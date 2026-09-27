package com.felix.streamhub

import com.felix.streamhub.picker.ProfilePickers
import com.felix.streamhub.picker.ProfilePickers.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs recognition against real `uiautomator dump`s from a Fire TV (Fire OS
 * 7.7.1.4, September 2026), with the household's profile names replaced.
 * When a service redesigns its picker, add the new dump here next to the old.
 */
class ProfilePickersTest {

    private val disney = ProfilePickers.forService("disneyplus")!!
    private val prime = ProfilePickers.forService("primevideo")!!

    // ---- HBO Max (blind) -------------------------------------------------

    @Test
    fun hboIsTypedOnlyWhatItsKeyboardHas() {
        val hbo = ProfilePickers.SearchKeyboard
        assertEquals("house of the dragon", hbo.searchText("House of the Dragon"))
        assertEquals("greys anatomy", hbo.searchText("Grey’s Anatomy"))
        assertEquals("pokemon the movie", hbo.searchText("Pokémon: The Movie!"))
        assertEquals("dune part two", hbo.searchText("  Dune: Part Two "))
        assertEquals("", hbo.searchText("进击的巨人"))
    }

    @Test
    fun hboRightPressesFollowTheLastKeyTyped() {
        // Seen on the TV: after "house of the dragon" the highlight sat on "n"
        // (2nd column) and 5 Rights reached the first result; after "the last
        // of us" it sat on "s" (1st column), 6 Rights.
        assertEquals(5, ProfilePickers.SearchKeyboard.rightsToFirstResult("house of the dragon"))
        assertEquals(6, ProfilePickers.SearchKeyboard.rightsToFirstResult("the last of us"))
        assertEquals(4, ProfilePickers.SearchKeyboard.rightsToFirstResult("1917")) // "7": 3rd key of "5 6 7 8 9 0"
        assertEquals(1, ProfilePickers.SearchKeyboard.rightsToFirstResult("catch 22 4")) // "4" ends a row
        assertEquals(null, ProfilePickers.SearchKeyboard.rightsToFirstResult(""))
    }

    @Test
    fun whatIsPlayingMustNameTheTitle() {
        assertTrue(ProfilePickers.namesTitle("When You're Lost in the Darkness, The Last of Us", "The Last of Us"))
        assertTrue(ProfilePickers.namesTitle("Road House (2024)", "Road House"))
        assertTrue(!ProfilePickers.namesTitle("Making of: The Last of Us Part", "The Last of Us Podcast"))
        assertTrue(!ProfilePickers.namesTitle("Houseboat", "House"))
        assertTrue(!ProfilePickers.namesTitle("", "Dune"))
        // Films: HBO names a film alone, so a longer name is another film.
        assertTrue(ProfilePickers.namesTitle("Dune", "Dune", isMovie = true))
        assertTrue(!ProfilePickers.namesTitle("Dune: Part Two", "Dune", isMovie = true))
    }

    // ---- Netflix (blind, by place in the list) ---------------------------

    @Test
    fun netflixCountsDownToTheProfileWhosePlaceIsKnown() {
        val n = ProfilePickers.Netflix
        assertEquals(0, n.downsToProfile(1))
        assertEquals(3, n.downsToProfile(4))
        assertEquals(null, n.downsToProfile(0))
        assertEquals(null, n.downsToProfile(6))
        // Up must be pressed more often than anyone has profiles, because it
        // stops at the top of the list instead of wrapping round.
        assertTrue(n.UPS_TO_FIRST_PROFILE >= n.MAX_PLACE)
    }

    @Test
    fun netflixIgnoresAPlaceThatIsNotOne() {
        val n = ProfilePickers.Netflix
        assertEquals(4, n.place(" 4 "))
        assertEquals(null, n.place(""))
        assertEquals(null, n.place(null))
        assertEquals(null, n.place("Félix")) // its names can't be read, so a name is no use
        assertEquals(null, n.place("9"))
    }

    // ---- Disney+ ---------------------------------------------------------

    @Test
    fun disneyClicksTheTileOfTheNamedProfile() {
        val found = disney.recognise(dump("disney-picker.xml"), "Bob") as Outcome.Found
        assertTrue("must click the clickable tile, not its label", found.node.clickable)
        assertEquals("Access Bob's profile", found.node.children.first().desc)
    }

