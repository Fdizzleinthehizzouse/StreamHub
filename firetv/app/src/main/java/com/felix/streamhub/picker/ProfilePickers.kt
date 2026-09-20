package com.felix.streamhub.picker

import java.util.Locale

/**
 * How to recognise each service's "who's watching" screen and find one named
 * profile on it. EXPECT THIS FILE TO BREAK when a service updates its app:
 * everything here is read off real screen dumps (Fire TV AFT6E0FA, Fire OS
 * 7.7.1.4, September 2026), not any published contract.
 *
 * To fix a break: open the app to its picker, run
 *   adb shell uiautomator dump /sdcard/p.xml && adb pull /sdcard/p.xml
 * or read the tree ProfilePickerService logs under the tag StreamHubPicker,
 * then update that service's section below.
 *
 * Netflix and HBO Max have no picker section on purpose. Both draw their whole
 * screen themselves and expose no text to accessibility, so there is nothing
 * to read. (HBO Max also skipped its picker on that TV.) HBO Max's section
 * near the end is for BlindPlay, which drives it without reading it.
 *
 * Rules every section follows: the screen must positively look like the
 * picker, exactly one profile must match the name, and the node clicked must
 * be the clickable element of that profile's own tile. Anything less is
 * NoSuchProfile or NotPicker, and nothing gets clicked.
 */
object ProfilePickers {

    /** A node on screen, reduced to what recognition needs. */
    interface Node {
        val text: String
        val desc: String
        /** e.g. "com.amazon.firebat:id/profile_name", or "" */
        val viewId: String
        val clickable: Boolean
        /** left, top, right, bottom */
        val bounds: List<Int>
        val parent: Node?
        val children: List<Node>
    }

    /**
     * The title sent from the phone, as TMDB names it. [isMovie]: a title
     * page's date is the release year for a film, but the latest season's for
     * a series (Prime showed 2026 for The Boys, TMDB says 2019), so only films
     * are year-checked on their page.
     */
    data class Wanted(val title: String, val year: Int?, val isMovie: Boolean = false)

    /** A title as a service shows it; year only if the screen shows one. */
    data class Shown(val name: String, val year: Int?)

    /** How sure a match is. Year-confirmed beats name-only. */
    enum class Match { EXACT_WITH_YEAR, CONTAINS_WITH_YEAR, EXACT }

    /**
     * Whether [shown] is the [wanted] title. Services name titles their own
     * way - seen on the real TV: Disney+ "Marvel Studios' Avengers: Endgame"
     * for TMDB's "Avengers: Endgame"; Prime "Road House (2024)" beside the
     * 1989 "Road House". So: words only (case, punctuation and apostrophes
     * ignored); a "(YYYY)" in the name counts as its year; years must agree
     * within one where both are known; and a longer name that merely contains
     * the title counts only with the year confirmed.
     */
    fun titleMatch(shown: Shown, wanted: Wanted): Match? {
        var name = shown.name
        var year = shown.year
        YEAR_SUFFIX.find(name)?.let {
            year = year ?: it.groupValues[1].toInt()
            name = name.removeRange(it.range)
        }
        val s = words(name)
        val w = words(wanted.title)
        if (w.isEmpty() || s.isEmpty()) return null
        val yearKnown = year != null && wanted.year != null
        if (yearKnown && Math.abs(year!! - wanted.year!!) > 1) return null
        return when {
            s == w -> if (yearKnown) Match.EXACT_WITH_YEAR else Match.EXACT
            yearKnown && " $s ".contains(" $w ") -> Match.CONTAINS_WITH_YEAR
            else -> null
        }
    }

    /**
     * The one tile to open: the best kind of match there is ([Match] order),
     * provided only one tile has it. On the real TV Disney+ listed "The
     * Mandalorian" (2019) beside "Disney Gallery / Star Wars: The Mandalorian"
     * (2020): the exact name wins. Two equally good candidates means we can't
     * tell them apart, so null - never a guess.
     */
    fun bestTile(candidates: List<Pair<Node, Shown>>, wanted: Wanted): Node? {
        val scored = candidates.mapNotNull { (n, s) -> titleMatch(s, wanted)?.let { n to it } }
        val best = scored.minOfOrNull { it.second.ordinal } ?: return null
        return scored.filter { it.second.ordinal == best }.singleOrNull()?.first
    }

    private val YEAR_SUFFIX = Regex("\\s*\\((\\d{4})\\)\\s*$")
    private val ANY_YEAR = Regex("\\b(19|20)\\d{2}\\b")

