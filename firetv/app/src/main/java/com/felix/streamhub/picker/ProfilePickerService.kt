package com.felix.streamhub.picker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.felix.streamhub.TvKeys

/**
 * The TV helper: after a play request, picks your profile, fills in Disney+
 * search, and - where the screens can be read - opens the exact title and
 * starts it. Otherwise it only does Back / Home for the phone.
 *
 * Armed only by a play request, for [ARM_MS]; outside that window it does
 * nothing. The service config limits it to the Disney+ and Prime Video
 * packages. Nothing is stored or reported beyond the current request's status.
 *
 * Rule for every press: find the exact item, move the highlight onto it,
 * confirm the highlight is there, then press OK (a real key press via
 * TvKeys - Prime Video ignores accessibility clicks). Anything unexpected:
 * stop and say where.
 *
 * Fire TV has no settings screen to enable a sideloaded accessibility
 * service; see the README for the adb commands.
 */
class ProfilePickerService : AccessibilityService() {

    // Not the main thread: key presses and the "is it playing" check use a
    // local socket, which Android forbids on the main thread.
    private val worker = HandlerThread("tv-helper").apply { start() }
    private val handler = Handler(worker.looper)

    // Looked at on a timer while armed, not only on events. On a real Fire TV,
    // Disney+ sends one window event at start-up - before its screen exists -
    // and none after, even once the picker is up. Waiting for events never saw it.
    private val poll = object : Runnable {
        override fun run() {
            val again = runCatching { check() }.onFailure { Log.w(TAG, "check failed", it) }.getOrDefault(false)
            if (again) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        // Also set in res/xml, but a reinstall kept serving the old flags on a
        // real TV until the service was switched off and on. Set here too.
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        running = this
        if (armed != null) startPolling()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = null
        handler.removeCallbacks(poll)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (armed != null) startPolling()
    }

    fun startPolling() {
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    /** @return true to keep looking. */
    private fun check(): Boolean {
        val job = armed ?: return false
        val now = System.currentTimeMillis()
        if (now > job.until) {
            armed = null
            job.stop(job.waitingFor ?: "nothing to do")
            Log.i(TAG, "${job.serviceId}: gave up after ${ARM_MS / 1000}s: ${job.waitingFor}")
            return false
        }

        // Playback confirmation needs no screen: the media session says it.
        if (job.playPressedAt != 0L) {
            val playing = TvKeys.playing()
            if (playing != null && playing.any { it in job.picker.packages }) {
                armed = null
                job.report(State.PLAYING, "Playing")
                Log.i(TAG, "${job.serviceId}: playing")
                return false
            }
            if (now - job.playPressedAt > PLAY_CONFIRM_MS) {
                armed = null
                job.stop("pressed Play, but nothing started")
                return false
            }
            return true
        }

        val root = rootInActiveWindow ?: return true
        if (root.packageName?.toString() !in job.picker.packages) return true
        val tree = Live(root, null)

        // The search box showing means we are already in a profile.
        if (job.query != null && !job.typed) {
            job.picker.searchBox?.invoke(tree)?.let { box ->
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.query)
                }
                val typed = (box as Live).info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.i(TAG, "${job.serviceId}: typed the title into search -> $typed")
                job.typed = true
                job.profileDone = true
                if (job.autoplay == null) { armed = null; job.report(State.DONE, "Search filled in"); return false }
                return true
            }
        }

        val name = job.profileName
        if (name == null && job.autoplay != null && job.picker.recognise(tree, NO_NAME) != ProfilePickers.Outcome.NotPicker) {
            // The picker is up and we don't know whose profile to choose.
            armed = null
            job.stop("${serviceName(job.serviceId)} is asking who’s watching. Add your profile name in Settings → Your profiles")
            return false
        }
        if (name != null && !job.profileDone) {
            when (val d = job.settle.next(job.picker.recognise(tree, name))) {
                ProfilePickers.Settle.Decision.KeepLooking -> {
                    // Not the picker (yet). If it's the results, we're past it.
                    if (job.autoplay == null || job.picker.resultTile?.invoke(tree, job.autoplay) == null) return true
                    job.profileDone = true
                }
                ProfilePickers.Settle.Decision.GiveUp -> {
                    // Never guess: leave the person on the picker.
                    armed = null
                    job.stop("your profile “${name}” isn’t on the “Who’s watching” screen")
                    Log.w(TAG, "${job.serviceId}: picker is up but \"$name\" is not on it (or not uniquely). Screen:")
                    // One entry per line: logcat truncates a single entry at ~4 KB,
                    // which on a real TV cut the tree off before the profile tiles.
                    describe(tree).lineSequence().forEach { Log.w(TAG, it) }
                    return false
                }
                is ProfilePickers.Settle.Decision.Click -> {
                    val tile = d.node as Live
                    // Key press if the key helper is up; else the accessibility click
                    // that works on Disney+.
                    val ok = select(tile) || tile.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "${job.serviceId}: picked profile \"$name\" -> $ok")
                    job.profileDone = true
                    job.profilePickedAt = now
                    job.report(State.WORKING, "Picked your profile")
                    return job.query != null || job.autoplay != null
                }
            }
        }