    @Test
    fun disneyMatchesAnAccentedNameHoweverItIsWritten() {
        // A live failure: Disney+ labels its tile with a one-character "é",
        // the phone can send "e" + a separate accent, and the two are not
        // equal - so the picker was up, the profile was on it, and nothing
        // matched. Both spellings, and the plain "Amelie", must find it.
        val r = dump("disney-picker-2026-09.xml")
        val precomposed = "Amélie"
        val decomposed = "Amélie"
        for (name in listOf(precomposed, decomposed, "Amelie", "amelie")) {
            val found = disney.recognise(r, name) as Outcome.Found
            assertTrue("clicks the tile for $name", found.node.clickable)
        }
        assertEquals(Outcome.NoSuchProfile, disney.recognise(r, "Amel"))
    }

    @Test
    fun disneysPickerTilesCarryNoViewIds() {
        // Only the labels identify a profile; the tiles themselves have no
        // view id. "Add Profile" is a tile too and must never be clicked.
        val r = dump("disney-picker-2026-09.xml")
        assertEquals(Outcome.NoSuchProfile, disney.recognise(r, "Add Profile"))
        assertEquals(Outcome.NotPicker, prime.recognise(r, "Bob"))
    }

    @Test
    fun disneyForgivesCaseAndSpacesFromAPhoneKeyboard() {
        assertTrue(disney.recognise(dump("disney-picker.xml"), "  alice ") is Outcome.Found)
    }

    @Test
    fun disneyHandlesItsPossessiveOnNamesEndingInS() {
        assertTrue(disney.recognise(dump("disney-picker.xml"), "Dennis") is Outcome.Found)
    }

    @Test
    fun disneyNeverGuessesAnUnknownName() {
        assertEquals(Outcome.NoSuchProfile, disney.recognise(dump("disney-picker.xml"), "Zoe"))
        assertEquals(Outcome.NoSuchProfile, disney.recognise(dump("disney-picker.xml"), "Ali"))
    }

    @Test
    fun disneyDoesNothingOffThePicker() {
        assertEquals(Outcome.NotPicker, disney.recognise(dump("prime-picker.xml"), "Bob"))
        assertEquals(Outcome.NotPicker, disney.recognise(dump("hbo-home.xml"), "Bob"))
    }

    // ---- Prime Video -----------------------------------------------------

    @Test
    fun primeClicksTheNamedProfile() {
        val found = prime.recognise(dump("prime-picker.xml"), "Alice Anne Smith") as Outcome.Found
        assertEquals("Alice Anne Smith", found.node.text)
        assertTrue(found.node.clickable)
    }

    @Test
    fun primeNeverTreatsTheAddProfileTileAsAProfile() {
        assertEquals(Outcome.NoSuchProfile, prime.recognise(dump("prime-picker.xml"), "New"))
    }

    @Test
    fun primeNeverGuessesAnUnknownName() {
        assertEquals(Outcome.NoSuchProfile, prime.recognise(dump("prime-picker.xml"), "Alice"))
    }

    @Test
    fun primeDoesNothingOffThePicker() {
        assertEquals(Outcome.NotPicker, prime.recognise(dump("disney-picker.xml"), "Bob"))
    }

    // ---- Disney+ search box -------------------------------------------------

    @Test
    fun disneyFindsItsSearchBoxOnTheRealSearchPage() {
        val box = disney.searchBox!!(dump("disney-search.xml"))!!
        assertTrue(box.viewId.endsWith(":id/searchEditText"))
    }

    @Test
    fun disneyFindsNoSearchBoxOnThePicker() {
        assertEquals(null, disney.searchBox!!(dump("disney-picker.xml")))
    }

    @Test
    fun primeNeedsNoTyping() {
        assertEquals(null, prime.searchBox)
    }

    // ---- Prime Video autoplay (real screens) ----------------------------------

    private fun w(title: String, year: Int? = null) = ProfilePickers.Wanted(title, year)

