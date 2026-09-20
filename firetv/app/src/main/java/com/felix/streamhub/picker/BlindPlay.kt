package com.felix.streamhub.picker

import android.util.Log
import com.felix.streamhub.TvKeys
import com.felix.streamhub.picker.ProfilePickerService.State
import com.felix.streamhub.picker.ProfilePickerService.Status

/**
 * Starts a title on the two services whose screens can't be read: HBO Max and
 * Netflix (see their sections in [ProfilePickers] for what was observed on the
 * real TV). Disney+ and Prime Video are driven by [ProfilePickerService],
 * which can check the screen before every press. Here nothing can be checked
 * first, so:
 *
 *  - **HBO Max** is checked afterwards. Its media session names what plays, so
 *    a wrong title is stopped (Back) straight away and the phone is told.
 *  - **Netflix** cannot be checked at all - not before, not after. Its media
 *    session reports "playing" with no title for the trailers on its own home
 *    screen, and playback shares the window and activity of every other
 *    screen. So it is driven only as far as its search results, with the title
 *    highlighted, and the last press is Félix's.
 */
object BlindPlay {

    private const val TAG = "StreamHubPicker"

    private fun packagesOf(serviceId: String) =
        com.felix.streamhub.data.Services.byId(serviceId)?.packages ?: emptyList()

    private val HBO = packagesOf(ProfilePickers.Hbo.SERVICE_ID)
    private val NETFLIX = packagesOf(ProfilePickers.Netflix.SERVICE_ID)

    private const val FRONT_WAIT_MS = 25_000L
    /** HBO Max: cold start to a usable search page took ~14 s on the TV. */
    private const val HBO_COLD_MS = 16_000L
    private const val HBO_WARM_MS = 4_000L
    /** Netflix is always started cold, and takes a while to show its profiles. */
    private const val NETFLIX_PICKER_MS = 18_000L
    /** At most this long for the home screen; its trailer says when it's there. */
    private const val NETFLIX_HOME_MS = 22_000L
    private const val NETFLIX_HOME_SETTLE_MS = 4_000L
    private const val MENU_SETTLE_MS = 1_500L
    private const val NETFLIX_SEARCH_MS = 4_000L
    private const val RESULTS_MS = 3_500L
    private const val KEY_GAP_MS = 400L
    private const val PAGE_MS = 5_000L
    private const val PLAY_CONFIRM_MS = 25_000L
    private const val POLL_MS = 1_000L

    private class Job(
        val deviceId: String,
        val serviceId: String,
        val packages: List<String>,
        val wanted: ProfilePickers.Wanted,
        val text: String,
        val wasRunning: Boolean,
        /** What this service's media session said before we started, if anything. */
        val before: Pair<String, String>?,
        /** Netflix only: which place in its profile list is this phone's. */
        val place: Int?
    ) {
        fun report(state: State, message: String) =
            ProfilePickerService.publish(Status(deviceId, serviceId, wanted.title, state, message))
    }

    @Volatile private var current: Job? = null

    /**
     * Whether [serviceId] is one this drives. Netflix also needs to know which
     * profile is this phone's, by place in the list: its names can't be read.
     */
    fun canAutoplay(serviceId: String, netflixPlace: Int?): Boolean = when (serviceId) {
        ProfilePickers.Hbo.SERVICE_ID -> TvKeys.helperVersion() >= 2
        ProfilePickers.Netflix.SERVICE_ID -> netflixPlace != null && TvKeys.helperVersion() >= 3
        else -> false
    }

    /** A newer request (any service) replaces this one. */
    fun cancel() { current = null }

    /**
     * Call BEFORE the app is launched: for HBO Max whether it was already
     * running decides how long its page gets to load, and Netflix is closed
     * here so that the launch starts from its profile screen.
     */
    fun start(deviceId: String, serviceId: String, wanted: ProfilePickers.Wanted, netflixPlace: Int?): Boolean {
        if (!canAutoplay(serviceId, netflixPlace)) return false
        val text = ProfilePickers.SearchKeyboard.searchText(wanted.title)
        if (text.isEmpty() || ProfilePickers.SearchKeyboard.rightsToFirstResult(text) == null) return false
        val packages = packagesOf(serviceId)
        if (packages.isEmpty()) return false
        ProfilePickerService.disarm()

        val netflix = serviceId == ProfilePickers.Netflix.SERVICE_ID
        // Netflix from cold, always: an app already open could be on any
        // screen, and its screen can't be read to find out.
        if (netflix) NETFLIX.forEach { TvKeys.stopApp(it) }
        val wasRunning = !netflix && packages.any { TvKeys.isRunning(it) == true }
        val job = Job(deviceId, serviceId, packages, wanted, text, wasRunning, playingNow(packages), netflixPlace)
        current = job
        job.report(State.WORKING, "Looking for “${wanted.title}”")
        Thread({
            runCatching { if (netflix) runNetflix(job) else runHbo(job) }
                .onFailure {
                    Log.w(TAG, "$serviceId: failed", it)
                    job.report(State.STOPPED, "Stopped: something went wrong")
                }
        }, "StreamHub-blind").start()
        return true
    }

    // ---- HBO Max -----------------------------------------------------------

