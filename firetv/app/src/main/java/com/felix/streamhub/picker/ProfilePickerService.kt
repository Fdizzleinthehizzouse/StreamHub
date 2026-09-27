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
            // Not "nothing to do": on a real TV that was all the phone got
            // when Disney+ never showed a screen this knows.
            job.stop(job.waitingFor ?: "${serviceName(job.serviceId)} never showed its profiles, its search or the title")
            Log.i(TAG, "${job.serviceId}: gave up after ${ARM_MS / 1000}s: ${job.waitingFor}")
            return false
        }

        // Playback confirmation needs no screen: the media session says it.
        if (job.playPressedAt != 0L) {
            val expect = job.expectName
            if (expect != null) return confirmEpisode(job, expect, now)
            val playing = TvKeys.playing()
            if (playing != null && playing.any { it in job.picker.packages }) {
                armed = null
                // "Playing Season 2, Episode 5", or for Continue what the
                // service's own button said: "Playing — Resume Episode 4".
                val message = job.autoplay?.episode?.let { "Playing ${it.label}" }
                    ?: job.playLabel?.let { "Playing — $it" }
                    ?: "Playing"
                job.report(State.PLAYING, message)
                Log.i(TAG, "${job.serviceId}: $message")
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

        // A chosen episode: the page was confirmed below, and moving down it
        // scrolls the name and Play button away, so it isn't checked again.
        wanted.episode?.let { ep -> if (job.pageConfirmed) return chooseEpisode(job, tree, ep, now) }

        job.waitingFor = "the title’s page didn’t show a Play button"
        val play = playButton(tree) as Live?
        // A chosen episode needs the page, not its Play button, which Prime
        // drops when it counts the current episode as watched (seen on the TV).
        val onPage = play != null || (wanted.episode != null || job.picker.noResume?.invoke(tree) == true) &&
            job.picker.onTitlePage?.invoke(tree) == true
        if (!onPage) {
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
        wanted.episode?.let { ep ->
            job.pageConfirmed = true
            job.pageConfirmedAt = now
            job.report(State.WORKING, "Finding ${ep.label}")
            return chooseEpisode(job, tree, ep, now)
        }
        // Continue: the page's own Play is the service's Resume / Continue.
        if (play == null) {
            // Only "Watch from beginning" on offer. That isn't continuing, so
            // it isn't pressed; the phone is told what to do instead. A moment
            // first, in case the page is still drawing its buttons.
            if (job.noResumeSince == 0L) job.noResumeSince = now
            if (now - job.noResumeSince < PAGE_YEAR_WAIT_MS) return true
            armed = null
            job.stop("it isn’t offering to continue “${title}”, only to watch an episode from the beginning. Choose the episode under Episodes instead")
            return false
        }
        // What it says it resumes is passed on, so the phone can tell you.
        job.playLabel = job.picker.playLabel?.invoke(tree)
        if (!select(play)) { armed = null; job.stop("couldn’t get the highlight onto Play"); return false }
        job.playPressedAt = now
        job.report(State.WORKING, "Starting “${title}”")
        return true
    }

    /**
     * On a series' page, confirmed to be the right one: get the highlight to
     * [ep] and start it. One step per look, each decided from the screen as
     * it is now, so nothing is pressed on a guess; OK only once the
     * highlighted card itself says it is that season and episode.
     * @return true to keep looking.
     */
    private fun chooseEpisode(job: Job, tree: ProfilePickers.Node, ep: ProfilePickers.Episode, now: Long): Boolean {
        val service = serviceName(job.serviceId)
        fun give(why: String): Boolean {
            armed = null
            job.stop(why)
            // As for a missing profile: the screen, one line per entry, so a
            // redesign can be fixed from logcat alone.
            Log.w(TAG, "${job.serviceId}: gave up on ${ep.label}. Screen:")
            describe(tree).lineSequence().forEach { Log.w(TAG, it) }
            return false
        }
        val list = job.picker.episodes ?: return give("$service can’t be sent to one episode")
        // Let the last press land (Prime's season list takes a moment to close).
        if (now < job.waitUntil) return true
        if (++job.episodeLooks > MAX_EPISODE_LOOKS) return give("couldn’t reach ${ep.label} in $service’s list")

        // 1. The right season listed.
        val offered = list.seasons(tree)
        val shown = list.seasonShown(tree)
        val cardsNow = list.cards(tree)
        Log.i(
            TAG,
            "${job.serviceId}: episode look ${job.episodeLooks}: season shown=$shown offered=${offered.map { "${it.season}${if (it.highlighted) "*" else ""}" }} " +
                "opener=${list.seasonOpener?.invoke(tree) != null} cards=${cardsNow.map { "${it.season}x${it.episode}${if (it.highlighted) "*" else ""}" }} " +
                "focus=${findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { "${it.viewIdResourceName} \"${it.contentDescription}\"" }}"
        )
        // Another of the page's tabs showing: back to Episodes first.
        list.episodesTab?.invoke(tree)?.let { (tab, showing) ->
            if (!showing) {
                job.waitingFor = "couldn’t get back to the Episodes tab"
                val live = (tab as Live).info
                if (live.isFocused) {
                    if (!press(job, TvKeys.Key.OK)) return false
                } else if (!focusOn(live, keysOnly = true)) {
                    return give("couldn’t get the highlight onto $service’s Episodes tab")
                }
                job.waitUntil = now + NUDGE_SETTLE_MS
                return true
            }
        }
        if (shown != ep.season) {
            job.waitingFor = "couldn’t switch to Season ${ep.season}"
            if (offered.isNotEmpty()) {
                val target = offered.firstOrNull { it.season == ep.season }
                    ?: return give("$service has no Season ${ep.season} of “${job.autoplay?.title}”")
                if (list.seasonOpener == null) {
                    // Disney+: highlighting a season lists its episodes. Its
                    // highlight there and the season still not listed, a few
                    // looks running, means the list isn't following: stop.
                    if (target.highlighted && ++job.seasonStuck >= STUCK_LOOKS) {
                        return give("the highlight is on Season ${ep.season}, but $service still lists another season")
                    }
                    if (!focusOn((target.node as Live).info, keysOnly = true)) return give("couldn’t get the highlight onto Season ${ep.season}")
                    job.waitUntil = now + NUDGE_SETTLE_MS
                    return true
                }
                // Prime Video's open list shows its highlight as `selected`.
                val at = offered.indexOfFirst { it.highlighted }
                if (at < 0) return true // still drawing
                val arrow = ProfilePickers.arrowToward(at, offered.indexOf(target), horizontal = false)
                if (!press(job, arrow?.let(::keyOf) ?: TvKeys.Key.OK)) return false
                if (arrow == null) job.waitUntil = now + LIST_CLOSE_MS
                return true
            }
            list.seasonOpener?.invoke(tree)?.let { opener ->
                if (!select(opener as Live, keysOnly = true)) return give("couldn’t open $service’s list of seasons")
                job.waitUntil = now + LIST_CLOSE_MS
                return true
            }
            return nudgeDown(job, now, "the seasons")
        }

        // 2. The episode.
        job.waitingFor = "couldn’t find ${ep.label}"
        val cards = list.cards(tree).filter { it.season == ep.season }
        if (cards.isEmpty()) return nudgeDown(job, now, "the episodes")
        cards.firstOrNull { it.episode == ep.number }?.let { card ->
            if (!select(card.node as Live, keysOnly = true)) return give("couldn’t get the highlight onto ${ep.label}")
            job.playPressedAt = now
            if (list.sessionNamesEpisode) {
                job.expectName = card.name
                job.sessionBefore = TvKeys.nowPlaying()?.firstOrNull { it.first in job.picker.packages }
            }
            job.report(State.WORKING, "Starting ${ep.label}")
            Log.i(TAG, "${job.serviceId}: pressed OK on \"${card.node.desc}\"")
            return true
        }
        // Not on screen: walk the list towards it.
        val at = cards.firstOrNull { it.highlighted }
        if (at == null) {
            // On a season, beside the episodes (Disney+): step across.
            val across = list.intoEpisodesFromSeasons
            if (across != null && offered.any { it.highlighted }) {
                job.waitUntil = now + NUDGE_SETTLE_MS
                return press(job, keyOf(across))
            }
            val nearest = cards.minByOrNull { Math.abs(it.episode!! - ep.number) }!!
            if (!focusOn((nearest.node as Live).info, keysOnly = true)) return give("couldn’t get the highlight into $service’s episodes")
            return true
        }
        // The highlight no longer moves: the end of the list, or the service
        // not taking the key. Either way, say where it stopped - not a guess
        // at which.
        if (at.episode == job.lastEpisodeAt) {
            if (++job.stuck >= STUCK_LOOKS) {
                return give("the highlight stopped at Season ${ep.season}, Episode ${at.episode} in $service’s list, short of Episode ${ep.number}")
            }
        } else {
            job.stuck = 0
            job.lastEpisodeAt = at.episode
        }
        val arrow = ProfilePickers.arrowToward(at.episode!!, ep.number, list.horizontal) ?: return true
        job.waitUntil = now + STEP_SETTLE_MS
        return press(job, keyOf(arrow))
    }

    /**
     * The media session must name the episode whose card was pressed. The
     * one that was playing before, still reporting, is waited out; anything
     * else is the wrong thing, and is stopped.
     */
    private fun confirmEpisode(job: Job, expect: String, now: Long): Boolean {
        val ep = job.autoplay?.episode
        val service = serviceName(job.serviceId)
        val playing = TvKeys.nowPlaying()?.firstOrNull { it.first in job.picker.packages }
        when {
            playing == null || playing == job.sessionBefore || playing.second.isBlank() -> Unit
            ProfilePickers.namesTitle(playing.second, expect) -> {
                armed = null
                // Prime quotes some names itself: "\"Nothing Like it in the World\"".
                job.report(State.PLAYING, "Playing ${ep?.label}: “${playing.second.trim('"', '“', '”', ' ')}”")
                Log.i(TAG, "${job.serviceId}: playing \"${playing.second}\"")
                return false
            }
            else -> {
                armed = null
                TvKeys.press(TvKeys.Key.BACK)
                job.stop("$service started “${playing.second}”, not ${ep?.label} (“$expect”), so it was stopped")
                return false
            }
        }
        if (now - job.playPressedAt > PLAY_CONFIRM_MS) {
            armed = null
            job.stop("pressed OK on ${ep?.label}, but $service didn’t say it started")
            return false
        }
        return true
    }

    /** The list isn't on screen yet: it is further down the page. Down only - never OK. */
    private fun nudgeDown(job: Job, now: Long, what: String): Boolean {
        // Seen on the TV: Prime drew the page's name and Play button seconds
        // before anything below them, and Down pressed into that half-drawn
        // page carried the highlight past the episodes. Give it time first.
        if (now - job.pageConfirmedAt < PAGE_SETTLE_MS) return true
        if (++job.nudges > MAX_NUDGES) {
            armed = null
            job.stop("couldn’t find $what on the page of “${job.autoplay?.title}”")
            return false
        }
        job.waitUntil = now + NUDGE_SETTLE_MS
        return press(job, TvKeys.Key.DOWN)
    }

    private fun press(job: Job, key: TvKeys.Key): Boolean {
        if (TvKeys.press(key) == TvKeys.Result.Pressed) return true
        armed = null
        job.stop("the TV didn’t take the ${key.name.lowercase()} press")
        return false
    }

    private fun keyOf(a: ProfilePickers.Arrow) = when (a) {
        ProfilePickers.Arrow.UP -> TvKeys.Key.UP
        ProfilePickers.Arrow.DOWN -> TvKeys.Key.DOWN
        ProfilePickers.Arrow.LEFT -> TvKeys.Key.LEFT
        ProfilePickers.Arrow.RIGHT -> TvKeys.Key.RIGHT
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
    private fun select(node: Live, keysOnly: Boolean = false): Boolean {
        if (!focusOn(node.info, keysOnly)) {
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
    private fun focusOn(target: AccessibilityNodeInfo, keysOnly: Boolean = false): Boolean {
        if (focusedOn(target, 1)) return true
        // keysOnly: seen on the TV, Disney+ let ACTION_FOCUS put its highlight
        // on an episode at the bottom of its page, but then ignored Down from
        // there - the page never scrolled. Moved by arrows, as a remote would,
        // its lists behave.
        if (!keysOnly) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (focusedOn(target, SETTLE_TRIES)) return true
        }

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
        override val focused get() = info.isFocused
        override val selected get() = info.isSelected
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
        /** What the page's Play button says it resumes ("Resume Episode 8"). */
        var playLabel: String? = null
        // A chosen episode (see chooseEpisode).
        var pageConfirmed = false
        var pageConfirmedAt = 0L
        var noResumeSince = 0L
        var waitUntil = 0L
        var episodeLooks = 0
        var nudges = 0
        var lastEpisodeAt: Int? = null
        var stuck = 0
        var seasonStuck = 0
        /** The service's own name for the episode pressed, to find in the media session. */
        var expectName: String? = null
        var sessionBefore: Pair<String, String>? = null

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
        /** Walking to an episode: a season of 25 plus the moves around it. */
        private const val MAX_EPISODE_LOOKS = 80
        private const val MAX_NUDGES = 5
        private const val NUDGE_SETTLE_MS = 800L
        private const val PAGE_SETTLE_MS = 3_000L
        private const val STEP_SETTLE_MS = 400L
        private const val STUCK_LOOKS = 3
        private const val LIST_CLOSE_MS = 1_500L
        /** Finding an episode adds up to this to [ARM_MS]. */
        const val EPISODE_EXTRA_MS = 60_000L
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
            // An episode in a service that can't list them is refused here,
            // not half-done: the phone is told, and nothing is opened blind.
            if (autoplay?.episode != null && (a == null || picker.episodes == null)) return false
            val until = System.currentTimeMillis() + ARM_MS + if (a?.episode != null) EPISODE_EXTRA_MS else 0L
            val job = Job(picker, serviceId, deviceId, name, q, a, relaunch, until)
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
