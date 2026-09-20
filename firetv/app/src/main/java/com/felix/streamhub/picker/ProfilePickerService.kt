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
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // Every event type: see profile_picker_service.xml (stale screen copy).
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
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

        val root = currentRoot()
        if (root == null || root.packageName?.toString() !in job.picker.packages) {
            if (now - job.lastWaitLog > WAIT_LOG_MS) {
                job.lastWaitLog = now
                val seen = runCatching { windows.joinToString { "${it.type}/${it.isFocused}/${it.isActive}/${it.root?.packageName}" } }.getOrNull()
                Log.i(TAG, "${job.serviceId}: waiting for its screen; reading ${root?.packageName}; windows: $seen")
            }
            return true
        }
        val tree = Live(root, null)
        if (now - job.lastWaitLog > WAIT_LOG_MS && job.playPressedAt == 0L) {
            job.lastWaitLog = now
            hidden = 0
            unreadable = 0
            val ids = idsOnScreen(tree).joinToString()
            Log.i(
                TAG,
                "${job.serviceId}: looking (typed=${job.typed} profile=${job.profileDone} opened=${job.tileOpened}, " +
                    "read in ${System.currentTimeMillis() - now} ms, skipped $hidden of which $unreadable unreadable); " +
                    "read=${nodeCount(root)} live=${countLive(tree)}; windows=${windowsSeen()}; ids=$ids"
            )
        }

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

        val wanted = job.autoplay
        if (wanted == null) {
            if (job.query == null) { armed = null; job.report(State.DONE, "Profile picked"); return false }
            job.waitingFor = "the search box didn’t appear"
            // Seen on a real TV: sometimes Disney+ never showed its search.
            maybeRelaunch(job, now, "the search box didn’t appear")
            return true
        }
        val title = wanted.title
        // Set whenever autoplay is armed (see canAutoplay).
        val resultTile = job.picker.resultTile!!
        val playButton = job.picker.playButton!!

        // Some apps drop where they were headed (after a profile pick, or when
        // already open elsewhere); send the title again, once.
        if (!job.tileOpened && resultTile(tree, wanted) == null && playButton(tree) == null) {
            maybeRelaunch(job, now, "the search results didn’t appear")
        }

        if (!job.tileOpened) {
            job.waitingFor = "“${title}” wasn’t in the search results (or several matched)"
            val tile = resultTile(tree, wanted) as Live? ?: return true
            if (!select(tile)) { armed = null; job.stop("couldn’t get the highlight onto “${title}” in the results"); return false }
            job.tileOpened = true
            job.report(State.WORKING, "Opening “${title}”")
            return true
        }

        job.waitingFor = "the title’s page didn’t show a Play button"
        val play = playButton(tree) as Live?
        if (play == null) {
            // Say what's on screen now and then, so a stall can be diagnosed from logcat.
            if (now - job.lastWaitLog > WAIT_LOG_MS) {
                job.lastWaitLog = now
                Log.i(TAG, "${job.serviceId}: waiting for Play; page title=${job.picker.pageTitle?.invoke(tree)} " +
                    "ids=${idsOnScreen(tree).joinToString()}")
            }
            return true
        }
        // Only press Play on the right title's page.
        job.picker.pageTitle?.let { nameOf ->
            val page = nameOf(tree) ?: return true // page still loading
            // A film's year must be read before deciding. On a real TV, Prime
            // drew the name before the year; checking in between let a 1989
            // "Road House" request play the 2024 film.
            if (wanted.isMovie && wanted.year != null && page.year == null) {
                if (job.pageSeenAt == 0L) job.pageSeenAt = now
                if (now - job.pageSeenAt < PAGE_YEAR_WAIT_MS) return true
                armed = null
                job.stop("couldn’t see which year’s “${title}” this is, so didn’t press Play")
                return false
            }
            // A series' page shows its latest season's year, not its first.
            val shown = if (wanted.isMovie) page else page.copy(year = null)
            if (ProfilePickers.titleMatch(shown, wanted) == null) {
                armed = null
                val which = listOfNotNull(shown.name, shown.year?.let { "from $it" }).joinToString(" ")
                val asked = listOfNotNull(title, wanted.year?.takeIf { wanted.isMovie }?.let { "from $it" }).joinToString(" ")
                job.stop("opened “${which}”, not “${asked}”, so didn’t press Play")
                return false
            }
        }
        if (!select(play)) { armed = null; job.stop("couldn’t get the highlight onto Play"); return false }
        job.playPressedAt = now
        job.report(State.WORKING, "Starting “${title}”")
        return true
    }

    /**
     * The screen as it is now. On a real TV, after Prime Video moved from its
     * results to a title's page, rootInActiveWindow kept returning the results
     * - a stale window - so the helper waited for a Play button it was looking
     * straight past. The focused application window is asked for first, and
     * its root is refreshed rather than taken from the cache.
     */
    private fun currentRoot(): AccessibilityNodeInfo? {
        val focused = runCatching {
            windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
        }.getOrNull()
        return (focused ?: rootInActiveWindow)?.also { it.refresh() }
    }

    /** Every window this service can see, for when the screen reads as empty. */
    private fun windowsSeen(): String = runCatching {
        windows.joinToString(" | ") { w ->
            val r = w.root
            "t${w.type}${if (w.isFocused) "F" else ""}${if (w.isActive) "A" else ""}:${r?.packageName}/${r?.childCount}/${r?.let { nodeCount(it) }}"
        }
    }.getOrElse { "?" }

    private fun countLive(n: ProfilePickers.Node): Int = 1 + n.children.sumBy { countLive(it) }

    private fun nodeCount(n: AccessibilityNodeInfo): Int =
        1 + (0 until n.childCount).sumBy { i -> n.getChild(i)?.let { nodeCount(it) } ?: 0 }

    /** View ids on screen with how often each appears, for the stall log. */
    private fun idsOnScreen(root: ProfilePickers.Node): List<String> {
        val counts = LinkedHashMap<String, Int>()
        fun walk(n: ProfilePickers.Node) {
            if (n.viewId.isNotEmpty()) counts[n.viewId.substringAfter(":id/")] = (counts[n.viewId.substringAfter(":id/")] ?: 0) + 1
            n.children.forEach(::walk)
        }
        walk(root)
        return counts.entries.take(40).map { if (it.value > 1) "${it.key}×${it.value}" else it.key }
    }

    private fun maybeRelaunch(job: Job, now: Long, why: String) {
        // Not once the title is typed: on a real TV a resend then reopened an
        // empty search page, and the results never came.
        if (job.relaunched || job.typed) return
        val since = if (job.profilePickedAt != 0L) job.profilePickedAt else job.armedAt
        val wait = if (job.profilePickedAt != 0L) RELAUNCH_AFTER_PICK_MS else RELAUNCH_AFTER_OPEN_MS
        if (now - since < wait) return
        job.relaunched = true
        Log.i(TAG, "${job.serviceId}: $why; sending the title again")
        job.relaunch?.invoke()
    }

    /**
     * Get the highlight onto [node], confirm it's there, then press OK.
     * @return false, with nothing pressed, if the highlight didn't land.
     */
    private fun select(node: Live): Boolean {
        if (!focusOn(node.info)) {
            Log.w(TAG, "highlight did not land on ${node.info.viewIdResourceName} \"${node.info.contentDescription}\"")
            return false
        }
        return TvKeys.press(TvKeys.Key.OK) == TvKeys.Result.Pressed
    }

    /**
     * Ask for focus directly; if the app ignores that (Prime Video does, on a
     * real TV - it only moved for the tile already highlighted), steer with
     * real arrow presses towards the target, re-reading the highlight after
     * each one. Nothing is pressed except arrows; bounded; gives up if the
     * highlight stops moving.
     */
    private fun focusOn(target: AccessibilityNodeInfo): Boolean {
        if (focusedOn(target, 1)) return true
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (focusedOn(target, SETTLE_TRIES)) return true

        var lastBounds: Rect? = null
        repeat(MAX_STEPS) {
            val current = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val from = Rect().also { current.getBoundsInScreen(it) }
            if (from == lastBounds) return false // the highlight stopped moving
            lastBounds = from
            if (!target.refresh()) return false
            val to = Rect().also { target.getBoundsInScreen(it) }
            val key = direction(from, to) ?: return false
            if (TvKeys.press(key) != TvKeys.Result.Pressed) return false
            if (focusedOn(target, 3)) return true
        }
        return false
    }

    /** Identity, not position: results may still be sliding into place. */
    private fun focusedOn(target: AccessibilityNodeInfo, tries: Int): Boolean {
        repeat(tries) {
            Thread.sleep(SETTLE_MS)
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target.refresh() && target.isFocused && focused != null && sameItem(focused, target)) return true
        }
        return false
    }

    /** Which arrow moves the highlight from [from] towards [to]: rows first, then along the row. */
    private fun direction(from: Rect, to: Rect): TvKeys.Key? {
        val dy = to.centerY() - from.centerY()
        val dx = to.centerX() - from.centerX()
        return when {
            Math.abs(dy) > from.height() / 2 -> if (dy > 0) TvKeys.Key.DOWN else TvKeys.Key.UP
            Math.abs(dx) > from.width() / 2 -> if (dx > 0) TvKeys.Key.RIGHT else TvKeys.Key.LEFT
            else -> null
        }
    }

    private fun sameItem(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean =
        a.viewIdResourceName == b.viewIdResourceName &&
            a.contentDescription?.toString() == b.contentDescription?.toString() &&
            a.text?.toString() == b.text?.toString()

    /**
     * A live screen node, adapted for ProfilePickers. Children are read lazily.
     * Only what is visible on the TV counts: Prime Video opens a title's page on
     * top of its search results in the same window, and the hidden results
     * (with their own, empty header_title_logo) stayed in the tree, so the page
     * could not be read. `uiautomator dump`, which every fixture comes from,
     * leaves hidden nodes out the same way.
     */
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
            // refresh(): each node is re-read from the app, not Android's cached
            // copy. Disney+ swaps its screens inside one window, and on the real
            // TV the cache went on showing its profile screen (empty) long after
            // the search page had replaced it, so the search box was never found.
            // A failed refresh() is not a missing node: keep what Android
            // already has. Dropping those cost the whole profile list on
            // Disney+ - the screen read as "profilesContent" and nothing in
            // it, so no profile could be picked.
            (0 until info.childCount).mapNotNull { i ->
                val cached = info.getChild(i) ?: return@mapNotNull null
                // Re-reading a node is what keeps the screen current, but on
                // Disney+'s new profile screen (September 2026) refresh()
                // hands back the node with its children gone - the profiles
                // vanished and none could be picked. So a refreshed node is
                // used only while it still holds everything the old one did.
                val refreshed = AccessibilityNodeInfo.obtain(cached)
                val child = if (refreshed.refresh() && refreshed.childCount >= cached.childCount) refreshed else cached
                if (!child.isVisibleToUser) {
                    hidden++
                    if (child === cached) unreadable++
                    return@mapNotNull null
                }
                Live(child, this)
            }
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
        val autoplay: ProfilePickers.Wanted?,
        val relaunch: (() -> Unit)?,
        val until: Long
    ) {
        val settle = ProfilePickers.Settle()
        var profileDone = false
        var profilePickedAt = 0L
        val armedAt = System.currentTimeMillis()
        var pageSeenAt = 0L
        var lastWaitLog = 0L
        var relaunched = false
        var typed = false
        var tileOpened = false
        var playPressedAt = 0L
        var waitingFor: String? = null

        fun report(state: State, message: String) {
            status = Status(deviceId, serviceId, autoplay?.title ?: query, state, message)
        }

        fun stop(why: String) {
            Log.i(TAG, "$serviceId: stopped: $why")
            report(State.STOPPED, "Stopped: $why")
        }
    }

    companion object {
        private const val TAG = "StreamHubPicker"

        /** Generous: a cold start on a low-memory TV can take a while. */
        const val ARM_MS = 90_000L
        private const val POLL_MS = 500L
        private const val RELAUNCH_AFTER_PICK_MS = 6_000L
        /** A cold start on this TV took ~10 s to show anything; don't resend before. */
        private const val RELAUNCH_AFTER_OPEN_MS = 15_000L
        private const val MAX_STEPS = 12
        private const val PAGE_YEAR_WAIT_MS = 6_000L
        private const val WAIT_LOG_MS = 5_000L
        private const val PLAY_CONFIRM_MS = 20_000L
        private const val SETTLE_TRIES = 8
        private const val SETTLE_MS = 150L
        /** Matches no profile: asks recognise only "is this the picker?". */
        private const val NO_NAME = "__streamhub: no profile name__"

        private fun serviceName(id: String) = com.felix.streamhub.data.Services.byId(id)?.name ?: id

        @Volatile private var armed: Job? = null
        @Volatile private var running: ProfilePickerService? = null

        /** The latest request's progress, for the phone that sent it. */
        @Volatile var status: Status? = null
            private set

        /** Parts of the screen left out of the last read, for the diagnostic log. */
        @Volatile private var hidden = 0
        @Volatile private var unreadable = 0

        /** For [BlindPlay], which reports through the same status. */
        internal fun publish(s: Status) { status = s }

        /** A request [BlindPlay] handles replaces any job here. */
        internal fun disarm() { armed = null }

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
            autoplay: ProfilePickers.Wanted? = null,
            relaunch: (() -> Unit)? = null
        ): Boolean {
            armed = null
            BlindPlay.cancel()
            val picker = ProfilePickers.forService(serviceId) ?: return false
            val name = profileName?.takeIf { it.isNotBlank() }
            val q = query?.takeIf { it.isNotBlank() && picker.searchBox != null }
            val a = autoplay?.takeIf { it.title.isNotBlank() && canAutoplay(serviceId) }
            if (name == null && q == null && a == null) return false
            val job = Job(picker, serviceId, deviceId, name, q, a, relaunch, System.currentTimeMillis() + ARM_MS)
            job.report(State.WORKING, if (a != null) "Looking for “${a.title}”" else "Working")
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