    @Test
    fun primeFindsTheSentTitleInItsResults() {
        val tile = prime.resultTile!!(dump("prime-results.xml"), w("The Boys", 2019))!!
        assertTrue(tile.desc.startsWith("The Boys,"))
    }

    @Test
    fun primeNeverOpensAMerelySimilarTitle() {
        // "The Boys Presents: Diabolical" and "Prime Rewind: Inside The Boys" are right there.
        assertEquals(null, prime.resultTile!!(dump("prime-results.xml"), w("The")))
        assertEquals(null, prime.resultTile!!(dump("prime-results.xml"), w("Boys")))
        assertEquals(null, prime.resultTile!!(dump("prime-results.xml"), w("The Boys Presents")))
    }

    @Test
    fun primeTellsSameNamedFilmsApartByYear() {
        // As on the TV: "Road House (2024), MOST LIKED", "Road House" (1989),
        // "Road House (with ASL/ ...)", "Road House, Free trial or buy".
        val r = dump("prime-results-roadhouse.xml")
        assertEquals("Road House (2024)", ProfilePickers.withoutBadge(prime.resultTile!!(r, w("Road House", 2024))!!.desc))
        assertEquals("Road House", prime.resultTile!!(r, w("Road House", 1989))!!.desc)
    }

    @Test
    fun primeFindsWatchNowOnlyOnATitlesPage() {
        assertTrue(prime.playButton!!(dump("prime-title.xml"))!!.viewId.endsWith(":id/watch_now_button"))
        assertTrue(prime.playButton!!(dump("prime-title-roadhouse.xml"))!!.viewId.endsWith(":id/watch_now_button"))
        assertEquals(null, prime.playButton!!(dump("prime-results.xml")))
    }

    @Test
    fun primesPageTitleConfirmsTheRightFilm() {
        assertEquals("The Boys", prime.pageTitle!!(dump("prime-title.xml"))!!.name)
        val page = prime.pageTitle!!(dump("prime-title-roadhouse.xml"))!!
        assertEquals(ProfilePickers.Shown("Road House (2024)", 2024), page)
        assertTrue(ProfilePickers.titleMatch(page, w("Road House", 2024)) != null)
        assertEquals(null, ProfilePickers.titleMatch(page, w("Road House", 1989)))
    }

    @Test
    fun aPlainlyNamedTileThatOpensTheOtherFilmIsCaughtByItsYear() {
        // On the TV, the plain "Road House" tile (picked for the 1989 film)
        // opened the 2024 film: name "Road House", no logo, released 2024.
        val page = prime.pageTitle!!(dump("prime-title-roadhouse-plain.xml"))!!
        assertEquals(ProfilePickers.Shown("Road House", 2024), page)
        assertEquals(null, ProfilePickers.titleMatch(page, ProfilePickers.Wanted("Road House", 1989, isMovie = true)))
        assertTrue(ProfilePickers.titleMatch(page, ProfilePickers.Wanted("Road House", 2024, isMovie = true)) != null)
    }

    // ---- Disney+ autoplay (real screens) --------------------------------------

    @Test
    fun disneyFindsTheSentTitleInItsResults() {
        val tile = disney.resultTile!!(dump("disney-results.xml"), w("Moana", 2016))!!
        assertTrue(tile.viewId.endsWith(":id/shelfItemRootLayout"))
        assertTrue(tile.desc.startsWith("Moana, Rated"))
    }

    @Test
    fun disneyNeverOpensTheSequelForTheOriginal() {
        // "Moana 2" is the next tile over.
        assertTrue(disney.resultTile!!(dump("disney-results.xml"), w("Moana 2", 2024))!!.desc.startsWith("Moana 2,"))
        assertTrue(disney.resultTile!!(dump("disney-results.xml"), w("Moana", 2016))!!.desc.startsWith("Moana, "))
        assertEquals(null, disney.resultTile!!(dump("disney-results.xml"), w("Moan")))
    }

    @Test
    fun disneysLongerNameIsAcceptedWhenTheYearAgrees() {
        // Seen on the TV: TMDB's "Avengers: Endgame" is Disney's "Marvel Studios' Avengers: Endgame".
        val r = dump("disney-results-avengers.xml")
        assertTrue(disney.resultTile!!(r, w("Avengers: Endgame", 2019))!!.desc.startsWith("Marvel Studios' Avengers: Endgame"))
        assertEquals(null, disney.resultTile!!(r, w("Avengers: Endgame", 2012)))
        assertEquals(null, disney.resultTile!!(r, w("Avengers: Endgame", null)))
    }