        val title = job.autoplay
        if (title == null) {
            if (job.query == null) { armed = null; job.report(State.DONE, "Profile picked"); return false }
            job.waitingFor = "the search box didn’t appear"
            return true
        }
        // Set whenever autoplay is armed (see canAutoplay).
        val resultTile = job.picker.resultTile!!
        val playButton = job.picker.playButton!!

        // Some apps drop where they were headed once a profile is picked;
        // send the title again, once, if the results haven't shown up.
        if (job.profilePickedAt != 0L && !job.tileOpened && !job.relaunched &&
            now - job.profilePickedAt > RELAUNCH_AFTER_MS && resultTile(tree, title) == null
        ) {
            job.relaunched = true
            Log.i(TAG, "${job.serviceId}: results not shown after the profile pick; sending the title again")
            job.relaunch?.invoke()
            return true
        }

        if (!job.tileOpened) {
            job.waitingFor = "“${title}” wasn’t in the search results"
            val tile = resultTile(tree, title) as Live? ?: return true
            if (!select(tile)) { armed = null; job.stop("couldn’t highlight “${title}” in the results"); return false }
            job.tileOpened = true
            job.report(State.WORKING, "Opening “${title}”")
            return true
        }

        job.waitingFor = "the title’s page didn’t show a Play button"
        val play = playButton(tree) as Live? ?: return true
        // Only press Play on the right title's page.
        job.picker.pageTitle?.let { nameOf ->
            val shown = nameOf(tree) ?: return true // page still loading
            if (!ProfilePickers.describesTitle(shown, title)) {
                armed = null
                job.stop("opened “${shown}”, not “${title}”, so didn’t press Play")
                return false
            }
        }
        if (!select(play)) { armed = null; job.stop("couldn’t highlight Play"); return false }
        job.playPressedAt = now
        job.report(State.WORKING, "Starting “${title}”")
        return true
    }

    /**
     * Highlight [node], confirm the highlight is on it, then press OK.
     * @return false, with nothing pressed, if the highlight didn't land.
     */
    private fun select(node: Live): Boolean {
        val info = node.info
        if (!info.isFocused) info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // Identity, not position: on a real TV, Prime's results were still
        // sliding into place, so the same tile's bounds changed between the
        // focus and the check. Re-read this very node, and let it settle.
        repeat(SETTLE_TRIES) {
            Thread.sleep(SETTLE_MS)
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (info.refresh() && info.isFocused && focused != null && sameItem(focused, info)) {
                return TvKeys.press(TvKeys.Key.OK) == TvKeys.Result.Pressed
            }
        }
        Log.w(TAG, "highlight did not land on ${info.viewIdResourceName} \"${info.contentDescription}\"")
        return false
    }

    private fun sameItem(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean =
        a.viewIdResourceName == b.viewIdResourceName &&
            a.contentDescription?.toString() == b.contentDescription?.toString() &&
            a.text?.toString() == b.text?.toString()

    /** A live screen node, adapted for ProfilePickers. Children are read lazily. */
    private class Live(val info: AccessibilityNodeInfo, override val parent: Live?) : ProfilePickers.Node {
        override val text get() = info.text?.toString() ?: ""
        override val desc get() = info.contentDescription?.toString() ?: ""
        override val viewId get() = info.viewIdResourceName ?: ""
        override val clickable get() = info.isClickable
        override val bounds: List<Int> by lazy {
            val r = Rect().also { info.getBoundsInScreen(it) }
            listOf(r.left, r.top, r.right, r.bottom)
        }
        override val children: List<ProfilePickers.Node> by lazy {
            (0 until info.childCount).mapNotNull { i -> info.getChild(i)?.let { Live(it, this) } }
        }
    }

    enum class State { WORKING, PLAYING, DONE, STOPPED }

    /** What the phone that sent the title is told. */
    data class Status(val deviceId: String, val serviceId: String, val title: String?, val state: State, val message: String)

    private class Job(
        val picker: ProfilePickers.Picker,
        val serviceId: String,
        val deviceId: String,
        val profileName: String?,
        val query: String?,
        /** The title to open and start, or null to stop after profile/search. */
        val autoplay: String?,
        val relaunch: (() -> Unit)?,
        val until: Long
    ) {
        val settle = ProfilePickers.Settle()
        var profileDone = false
        var profilePickedAt = 0L
        var relaunched = false
        var typed = false
        var tileOpened = false
        var playPressedAt = 0L
        var waitingFor: String? = null

        fun report(state: State, message: String) {
            status = Status(deviceId, serviceId, autoplay ?: query, state, message)
        }

        fun stop(why: String) = report(State.STOPPED, "Stopped: $why")
    }

    companion object {
        private const val TAG = "StreamHubPicker"

        /** Generous: a cold start on a low-memory TV can take a while. */
        const val ARM_MS = 90_000L
        private const val POLL_MS = 500L
        private const val RELAUNCH_AFTER_MS = 6_000L
        private const val PLAY_CONFIRM_MS = 20_000L
        private const val SETTLE_TRIES = 8
        private const val SETTLE_MS = 150L
        /** Matches no profile: asks recognise only "is this the picker?". */
        private const val NO_NAME = " "

        private fun serviceName(id: String) = com.felix.streamhub.data.Services.byId(id)?.name ?: id

        @Volatile private var armed: Job? = null
        @Volatile private var running: ProfilePickerService? = null

        /** The latest request's progress, for the phone that sent it. */
        @Volatile var status: Status? = null
            private set

        /** Whether the service has been enabled on this TV (see README). */
        val isRunning: Boolean get() = running != null

        /**
         * Back / Home without the key helper (accessibility global actions).
         * @return null if the service is not enabled on this TV.
         */
        fun remote(key: String): Boolean? {
            val s = running ?: return null
            return when (key) {
                "back" -> s.performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> s.performGlobalAction(GLOBAL_ACTION_HOME)
                else -> false
            }
        }

        /** Whether [serviceId] can be driven all the way to playback. */
        fun canAutoplay(serviceId: String): Boolean {
            val p = ProfilePickers.forService(serviceId) ?: return false
            return running != null && p.resultTile != null && p.playButton != null
        }

        /**
         * Start watching [serviceId] for this request. Every play request
         * replaces the previous job, so a stale one never acts on the next
         * app. @return false if there is nothing this service can do.
         */
        fun arm(
            serviceId: String,
            deviceId: String,
            profileName: String?,
            query: String?,
            autoplay: String? = null,
            relaunch: (() -> Unit)? = null
        ): Boolean {
            armed = null
            val picker = ProfilePickers.forService(serviceId) ?: return false
            val name = profileName?.takeIf { it.isNotBlank() }
            val q = query?.takeIf { it.isNotBlank() && picker.searchBox != null }
            val a = autoplay?.takeIf { it.isNotBlank() && canAutoplay(serviceId) }
            if (name == null && q == null && a == null) return false
            val job = Job(picker, serviceId, deviceId, name, q, a, relaunch, System.currentTimeMillis() + ARM_MS)
            job.report(State.WORKING, if (a != null) "Looking for “${a}”" else "Working")
            armed = job
            running?.startPolling()
            return true
        }

        /** The tree as text, so a redesign can be fixed from `adb logcat` alone. */
        fun describe(n: ProfilePickers.Node, depth: Int = 0): String = buildString {
            append("  ".repeat(depth))
            append("click=${n.clickable} text=\"${n.text}\" desc=\"${n.desc}\" id=${n.viewId} ${n.bounds}\n")
            n.children.forEach { append(describe(it, depth + 1)) }
        }
    }
}