    private fun runHbo(job: Job) {
        if (!waitForFront(job)) return
        // Its search page is loaded. Nothing on screen says when, so wait.
        Thread.sleep(if (job.wasRunning) HBO_WARM_MS else HBO_COLD_MS)
        if (!live(job)) return
        if (!inFront(job)) return stop(job, "HBO Max was left before the title could be typed")

        Log.i(TAG, "hbomax: typing “${job.text}” (${if (job.wasRunning) "warm" else "cold"})")
        if (TvKeys.type(job.text) != TvKeys.Result.Pressed) return stop(job, "the TV didn't take the typing")
        job.report(State.WORKING, "Opening “${job.wanted.title}”")
        Thread.sleep(RESULTS_MS)
        if (!stepToFirstResult(job)) return
        press(TvKeys.Key.OK)
        Thread.sleep(PAGE_MS)
        if (!live(job)) return
        if (!inFront(job)) return stop(job, "HBO Max was left before Play")

        job.report(State.WORKING, "Starting “${job.wanted.title}”")
        press(TvKeys.Key.OK)
        val confirmBy = System.currentTimeMillis() + PLAY_CONFIRM_MS
        while (System.currentTimeMillis() < confirmBy) {
            Thread.sleep(POLL_MS)
            if (!live(job)) return
            val now = playingNow(job.packages) ?: continue
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
                    press(TvKeys.Key.BACK)
                    return stop(job, "HBO Max started “$title”, not “${job.wanted.title}”, so it was stopped")
                }
            }
        }
        stop(job, "HBO Max didn't start playing. It may be waiting on the TV: use the remote pad")
    }

    // ---- Netflix -----------------------------------------------------------

    /**
     * Netflix stops at its search results, with the title highlighted: Félix
     * presses OK himself. It is never driven into playback, because nothing
     * here can be checked - the TV cannot tell Netflix playing a film from
     * Netflix sitting on a menu (same activity, same window, and its media
     * session reports "playing" with no title for the trailers on its own home
     * screen). An early run that pressed Play blind started the last thing
     * watched instead of the title asked for, and nothing could detect that.
     */
    private fun runNetflix(job: Job) {
        val downs = ProfilePickers.Netflix.downsToProfile(job.place ?: return) ?: return
        if (!waitForFront(job)) return
        Thread.sleep(NETFLIX_PICKER_MS)
        if (!live(job)) return
        if (!inFront(job)) return stop(job, "Netflix was left before a profile was chosen")

        // "Who's watching?", by place: Up stops at the top of the list, so this
        // lands on the right profile without reading any name.
        pressTimes(TvKeys.Key.UP, ProfilePickers.Netflix.UPS_TO_FIRST_PROFILE)
        pressTimes(TvKeys.Key.DOWN, downs)
        if (!live(job)) return
        press(TvKeys.Key.OK)
        job.report(State.WORKING, "Opening “${job.wanted.title}”")
        if (!waitForNetflixHome(job)) return

        // Home screen -> the menu along the top -> Search. The OK below is the
        // only press here that could do something unwanted (Netflix plays the
        // banner if the menu isn't open yet), which is why the home screen is
        // given so long to settle first.
        press(TvKeys.Key.LEFT)
        pressTimes(TvKeys.Key.UP, 8)
        press(TvKeys.Key.LEFT)
        Thread.sleep(MENU_SETTLE_MS)
        if (!live(job)) return
        press(TvKeys.Key.OK)
        Thread.sleep(NETFLIX_SEARCH_MS)
        if (!live(job)) return
        if (!inFront(job)) return stop(job, "Netflix was left before the title could be typed")

        Log.i(TAG, "netflix: typing “${job.text}” for place ${job.place}")
        if (TvKeys.type(job.text) != TvKeys.Result.Pressed) return stop(job, "the TV didn't take the typing")
        Thread.sleep(RESULTS_MS)
        if (!stepToFirstResult(job)) return
        current = null
        Log.i(TAG, "netflix: left on the results")
        job.report(
            State.DONE,
            "Netflix is showing “${job.wanted.title}”, highlighted. Press OK below to play it"
        )
    }

    /**
     * Netflix's home screen, ready for key presses. Nothing on screen says
     * when: the sign used is its home screen's own trailer starting to play.
     * With previews switched off that never comes, so the full wait is used.
     */
    private fun waitForNetflixHome(job: Job): Boolean {
        val until = System.currentTimeMillis() + NETFLIX_HOME_MS
        while (System.currentTimeMillis() < until) {
            Thread.sleep(POLL_MS)
            if (!live(job)) return false
            if (playingNow(job.packages) != null) break
        }
        Thread.sleep(NETFLIX_HOME_SETTLE_MS)
        if (!live(job)) return false
        if (!inFront(job)) {
            stop(job, "Netflix was left before its search opened")
            return false
        }
        return true
    }

    // ---- shared ------------------------------------------------------------

    private fun live(job: Job) = current === job

    private fun inFront(job: Job) = TvKeys.front() in job.packages

    private fun stop(job: Job, why: String) {
        Log.i(TAG, "${job.serviceId}: stopped: $why")
        if (live(job)) job.report(State.STOPPED, "Stopped: $why")
        current = null
    }

    private fun waitForFront(job: Job): Boolean {
        val until = System.currentTimeMillis() + FRONT_WAIT_MS
        while (!inFront(job)) {
            if (!live(job)) return false
            if (System.currentTimeMillis() > until) {
                stop(job, "${com.felix.streamhub.data.Services.byId(job.serviceId)?.name ?: job.serviceId} didn't open")
                return false
            }
            Thread.sleep(POLL_MS)
        }
        return true
    }

    /** From the key last typed into the first result (both keyboards match). */
    private fun stepToFirstResult(job: Job): Boolean {
        val rights = ProfilePickers.SearchKeyboard.rightsToFirstResult(job.text) ?: return false
        pressTimes(TvKeys.Key.RIGHT, rights)
        return live(job)
    }

    private fun press(key: TvKeys.Key) {
        TvKeys.press(key)
        Thread.sleep(KEY_GAP_MS)
    }

    private fun pressTimes(key: TvKeys.Key, times: Int) = repeat(times) { press(key) }

    private fun playingNow(packages: List<String>): Pair<String, String>? =
        TvKeys.nowPlaying()?.firstOrNull { it.first in packages }
}