    @Test
    fun anExactNameBeatsALongerOneThatContainsIt() {
        // Seen on the TV: "The Mandalorian" (2019) beside "Disney Gallery /
        // Star Wars: The Mandalorian" (2020) - both within a year of 2019.
        // Treating them as equally sure left autoplay stuck on the results.
        val r = dump("disney-results-mandalorian.xml")
        assertTrue(disney.resultTile!!(r, w("The Mandalorian", 2019))!!.desc.startsWith("The Mandalorian, "))
        assertTrue(disney.resultTile!!(r, w("The Mandalorian", null))!!.desc.startsWith("The Mandalorian, "))
    }

    @Test
    fun disneyFindsPlayAndTheTitleOnItsPage() {
        assertTrue(disney.playButton!!(dump("disney-title.xml"))!!.viewId.endsWith(":id/detailPageMainButtonOne"))
        val page = disney.pageTitle!!(dump("disney-title.xml"))!!
        assertEquals(ProfilePickers.Shown("Moana", 2016), page)
        assertEquals(null, disney.playButton!!(dump("disney-results.xml")))
    }

    // ---- the matching rules themselves -----------------------------------------

    @Test
    fun titlesWithCommasAndBadges() {
        assertEquals("Love, Death & Robots", ProfilePickers.withoutBadge("Love, Death & Robots, NEW SEASON"))
        assertEquals("Love, Death & Robots", ProfilePickers.withoutBadge("Love, Death & Robots"))
        assertEquals("The Boys", ProfilePickers.withoutBadge("The Boys, MOST LIKED")) // NBSP, as on the TV
        assertEquals("Road House, Free trial or buy", ProfilePickers.withoutBadge("Road House, Free trial or buy"))
        assertTrue(ProfilePickers.titleMatch(ProfilePickers.Shown("Love, Death & Robots", null), w("love, death & robots")) != null)
        // "Love" must not open "Love, Death & Robots", even with a year.
        assertEquals(null, ProfilePickers.titleMatch(ProfilePickers.Shown("Love, Death & Robots", null), w("Love", 2019)))
    }

    @Test
    fun punctuationAndApostrophesDontMatterButWordsDo() {
        val shown = ProfilePickers.Shown("Marvel Studios’ Avengers: Endgame", 2019)
        assertEquals(ProfilePickers.Match.CONTAINS_WITH_YEAR, ProfilePickers.titleMatch(shown, w("Avengers - Endgame", 2019)))
        assertEquals(ProfilePickers.Match.EXACT_WITH_YEAR, ProfilePickers.titleMatch(shown, w("Marvel Studios' Avengers: Endgame", 2020)))
        assertEquals(null, ProfilePickers.titleMatch(shown, w("Endgame Avengers", 2019)))
    }

    @Test
    fun twoEquallyGoodCandidatesMeanNoGuess() {
        val a = object : ProfilePickers.Node {
            override val text = ""; override val desc = "a"; override val viewId = ""; override val clickable = true
            override val bounds = emptyList<Int>(); override val parent: ProfilePickers.Node? = null
            override val children = emptyList<ProfilePickers.Node>()
        }
        val b = object : ProfilePickers.Node by a {}
        val two = listOf(a to ProfilePickers.Shown("Dune", null), b to ProfilePickers.Shown("Dune", null))
        assertEquals(null, ProfilePickers.bestTile(two, w("Dune", 2021)))
        // A year-confirmed one beats name-only ones.
        val sure = listOf(a to ProfilePickers.Shown("Dune", 2021), b to ProfilePickers.Shown("Dune", null))
        assertTrue(ProfilePickers.bestTile(sure, w("Dune", 2021)) === a)
    }

    // ---- waiting for a half-drawn picker ------------------------------------

