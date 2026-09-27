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
        /** Has the TV's highlight (input focus). */
        val focused: Boolean get() = false
        /** Marked as the chosen one of a list (Prime's open season list, Disney+'s season shown). */
        val selected: Boolean get() = false
    }

    /**
     * The title sent from the phone, as TMDB names it. [isMovie]: a title
     * page's date is the release year for a film, but the latest season's for
     * a series (Prime showed 2026 for The Boys, TMDB says 2019), so only films
     * are year-checked on their page. [episode]: one episode of a series to
     * start instead of the page's own Continue / Resume.
     */
    data class Wanted(
        val title: String,
        val year: Int?,
        val isMovie: Boolean = false,
        val episode: Episode? = null
    )

    /** An episode as TMDB numbers and names it. */
    data class Episode(val season: Int, val number: Int, val name: String?) {
        /** "Season 2, Episode 3", for what the phone is told. */
        val label get() = "Season $season, Episode $number"
    }

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

    /** Any Disney+ profile tile's label, whoever it belongs to. */
    private val ANY_PROFILE = Regex("^access .+'s profile$")

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
        /** On a title's page: what its Play button says it will start ("Resume Episode 8"), if it says. */
        val playLabel: ((root: Node) -> String?)? = null,
        /**
         * A title's page, told apart from search results without its Play
         * button (which can be missing, see [noResume]). Prime's results show
         * a preview header too, so the name alone doesn't prove the page.
         */
        val onTitlePage: ((root: Node) -> Boolean)? = null,
        /**
         * The page offers no way to continue - only to start over. Seen on
         * Prime Video: with the current episode counted as watched, its page
         * had "Watch from beginning" and no Resume / Watch button at all.
         */
        val noResume: ((root: Node) -> Boolean)? = null,
        /** On a series' page: its seasons and episodes, to start one chosen episode. */
        val episodes: EpisodeList? = null,
        val recognise: (root: Node, name: String) -> Outcome
    )

    /** A season or an episode as a series' page lists it. */
    data class Listed(
        val node: Node,
        val season: Int,
        /** Null for a season entry. */
        val episode: Int? = null,
        /** Where the highlight is, as far as this list shows it. */
        val highlighted: Boolean = false,
        /** The service's own name for the episode, where its label gives one. */
        val name: String? = null
    )

    /**
     * How a service's series page lists seasons and episodes. Everything the
     * helper presses is decided from these, re-read after every press.
     */
    class EpisodeList(
        /** The season whose episodes are listed now, or null if the screen doesn't say (yet). */
        val seasonShown: (root: Node) -> Int?,
        /**
         * Prime Video: the drop-down that lists the seasons once OK is pressed
         * on it. Null where the seasons are listed on the page (Disney+).
         */
        val seasonOpener: ((root: Node) -> Node?)? = null,
        /**
         * Prime Video: its Episodes tab, and whether it is the tab showing.
         * The page has others (Explore, Related, Extras) whose rows hold no
         * episodes. Null where there is no such tab, or it isn't on screen.
         */
        val episodesTab: ((root: Node) -> Pair<Node, Boolean>?)? = null,
        /** The seasons on offer, with which one has the highlight. */
        val seasons: (root: Node) -> List<Listed>,
        /** The episodes on screen, with which one has the highlight. */
        val cards: (root: Node) -> List<Listed>,
        /** The next episode is to the Right (Prime Video's row) rather than Down (Disney+'s list). */
        val horizontal: Boolean,
        /**
         * Disney+: the seasons are a column beside the episodes, so from a
         * season the way into the episodes is this arrow. Steering there by
         * position went Down the season column instead (seen on the TV), which
         * shows another season. Null: steer by position.
         */
        val intoEpisodesFromSeasons: Arrow? = null,
        /**
         * Whether the media session names the episode (Prime: "BEWARE THE
         * JABBERWOCK, MY SON"), so the one playing can be checked against the
         * card that was pressed. Disney+ names only the show.
         */
        val sessionNamesEpisode: Boolean
    )

    /** An arrow key, as far as recognition is concerned. */
    enum class Arrow { UP, DOWN, LEFT, RIGHT }

    /**
     * The arrow that moves a highlight from item [from] to item [to] of a
     * list, by their places in it. Null once there. Places, not season or
     * episode numbers: that is how the highlight moves.
     */
    fun arrowToward(from: Int, to: Int, horizontal: Boolean): Arrow? = when {
        to > from -> if (horizontal) Arrow.RIGHT else Arrow.DOWN
        to < from -> if (horizontal) Arrow.LEFT else Arrow.UP
        else -> null
    }

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
    // is PLAY (focused when the page opens). For a series it reads CONTINUE,
    // RESTART beside it, and resumes the episode named in
    // detailDescriptionTitleTextView ("S1:E1 Chapter 1: The Mandalorian").
    //
    // Episodes: below the buttons, detailSeasonsRecyclerview lists the seasons
    // (no view id; description "Season 2, 8 Episodes"; the one shown is
    // `selected`) and detailSeasonEpisodesRecyclerview the episodes (`root`,
    // "Season 2 Episode 3 Chapter 11: The Heiress., 41 minutes long., ...").
    // Seen on the TV: moving the highlight onto a season shows its episodes
    // (no OK needed), and OK on an episode plays it. The media session then
    // names only the show ("The Mandalorian"), so the episode is confirmed on
    // screen before OK, not afterwards.

    private val DISNEY_SEASON = Regex("^Season (\\d+), \\d+ Episodes?$")
    private val DISNEY_EPISODE = Regex("^Season (\\d+) Episode (\\d+)\\b")
    private val DISNEY_EPISODE_LINE = Regex("^S\\d+:E\\d+\\b")

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
        },
        playLabel = { root ->
            // "CONTINUE" + "S1:E1 Chapter 1: The Mandalorian"; a film has no episode line.
            val button = root.all { it.viewId.endsWith(":id/detailPageMainButtonOne") }.singleOrNull()
                ?.all { it.text.isNotBlank() }?.firstOrNull()?.text?.let(::sentenceCase)
            val episode = root.all { it.viewId.endsWith(":id/detailDescriptionTitleTextView") }.singleOrNull()?.text
            listOfNotNull(button, episode?.takeIf { DISNEY_EPISODE_LINE.containsMatchIn(it) }).joinToString(" ").ifBlank { null }
        },
        episodes = EpisodeList(
            // The episodes listed say which season they are. `selected` alone
            // won't do: seen on the TV, while a season has the highlight none
            // is marked selected, and the helper waited on a season it had
            // already switched to.
            seasonShown = { root ->
                disneyCards(root).map { it.season }.distinct().singleOrNull()
                    ?: disneySeasons(root).filter { it.node.selected }.singleOrNull()?.season
            },
            seasons = ::disneySeasons,
            cards = ::disneyCards,
            horizontal = false,
            intoEpisodesFromSeasons = Arrow.RIGHT,
            sessionNamesEpisode = false
        )
    ) { root, name ->
        val onPicker = root.any { it.viewId.endsWith(":id/profilesContent") } &&
            root.any { it.text == "Who's watching?" }
        if (!onPicker) return@Picker Outcome.NotPicker

        // The picker's frame appears before the profiles in it. Seen on the
        // real TV: with no tiles yet, "your profile isn't here" was reported
        // about a screen that was still drawing. An empty picker is therefore
        // not yet the picker.
        val anyProfile = root.any { ANY_PROFILE.matches(plain(it.desc)) }
        if (!anyProfile) return@Picker Outcome.NotPicker

        val wanted = "access ${plain(name)}'s profile"
        val labels = root.all { plain(it.desc) == wanted }
        val tiles = labels.mapNotNull { label ->
            label.parent?.takeIf { it.clickable && it.bounds == label.bounds }
        }
        if (labels.size == 1 && tiles.size == 1) Outcome.Found(tiles[0]) else Outcome.NoSuchProfile
    }

    private fun disneyCards(root: Node): List<Listed> =
        root.all { it.viewId.endsWith(":id/detailSeasonEpisodesRecyclerview") }.flatMap { list ->
            list.all { it.viewId.endsWith(":id/root") }.mapNotNull { card ->
                DISNEY_EPISODE.find(card.desc)?.let {
                    Listed(card, it.groupValues[1].toInt(), it.groupValues[2].toInt(), card.focused)
                }
            }
        }

    private fun disneySeasons(root: Node): List<Listed> =
        root.all { it.viewId.endsWith(":id/detailSeasonsRecyclerview") }.flatMap { list ->
            list.children.mapNotNull { item ->
                DISNEY_SEASON.find(item.desc)?.let { Listed(item, it.groupValues[1].toInt(), highlighted = item.focused) }
            }
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
    // On a series watch_now_button reads "Resume\nEpisode 8" (or "Episode 8
    // Watch now"): it continues where you left off.
    //
    // Episodes, seen on the TV (The Boys, September 2026): below the buttons
    // are tabs, then season_drop_down showing "Season 5" (in
    // season_spinner_collapsed_item), then a row of standard_container_card_tile
    // named "The Boys, Season 5, Episode 8 - Blood and Bone". The row keeps
    // the highlighted tile in its second slot and scrolls. OK on the drop-down
    // opens a separate little window of season_spinner_expanded_item, where
    // the highlight is shown as `selected`, Up/Down move it, and OK picks it.
    // OK on an episode tile plays it, and the media session then names it by
    // the tile's own name ("BEWARE THE JABBERWOCK, MY SON").

    private val PRIME_EPISODE = Regex("(?:^|, )Season (\\d+), Episode (\\d+)(?: - (.+))?$")
    private val SEASON_TEXT = Regex("^Season (\\d+)$")
    /** "Episodes, Tab, Selected, 1 of 4" / "Episodes, Tab, 1 of 4". English UI only. */
    private val PRIME_EPISODES_TAB = Regex("^Episodes, Tab(, Selected)?, \\d+ of \\d+$")

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
        },
        playLabel = { root ->
            // "Resume\nEpisode 8, Button, 1 of 7" -> "Resume Episode 8"
            root.all { it.viewId.endsWith(":id/watch_now_button") }.singleOrNull()?.desc
                ?.substringBefore(", Button")?.replace(SPACES, " ")?.trim()?.ifBlank { null }
        },
        onTitlePage = { root -> root.any { it.viewId.endsWith(":id/detail_page_layout") } },
        noResume = { root ->
            root.any { it.viewId.endsWith(":id/detail_page_layout") } &&
                root.none { it.viewId.endsWith(":id/watch_now_button") } &&
                root.any { it.viewId.endsWith(":id/fable_button_text") && it.text.replace(SPACES, " ").startsWith("Watch from beginning") }
        },
        episodes = EpisodeList(
            seasonShown = { root ->
                // The drop-down says, once it has scrolled into view. Before
                // that, the page already shows a few of that season's tiles.
                root.all { it.viewId.endsWith(":id/season_spinner_collapsed_item") }.singleOrNull()
                    ?.let { seasonIn(it) }
                    ?: primeCards(root).map { it.season }.distinct().singleOrNull()
            },
            seasonOpener = { root -> root.all { it.viewId.endsWith(":id/season_drop_down") }.singleOrNull() },
            episodesTab = { root ->
                // No-break spaces after its commas, as in the tiles.
                fun label(n: Node) = n.desc.replace(SPACES, " ").trim()
                root.all { PRIME_EPISODES_TAB.matches(label(it)) }.singleOrNull()?.let { it to label(it).contains(", Selected,") }
            },
            seasons = { root ->
                root.all { it.viewId.endsWith(":id/season_spinner_expanded_item") }.mapNotNull { item ->
                    seasonIn(item)?.let { Listed(item, it, highlighted = item.selected) }
                }
            },
            cards = ::primeCards,
            horizontal = true,
            sessionNamesEpisode = true
        )
    ) { root, name ->
        if (!root.any { it.viewId.endsWith(":id/whos_watching_heading") }) return@Picker Outcome.NotPicker

        val matches = root.all {
            it.viewId.endsWith(":id/profile_name") && it.clickable && plain(it.text) == plain(name)
        }.filterNot { label ->
            label.parent?.any { it.viewId.endsWith(":id/profile_add_icon") } ?: true
        }
        if (matches.size == 1) Outcome.Found(matches[0]) else Outcome.NoSuchProfile
    }

    private fun primeCards(root: Node): List<Listed> =
        root.all { it.viewId.endsWith(":id/standard_container_card_tile") }.mapNotNull { tile ->
            // Prime puts no-break spaces in its labels (see [fold]).
            PRIME_EPISODE.find(tile.desc.replace(SPACES, " ").trim())?.let {
                Listed(tile, it.groupValues[1].toInt(), it.groupValues[2].toInt(), tile.focused, it.groupValues[3].ifBlank { null })
            }
        }

    /** "Season 4" in any text below [n]. */
    private fun seasonIn(n: Node): Int? =
        n.all { SEASON_TEXT.matches(it.text.replace(SPACES, " ").trim()) }.singleOrNull()
            ?.let { SEASON_TEXT.find(it.text.replace(SPACES, " ").trim())!!.groupValues[1].toInt() }

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
    //  - A series' page (The Last of Us, True Detective, Chernobyl): Down goes
    //    to the Episodes tab; Down again to a row of season numbers - only
    //    when there is more than one season - which opens on the season last
    //    watched (True Detective: 4). Moving onto a season shows its episodes;
    //    Left stops at the first season. Down from there (or from the tab,
    //    with one season) enters the episode row on its first episode; Left
    //    stops at the first episode, and never reaches the side menu. OK on an
    //    episode plays it.

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
        /** Enough Lefts to reach the first of anything HBO Max lists; Left stops there. */
        const val MAX_LEFTS = 30

        /**
         * The presses from a series' page (its Watch / Resume button
         * highlighted) to [episode], ready for OK. Blind, so it never counts
         * from where the highlight happens to be: it runs Left to the start of
         * each row first. [seasons] and [episodesInSeason] are TMDB's counts,
         * which only size those runs and decide whether there is a season row;
         * if HBO Max disagrees, the wrong thing is caught afterwards by name.
         * Null if the episode can't be reached this way.
         */
        fun episodeRoute(episode: Episode, seasons: Int, episodesInSeason: Int): List<Arrow>? {
            if (episode.season !in 1..seasons || episode.number < 1) return null
            val keys = mutableListOf(Arrow.DOWN) // the Episodes tab
            if (seasons > 1) {
                keys += Arrow.DOWN // the season row
                repeat(minOf(seasons, MAX_LEFTS)) { keys += Arrow.LEFT }
                repeat(episode.season - 1) { keys += Arrow.RIGHT }
            }
            keys += Arrow.DOWN // the episode row
            repeat(minOf(maxOf(episodesInSeason, episode.number), MAX_LEFTS)) { keys += Arrow.LEFT }
            repeat(episode.number - 1) { keys += Arrow.RIGHT }
            return keys
        }
    }

    /**
     * Whether a media session's description ("The Path, The Last of Us")
     * names episode [episodeName] of [show]: exactly that episode's name,
     * then the show's (words only). Exact, so "Night Country: Part 1" is not
     * taken for "Night Country: Part 10".
     */
    fun namesEpisode(description: String, show: String, episodeName: String?): Boolean {
        val e = words(episodeName ?: return false)
        val s = words(show)
        if (e.isEmpty() || s.isEmpty()) return false
        return words(description) == "$e $s"
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

    /** "CONTINUE" -> "Continue", for a button label passed on to the phone. */
    private fun sentenceCase(s: String) = s.trim().lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

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
    private fun fold(s: String) =
        java.text.Normalizer.normalize(s.replace(SPACES, " ").trim(), java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * A name as loosely as it can honestly be compared: accents dropped and
     * every apostrophe the same. A phone keyboard and a TV app can write the
     * same name in two ways - Disney+ labels its tiles "Access Félix's
     * profile" with a one-character é, while a phone may send é as "e" plus a
     * separate accent, and the two are not equal. That cost a live run: the
     * picker was up, the profile was on it, and nothing matched.
     */
    private fun plain(s: String) =
        java.text.Normalizer.normalize(fold(s), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("['’‘`]"), "'")

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

    private fun Node.none(pred: (Node) -> Boolean): Boolean = !any(pred)
}