    /** First plausible year in a metadata line ("12+ 2019 • Super Heroes"). */
    fun yearIn(s: String): Int? = ANY_YEAR.find(s)?.value?.toInt()

    private fun words(s: String) = fold(s).replace(NOT_WORD, " ").trim().replace(SPACES, " ")

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    sealed class Outcome {
        /** Not the picker (yet). Keep waiting. */
        object NotPicker : Outcome()
        /** The picker is showing but that name is not on it, or is ambiguous. */
        object NoSuchProfile : Outcome()
        data class Found(val node: Node) : Outcome()
    }

    /**
     * Turns a stream of per-look outcomes into a decision. "Not on the picker"
     * must hold for [needed] looks in a row before giving up: on a real TV,
     * Disney+ drew its heading before its tiles, and a single look concluded
     * the profile was missing and gave up for good.
     */
    class Settle(private val needed: Int = 4) {
        private var misses = 0

        sealed class Decision {
            object KeepLooking : Decision()
            object GiveUp : Decision()
            data class Click(val node: Node) : Decision()
        }

        fun next(outcome: Outcome): Decision = when (outcome) {
            is Outcome.Found -> Decision.Click(outcome.node)
            Outcome.NotPicker -> { misses = 0; Decision.KeepLooking }
            Outcome.NoSuchProfile -> if (++misses >= needed) Decision.GiveUp else Decision.KeepLooking
        }
    }

    class Picker(
        val serviceId: String,
        val packages: Set<String>,
        /** The search page's text box, for services whose search link leaves it empty. */
        val searchBox: ((root: Node) -> Node?)? = null,
        /** On search results: the one tile for this title (see [bestTile]), or null. */
        val resultTile: ((root: Node, wanted: Wanted) -> Node?)? = null,
        /** On a title's page: the button that starts playback, or null. */
        val playButton: ((root: Node) -> Node?)? = null,
        /** On a title's page: its name (and year, if shown), to confirm it before Play. */
        val pageTitle: ((root: Node) -> Shown?)? = null,
        val recognise: (root: Node, name: String) -> Outcome
    )

    val ALL: List<Picker> = listOf(disneyPlus(), primeVideo())

    fun forService(serviceId: String): Picker? = ALL.firstOrNull { it.serviceId == serviceId }

    fun forPackage(pkg: String): Picker? = ALL.firstOrNull { pkg in it.packages }

    // ---- Disney+ ---------------------------------------------------------
    //
    // Jetpack Compose, so almost no view ids. The picker has the view id
    // profilesContent and a "Who's watching?" heading (profilesContent alone
    // also backs the Edit Profiles screen). Each profile is a clickable tile
    // whose single child carries the description "Access <name>'s profile",
    // with identical bounds. Disney always appends 's, even to names ending
    // in s ("Access Peters's profile"). English UI only: the heading and
    // description are localised text.

    //
    // Search: the link opens a page whose EditText has the view id
    // searchEditText ("Search by title, genre, team or league"). Exactly one
    // such box must be on screen.
    //
    // Autoplay: each result is a focusable shelfItemRootLayout holding a
    // `title` TextView (Disney's own name: "Marvel Studios' Avengers:
    // Endgame") and a `metadata` line with the year ("12+ 2019 • ..."). A
    // title's page names it in detailLogoImage's description, the year is in
    // detailPageMetadataRoot's, and its first button, detailPageMainButtonOne,
    // is PLAY (focused when the page opens).

    private fun disneyPlus() = Picker(
        serviceId = "disneyplus",
        packages = setOf("com.disney.disneyplus", "com.disney.disneyplus.androidtv"),
        searchBox = { root -> root.all { it.viewId.endsWith(":id/searchEditText") }.singleOrNull() },
        resultTile = { root, wanted ->
            val tiles = root.all { it.viewId.endsWith(":id/shelfItemRootLayout") }.mapNotNull { tile ->
                val name = tile.all { it.viewId.endsWith(":id/title") }.singleOrNull()?.text ?: return@mapNotNull null
                val year = tile.all { it.viewId.endsWith(":id/metadata") }.singleOrNull()?.let { yearIn(it.text) }
                tile to Shown(name, year)
            }
            bestTile(tiles, wanted)
        },
        playButton = { root -> root.all { it.viewId.endsWith(":id/detailPageMainButtonOne") }.singleOrNull() },
        pageTitle = { root ->
            root.all { it.viewId.endsWith(":id/detailLogoImage") }.singleOrNull()?.desc?.let { name ->
                val year = root.all { it.viewId.endsWith(":id/detailPageMetadataRoot") }.singleOrNull()?.let { yearIn(it.desc) }
                Shown(name, year)
            }
        }
    ) { root, name ->
        val onPicker = root.any { it.viewId.endsWith(":id/profilesContent") } &&
            root.any { it.text == "Who's watching?" }
        if (!onPicker) return@Picker Outcome.NotPicker

        val wanted = "access ${fold(name)}'s profile"
        val labels = root.all { fold(it.desc) == wanted }
        val tiles = labels.mapNotNull { label ->
            label.parent?.takeIf { it.clickable && it.bounds == label.bounds }
        }
        if (labels.size == 1 && tiles.size == 1) Outcome.Found(tiles[0]) else Outcome.NoSuchProfile
    }