    @Test
    fun aHalfDrawnPickerDoesNotMakeItGiveUp() {
        // As seen on a real TV: heading up, tiles not yet drawn. A picker with
        // no profiles on it is not yet the picker - counting those rounds
        // towards giving up cost a live run, which stopped with "your profile
        // isn't here" about a screen that was still being drawn.
        val settle = ProfilePickers.Settle()
        for (fixture in listOf("disney-picker-heading-only.xml", "disney-picker-loading.xml")) {
            val halfDrawn = disney.recognise(dump(fixture), "Bob")
            assertEquals(fixture, Outcome.NotPicker, halfDrawn)
            repeat(4) { assertEquals(ProfilePickers.Settle.Decision.KeepLooking, settle.next(halfDrawn)) }
        }

        val full = disney.recognise(dump("disney-picker.xml"), "Bob")
        assertTrue(settle.next(full) is ProfilePickers.Settle.Decision.Click)
    }

    @Test
    fun aNameThatStaysMissingIsGivenUpOnWithoutClicking() {
        val settle = ProfilePickers.Settle()
        val missing = disney.recognise(dump("disney-picker.xml"), "Zoe")
        repeat(3) { assertEquals(ProfilePickers.Settle.Decision.KeepLooking, settle.next(missing)) }
        assertEquals(ProfilePickers.Settle.Decision.GiveUp, settle.next(missing))
    }

    @Test
    fun leavingThePickerResetsTheCount() {
        val settle = ProfilePickers.Settle()
        repeat(3) { settle.next(Outcome.NoSuchProfile) }
        settle.next(Outcome.NotPicker)
        assertEquals(ProfilePickers.Settle.Decision.KeepLooking, settle.next(Outcome.NoSuchProfile))
    }

    // ---- continue, and one chosen episode ------------------------------------
    //
    // Screens from the real TV on 2026-09-27: The Boys on Prime Video (5
    // seasons), The Mandalorian on Disney+ (3 seasons).

    private fun ProfilePickers.Picker.list() = episodes!!
    private fun List<ProfilePickers.Listed>.eps() = map { "${it.season}x${it.episode}" }

    @Test
    fun continueSaysWhatTheServiceWillResume() {
        assertEquals("Resume Episode 8", prime.playLabel!!(dump("prime-series-page.xml")))
        assertEquals("Continue S1:E1 Chapter 1: The Mandalorian", disney.playLabel!!(dump("disney-series-page.xml")))
        // A film's page has no episode line (Disney+: just its button).
        assertEquals(null, disney.playLabel!!(dump("disney-picker.xml")))
    }

    @Test
    fun aPageThatOffersOnlyToStartOverIsNotTakenForContinue() {
        // Seen on the TV (The Boys, after an episode was played to its end):
        // no Resume / Watch button, only "Watch from beginning". Continue
        // must not press that; picking an episode must still find the page.
        val restart = dump("prime-series-no-resume.xml")
        assertEquals(null, prime.playButton!!(restart))
        assertTrue(prime.noResume!!(restart))
        assertTrue(prime.onTitlePage!!(restart))
        assertEquals("The Boys", prime.pageTitle!!(restart)!!.name)

        val normal = dump("prime-series-page.xml")
        assertTrue(!prime.noResume!!(normal))
        assertTrue(prime.onTitlePage!!(normal))
        // Search results show a preview header with the name, but aren't the page.
        assertTrue(!prime.onTitlePage!!(dump("prime-results.xml")))
    }

    @Test
    fun primeSeriesPageIsReadBeforeItsSeasonListIsInView() {
        val page = dump("prime-series-page.xml")
        assertEquals("The Boys", prime.pageTitle!!(page)!!.name)
        // Only a couple of the current season's tiles peek in at the bottom.
        assertEquals(5, prime.list().seasonShown(page))
        val cards = prime.list().cards(page)
        assertEquals(listOf("5x7", "5x8"), cards.eps())
        assertEquals("Blood and Bone", cards.last().name)
        assertTrue(cards.none { it.highlighted }) // the highlight is on Resume
    }

