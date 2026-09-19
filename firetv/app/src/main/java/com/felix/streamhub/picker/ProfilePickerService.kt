package com.felix.streamhub.picker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Clicks your profile on a service's "who's watching" screen, and nothing else.
 *
 * Armed only by a play request from a phone that has a profile name for that
 * service, for [ARM_MS]. Outside that window every event is ignored. The
 * service config also limits events to the Disney+ and Prime Video packages.
 * Nothing is stored or reported: this is not usage tracking.
 *
 * Fire TV has no settings screen to enable a sideloaded accessibility
 * service; see the README for the adb commands.
 */
class ProfilePickerService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    // Looked at on a timer while armed, not only on events. On a real Fire TV,
    // Disney+ sends one window event at start-up - before its screen exists -
    // and none after, even once the picker is up. Waiting for events never saw it.
    private val poll = object : Runnable {
        override fun run() {
            if (check()) main.postDelayed(this, POLL_MS)
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
        main.removeCallbacks(poll)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (armed != null) startPolling()
    }

    fun startPolling() {
        main.removeCallbacks(poll)
        main.post(poll)
    }

    /** @return true to keep looking. */
    private fun check(): Boolean {
        val job = armed ?: return false
        if (System.currentTimeMillis() > job.until) {
            armed = null
            Log.i(TAG, "${job.serviceId}: no picker within ${ARM_MS / 1000}s; nothing done")
            return false
        }

        val root = rootInActiveWindow ?: return true
        if (root.packageName?.toString() !in job.picker.packages) return true
        val tree = Live(root, null)

        when (val d = job.settle.next(job.picker.recognise(tree, job.profileName))) {
            ProfilePickers.Settle.Decision.KeepLooking -> return true
            ProfilePickers.Settle.Decision.GiveUp -> {
                // Never guess: leave the person on the picker.
                armed = null
                Log.w(TAG, "${job.serviceId}: picker is up but \"${job.profileName}\" is not on it (or not uniquely). Screen:")
                // One entry per line: logcat truncates a single entry at ~4 KB,
                // which on a real TV cut the tree off before the profile tiles.
                describe(tree).lineSequence().forEach { Log.w(TAG, it) }
            }
            is ProfilePickers.Settle.Decision.Click -> {
                armed = null
                val clicked = (d.node as Live).info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "${job.serviceId}: clicked \"${job.profileName}\" -> $clicked")
            }
        }
        return false
    }

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

    private class Job(val picker: ProfilePickers.Picker, val serviceId: String, val profileName: String, val until: Long) {
        val settle = ProfilePickers.Settle()
    }

    companion object {
        private const val TAG = "StreamHubPicker"

        /**
         * Long enough to cover the universal-search route, where the app only
         * opens once someone presses OK on the remote.
         */
        const val ARM_MS = 90_000L
        private const val POLL_MS = 500L

        @Volatile private var armed: Job? = null
        @Volatile private var running: ProfilePickerService? = null

        /** Whether the service has been enabled on this TV (see README). */
        val isRunning: Boolean get() = running != null

        /** @return false if this service has no recognizer or the name is blank. */
        fun arm(serviceId: String, profileName: String): Boolean {
            val picker = ProfilePickers.forService(serviceId) ?: return false
            if (profileName.isBlank()) return false
            armed = Job(picker, serviceId, profileName, System.currentTimeMillis() + ARM_MS)
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