    // ---- Prime Video -----------------------------------------------------
    //
    // Classic Android views with stable ids. The picker is marked by
    // whos_watching_heading; each profile's name is a clickable profile_name
    // TextView. The "New" tile (add a profile) is also a profile_name, but
    // its tile holds a profile_add_icon, so it is excluded: someone whose
    // profile is literally called "New" must not create a profile instead.

    //
    // Autoplay: search results are standard_container_card_tile nodes whose
    // description is the title, optionally "(YYYY)" when names clash, then
    // optionally ", <BADGE>" ("Road House (2024), MOST LIKED"). Rent/buy tiles
    // read "<title>, Free trial or buy" and must not match. A title's page has
    // header_title_logo (same naming) or header_title_text, the year in
    // vod_original_air_date, and watch_now_button, for films and series
    // alike, focused when the page opens. Prime lists some films twice: the
    // plain "Road House" tile opened the 2024 film, same as "Road House
    // (2024)" - which is why a film's page year is checked before Play.

    private fun primeVideo() = Picker(
        serviceId = "primevideo",
        packages = setOf("com.amazon.firebat", "com.amazon.avod", "com.amazon.avod.thirdpartyclient"),
        resultTile = { root, wanted ->
            val tiles = root.all { it.viewId.endsWith(":id/standard_container_card_tile") }
                .map { it to Shown(withoutBadge(it.desc), null) }
            bestTile(tiles, wanted)
        },
        playButton = { root -> root.all { it.viewId.endsWith(":id/watch_now_button") }.singleOrNull() },
        pageTitle = { root ->
            // A logo when Prime has one ("Road House (2024)"), else plain text
            // (the 1989-titled page showed header_title_text "Road House").
            val name = root.all { it.viewId.endsWith(":id/header_title_logo") }.singleOrNull()?.desc?.takeIf { it.isNotBlank() }
                ?: root.all { it.viewId.endsWith(":id/header_title_text") }.singleOrNull()?.text?.takeIf { it.isNotBlank() }
            val year = root.all { it.viewId.endsWith(":id/vod_original_air_date") }.singleOrNull()?.let { yearIn(it.text) }
            name?.let { Shown(withoutBadge(it), year) }
        }
    ) { root, name ->
        if (!root.any { it.viewId.endsWith(":id/whos_watching_heading") }) return@Picker Outcome.NotPicker

        val matches = root.all {
            it.viewId.endsWith(":id/profile_name") && it.clickable && fold(it.text) == fold(name)
        }.filterNot { label ->
            label.parent?.any { it.viewId.endsWith(":id/profile_add_icon") } ?: true
        }
        if (matches.size == 1) Outcome.Found(matches[0]) else Outcome.NoSuchProfile
    }

    // ---- HBO Max (blind) -------------------------------------------------
    //
    // HBO Max exposes nothing to accessibility, so nothing here reads its
    // screen; BlindPlay drives it with key presses and checks the result
    // afterwards. Observed on the real TV (September 2026):
    //  - https://play.max.com/search opens the search page with an on-screen
    //    keyboard, and typed key events land in its box. A cold start took
    //    ~14 s to get there, an app already running ~3 s.
    //  - After typing, the highlight rests on the key of the last character
    //    typed. Right from the keyboard's last column enters the first result.
    //  - OK on a result opens its page with Watch / Continue highlighted, and
    //    OK there plays.
    //  - While playing, the media session names it: "When You're Lost in the
    //    Darkness, The Last of Us" (episode, then show).

    /**
     * HBO Max's and Netflix's on-screen keyboards, which are laid out the same
     * (both checked on the real TV): six keys a row, a-f / g-l / m-r / s-x /
     * y z 1 2 3 4 / 5 6 7 8 9 0, with the results to the right of it.
     */
    object SearchKeyboard {
        private const val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890"
        private const val COLUMNS = 6