    @Test
    fun primeSeasonsAreADropDownThatHasToBeOpened() {
        val closed = dump("prime-series-seasons-closed.xml")
        // Its Episodes tab is the one showing (Prime also has Explore, Related, Extras).
        assertEquals(true, prime.list().episodesTab!!(closed)?.second)
        assertEquals(5, prime.list().seasonShown(closed))
        assertTrue(prime.list().seasonOpener!!(closed) != null)
        assertEquals(emptyList<ProfilePickers.Listed>(), prime.list().seasons(closed))

        // Open, it is a window of its own. Its highlight shows as `selected`:
        // here one Up from Season 5.
        val open = dump("prime-series-seasons-open.xml")
        val seasons = prime.list().seasons(open)
        assertEquals(listOf(1, 2, 3, 4, 5), seasons.map { it.season })
        assertEquals(listOf(4), seasons.filter { it.highlighted }.map { it.season })
        // Nothing here says which season is listed: that's read once it closes.
        assertEquals(null, prime.list().seasonShown(open))
    }

    @Test
    fun primeEpisodeRowNamesEachEpisodeAndWhereTheHighlightIs() {
        val row = dump("prime-series-row.xml")
        assertEquals(4, prime.list().seasonShown(row))
        val cards = prime.list().cards(row)
        assertEquals(listOf("4x4", "4x5", "4x6", "4x7", "4x8"), cards.eps())
        val at = cards.single { it.highlighted }
        assertEquals(5, at.episode)
        // The name the media session gave when this one played on the TV.
        assertEquals("BEWARE THE JABBERWOCK, MY SON", at.name)
        assertTrue(prime.list().horizontal)
        assertTrue(prime.list().sessionNamesEpisode)
    }

    @Test
    fun primeEpisodeTilesAreNotMistakenForOtherTitles() {
        // Search results are tiles too, named without "Season N, Episode N".
        assertEquals(emptyList<String>(), prime.list().cards(dump("prime-results.xml")).eps())
    }

    @Test
    fun disneyListsSeasonsAndEpisodesOnThePage() {
        val page = dump("disney-series-page.xml")
        assertEquals(1, disney.list().seasonShown(page))

        val list = dump("disney-series-episodes.xml")
        val seasons = disney.list().seasons(list)
        assertEquals(listOf(1, 2, 3), seasons.map { it.season })
        assertEquals(1, disney.list().seasonShown(list))
        // The season shown is read from the episodes listed, not only from
        // `selected`: seen on the TV, with the highlight on a season, none was
        // marked selected. Here that mark is taken away, and the answer holds.
        val unmarked = dump("disney-series-episodes.xml", dropSelected = true)
        assertTrue(disney.list().seasons(unmarked).none { it.node.selected })
        assertEquals(1, disney.list().seasonShown(unmarked))
        val cards = disney.list().cards(list)
        assertEquals(listOf("1x1", "1x2", "1x3", "1x4"), cards.eps())
        assertEquals(listOf(2), cards.filter { it.highlighted }.map { it.episode })
        assertTrue(disney.list().seasonOpener == null) // highlighting a season lists it
        assertTrue(!disney.list().horizontal)
        // Seasons are a column left of the episodes: Down there is another season.
        assertEquals(ProfilePickers.Arrow.RIGHT, disney.list().intoEpisodesFromSeasons)
        assertEquals(null, prime.list().intoEpisodesFromSeasons)
        // Disney+'s media session names only the show, so the card is the check.
        assertTrue(!disney.list().sessionNamesEpisode)
    }

    @Test
    fun theHighlightIsMovedByPlaceInTheList() {
        assertEquals(ProfilePickers.Arrow.RIGHT, ProfilePickers.arrowToward(1, 3, horizontal = true))
        assertEquals(ProfilePickers.Arrow.LEFT, ProfilePickers.arrowToward(5, 3, horizontal = true))
        assertEquals(ProfilePickers.Arrow.DOWN, ProfilePickers.arrowToward(0, 2, horizontal = false))
        assertEquals(ProfilePickers.Arrow.UP, ProfilePickers.arrowToward(4, 3, horizontal = false))
        assertEquals(null, ProfilePickers.arrowToward(3, 3, horizontal = true))
    }

