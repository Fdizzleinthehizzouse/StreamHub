package com.felix.streamhub.picker

import android.util.Log
import com.felix.streamhub.TvKeys
import com.felix.streamhub.picker.ProfilePickerService.State
import com.felix.streamhub.picker.ProfilePickerService.Status

/**
 * Starts a title on HBO Max, whose screen can't be read (see the HBO section
 * of [ProfilePickers] for what was observed). Unlike Disney+ and Prime Video,
 * nothing can be confirmed before a key is pressed, so the check comes after:
 * the TV's media session has to name the title. If HBO Max starts something
 * else, it is stopped straight away (Back) and the phone is told.
 *
 * The steps: wait for HBO Max to be in front, give its search page time to
 * load, type the title, step Right from the keyboard into the first result,
 * OK (the title's page), OK (Watch / Continue).
 */
object BlindPlay {

    private const val TAG = "StreamHubPicker"
    private val HBO_PACKAGES = com.felix.streamhub.data.Services.byId(ProfilePickers.Hbo.SERVICE_ID)?.packages ?: emptyList()

    private const val FRONT_WAIT_MS = 25_000L
    /** Cold start to a usable search page took ~14 s on the TV. */
    private const val COLD_LOAD_MS = 16_000L
    private const val WARM_LOAD_MS = 4_000L
    private const val RESULTS_MS = 3_500L
    private const val KEY_GAP_MS = 400L
    private const val PAGE_MS = 5_000L
    private const val PLAY_CONFIRM_MS = 25_000L
    private const val POLL_MS = 1_000L

    private class Job(val deviceId: String, val wanted: ProfilePickers.Wanted, val wasRunning: Boolean, val before: Pair<String, String>?) {
        fun report(state: State, message: String) =
            ProfilePickerService.publish(Status(deviceId, ProfilePickers.Hbo.SERVICE_ID, wanted.title, state, message))
    }

    @Volatile private var current: Job? = null

    /** Whether [serviceId] is one this drives, and the helper can do all of it. */
    fun canAutoplay(serviceId: String): Boolean =
        serviceId == ProfilePickers.Hbo.SERVICE_ID && TvKeys.helperVersion() >= 2

    /** A newer request (any service) replaces this one. */
    fun cancel() { current = null }

    /**
     * Call BEFORE launching HBO Max's search page: whether the app was already
     * running decides how long its page gets to load.
     */
    fun start(deviceId: String, wanted: ProfilePickers.Wanted): Boolean {
        val text = ProfilePickers.Hbo.searchText(wanted.title)
        if (text.isEmpty() || ProfilePickers.Hbo.rightsToFirstResult(text) == null) return false
        ProfilePickerService.disarm()
        val wasRunning = HBO_PACKAGES.any { TvKeys.isRunning(it) == true }
        val before = hboPlaying()
        val job = Job(deviceId, wanted, wasRunning, before)
        current = job
        job.report(State.WORKING, "Looking for “${wanted.title}”")
        Thread({ runCatching { run(job, text) }.onFailure { Log.w(TAG, "hbomax: failed", it); job.report(State.STOPPED, "Stopped: something went wrong") } }, "StreamHub-HBO").start()
        return true
    }

    private fun run(job: Job, text: String) {
        fun live() = current === job
        fun stop(why: String) {
            Log.i(TAG, "hbomax: stopped: $why")
            if (live()) job.report(State.STOPPED, "Stopped: $why")
            current = null
        }

        // 1. HBO Max in front.
        val until = System.currentTimeMillis() + FRONT_WAIT_MS
        while (TvKeys.front() !in HBO_PACKAGES) {
            if (!live()) return
            if (System.currentTimeMillis() > until) return stop("HBO Max didn't open")
            Thread.sleep(POLL_MS)
        }
        // 2. Its search page loaded. Nothing on screen says when, so wait.
        Thread.sleep(if (job.wasRunning) WARM_LOAD_MS else COLD_LOAD_MS)
        if (!live()) return
        if (TvKeys.front() !in HBO_PACKAGES) return stop("HBO Max was left before the title could be typed")

        // 3. Type, and step into the first result.
        Log.i(TAG, "hbomax: typing “$text” (${if (job.wasRunning) "warm" else "cold"})")
        if (TvKeys.type(text) != TvKeys.Result.Pressed) return stop("the TV didn't take the typing")
        job.report(State.WORKING, "Opening “${job.wanted.title}”")
        Thread.sleep(RESULTS_MS)
        repeat(ProfilePickers.Hbo.rightsToFirstResult(text)!!) {
            if (!live()) return
            TvKeys.press(TvKeys.Key.RIGHT)
            Thread.sleep(KEY_GAP_MS)
        }
        if (!live()) return
        TvKeys.press(TvKeys.Key.OK)
        Thread.sleep(PAGE_MS)
        if (!live()) return
        if (TvKeys.front() !in HBO_PACKAGES) return stop("HBO Max was left before Play")

        // 4. Watch, then make sure it's the right thing playing.
        job.report(State.WORKING, "Starting “${job.wanted.title}”")
        TvKeys.press(TvKeys.Key.OK)
        val confirmBy = System.currentTimeMillis() + PLAY_CONFIRM_MS
        while (System.currentTimeMillis() < confirmBy) {
            Thread.sleep(POLL_MS)
            if (!live()) return
            val now = hboPlaying() ?: continue
            val title = now.second
            when {
                ProfilePickers.namesTitle(title, job.wanted.title, job.wanted.isMovie) -> {
                    Log.i(TAG, "hbomax: playing “$title”")
                    job.report(State.PLAYING, "Playing")
                    current = null
                    return
                }
                // Whatever was playing before we started, still reporting.
                now == job.before -> continue
                title.isBlank() -> {
                    Log.i(TAG, "hbomax: playing, unnamed")
                    job.report(State.PLAYING, "Playing (HBO Max didn’t say what, so check it’s the right one)")
                    current = null
                    return
                }
                else -> {
                    // Never leave the wrong thing playing.
                    TvKeys.press(TvKeys.Key.BACK)
                    return stop("HBO Max started “$title”, not “${job.wanted.title}”, so it was stopped")
                }
            }
        }
        stop("HBO Max didn't start playing. It may be waiting on the TV: use the remote pad")
    }

    private fun hboPlaying(): Pair<String, String>? = TvKeys.nowPlaying()?.firstOrNull { it.first in HBO_PACKAGES }
}