        /**
         * What to type for [title]: only what the keyboard has. Accents are
         * dropped, apostrophes closed up, anything else becomes a space:
         * "Grey's Anatomy" -> "greys anatomy", "Pokémon" -> "pokemon".
         */
        fun searchText(title: String): String =
            java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("['’‘`]"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .take(60)
                .trim()

        /** Right presses from the key last typed to the first result; null if it isn't on the keyboard. */
        fun rightsToFirstResult(typed: String): Int? {
            val i = KEYS.indexOf(typed.lastOrNull() ?: return null)
            return if (i < 0) null else COLUMNS - i % COLUMNS
        }
    }

    object Hbo {
        const val SERVICE_ID = "hbomax"
        const val SEARCH_LINK = "https://play.max.com/search"
    }

    // ---- Netflix (blind, and it never says what it plays) ------------------
    //
    // Netflix shows nothing to accessibility and blocks screenshots, so this
    // was mapped by pressing keys with Félix watching the TV (September 2026):
    //  - a cold start always lands on "Who's watching?", a list top to bottom.
    //    Up stops at the top (no wrap), so Up x5 then Down x(place-1) reaches
    //    any profile without reading a single name.
    //  - from the home screen, Left then Up x8 reaches the top menu bar on
    //    "Home"; Left again is Search; OK opens it.
    //  - typed keys land in the search box, but only slowly (see KeyServer).
    //  - the keyboard is [SearchKeyboard]; Right from its last column enters
    //    the first result. OK opens the title's page with Play highlighted.
    //  - its media session reports state=3 for the trailers on its own menus
    //    and never names anything, so nothing here can be verified. The phone
    //    is told exactly that.

    object Netflix {
        const val SERVICE_ID = "netflix"
        /** More than the profiles anyone has: Up stops at the top of the list. */
        const val UPS_TO_FIRST_PROFILE = 5
        const val MAX_PLACE = 5

        /** Down presses from the top of the profile list to [place] (1-based), or null if out of range. */
        fun downsToProfile(place: Int): Int? = if (place in 1..MAX_PLACE) place - 1 else null

        /** "3" -> 3, for the place stored per phone. */
        fun place(stored: String?): Int? = stored?.trim()?.toIntOrNull()?.takeIf { it in 1..MAX_PLACE }
    }

    /**
     * Whether a media session's description names [title]. HBO gives
     * "episode, show" for a series, so the title's words, in order, anywhere
     * will do. A film's description is its name alone ("Dune"), so a film must
     * be exactly that: otherwise "Dune: Part Two" would pass for "Dune".
     * Two films with the very same name still can't be told apart here.
     */
    fun namesTitle(description: String, title: String, isMovie: Boolean = false): Boolean {
        val d = words(description)
        val w = words(title)
        if (w.isEmpty()) return false
        return if (isMovie) d == w else " $d ".contains(" $w ")
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Prime's tile text minus a trailing ", <BADGE>". Badges are capitals
     * ("MOST LIKED", "SEASON FINALE"); anything else after the last comma is
     * part of the name ("Love, Death & Robots") or a rent/buy note ("Free
     * trial or buy"), which is left on so it doesn't match.
     */
    fun withoutBadge(desc: String): String {
        val i = desc.lastIndexOf(',')
        if (i <= 0) return desc
        val rest = desc.substring(i + 1).replace(SPACES, " ").trim()
        return if (rest.any { it.isLetter() } && rest == rest.uppercase(Locale.ROOT)) desc.substring(0, i) else desc
    }

    /**
     * Names are typed on a phone, so case and stray spaces are forgiven.
     * Unicode spaces are named explicitly: Prime's tiles read "The Boys,<NBSP>
     * MOST LIKED", and `\s` matches NBSP on Android's regex engine but not the
     * JVM's, so behaviour differed between the TV and the tests.
     */
    private fun fold(s: String) = s.replace(SPACES, " ").trim().lowercase(Locale.ROOT)

    private val SPACES = Regex("[\\s\\u00A0\\u2007\\u202F\\u2009\\u200A\\u3000]+")

    private fun Node.all(pred: (Node) -> Boolean): List<Node> {
        val out = mutableListOf<Node>()
        fun walk(n: Node) {
            if (pred(n)) out += n
            n.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    private fun Node.any(pred: (Node) -> Boolean): Boolean = all(pred).isNotEmpty()
}