    @Test
    fun hboEpisodesAreReachedByRunningLeftToTheStartBeforeCounting() {
        val route = ProfilePickers.Hbo::episodeRoute
        fun keys(r: List<ProfilePickers.Arrow>?) = r?.joinToString(" ") { it.name.take(1) }
        // The Last of Us, S2 E3 ("The Path"), as pressed on the TV: tab,
        // seasons, back to Season 1, one Right, episodes, back to E1, two Rights.
        assertEquals("D D L L R D L L L L L L L R R", keys(route(ProfilePickers.Episode(2, 3, "The Path"), 2, 7)))
        // One season (Chernobyl): HBO Max shows no season row at all.
        assertEquals("D D L L L L L R R R", keys(route(ProfilePickers.Episode(1, 4, null), 1, 5)))
        // True Detective opens on its last-watched season; the Lefts undo that.
        assertTrue(keys(route(ProfilePickers.Episode(1, 1, null), 4, 8))!!.startsWith("D D L L L L D"))
        // Bounded, however long a season TMDB claims.
        assertEquals(ProfilePickers.Hbo.MAX_LEFTS, route(ProfilePickers.Episode(1, 1, null), 1, 500)!!.count { it == ProfilePickers.Arrow.LEFT })
        assertEquals(null, route(ProfilePickers.Episode(3, 1, null), 2, 7))
        assertEquals(null, route(ProfilePickers.Episode(1, 0, null), 2, 7))
    }

    @Test
    fun hboMustNameTheVeryEpisodeAsked() {
        // What HBO Max's media session said while S2 E3 played on the TV.
        assertTrue(ProfilePickers.namesEpisode("The Path, The Last of Us", "The Last of Us", "The Path"))
        assertTrue(ProfilePickers.namesEpisode("When You're Lost in the Darkness, The Last of Us", "The Last of Us", "When You’re Lost in the Darkness"))
        assertTrue(!ProfilePickers.namesEpisode("Night Country: Part 1, True Detective", "True Detective", "Night Country: Part 10"))
        assertTrue(!ProfilePickers.namesEpisode("The Path, The Last of Us", "The Last of Us", "Future Days"))
        assertTrue(!ProfilePickers.namesEpisode("The Path, The Last of Us Podcast", "The Last of Us", "The Path"))
        assertTrue(!ProfilePickers.namesEpisode("The Path, The Last of Us", "The Last of Us", null))
    }

    // ---- scope -----------------------------------------------------------

    @Test
    fun onlyServicesWithReadableScreensAreOffered() {
        assertEquals(listOf("disneyplus", "primevideo"), ProfilePickers.ALL.map { it.serviceId })
        assertEquals("primevideo", ProfilePickers.forPackage("com.amazon.firebat")?.serviceId)
        assertEquals(null, ProfilePickers.forPackage("com.netflix.ninja"))
    }

    // ---- a uiautomator dump as ProfilePickers.Node -------------------------

    private class DumpNode(attrs: Map<String, String>, override val parent: DumpNode?, dropSelected: Boolean = false) : ProfilePickers.Node {
        override val text = attrs["text"] ?: ""
        override val desc = attrs["content-desc"] ?: ""
        override val viewId = attrs["resource-id"] ?: ""
        override val clickable = attrs["clickable"] == "true"
        override val focused = attrs["focused"] == "true"
        override val selected = !dropSelected && attrs["selected"] == "true"
        override val bounds = Regex("-?\\d+").findAll(attrs["bounds"] ?: "").map { it.value.toInt() }.toList()
        override val children = mutableListOf<ProfilePickers.Node>()
    }

    private fun dump(name: String, dropSelected: Boolean = false): ProfilePickers.Node {
        val xml = javaClass.classLoader!!.getResource("pickers/$name")!!.readText()
        val root = DumpNode(emptyMap(), null)
        var current = root
        Regex("<node([^>]*?)(/?)>|</node>").findAll(xml).forEach { m ->
            if (m.value == "</node>") {
                current = current.parent ?: root
                return@forEach
            }
            val attrs = Regex("([\\w-]+)=\"([^\"]*)\"").findAll(m.groupValues[1])
                .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            val node = DumpNode(attrs, current, dropSelected)
            current.children += node
            if (m.groupValues[2] != "/") current = node
        }
        return root
    }

    private fun unescape(s: String) = s.replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", "\n").replace("&amp;", "&")
}
