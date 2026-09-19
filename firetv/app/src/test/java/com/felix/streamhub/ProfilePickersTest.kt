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
        val hbo = ProfilePickers.Hbo
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
        assertEquals(5, ProfilePickers.Hbo.rightsToFirstResult("house of the dragon"))
        assertEquals(6, ProfilePickers.Hbo.rightsToFirstResult("the last of us"))
        assertEquals(4, ProfilePickers.Hbo.rightsToFirstResult("1917")) // "7": 3rd key of "5 6 7 8 9 0"
        assertEquals(1, ProfilePickers.Hbo.rightsToFirstResult("catch 22 4")) // "4" ends a row
        assertEquals(null, ProfilePickers.Hbo.rightsToFirstResult(""))
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

    // ---- Disney+ ---------------------------------------------------------

    @Test
    fun disneyClicksTheTileOfTheNamedProfile() {
        val found = disney.recognise(dump("disney-picker.xml"), "Bob") as Outcome.Found
        assertTrue("must click the clickable tile, not its label", found.node.clickable)
        assertEquals("Access Bob's profile", found.node.children.first().desc)
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
        // As seen on a real TV: heading up, tiles not yet drawn.
        val settle = ProfilePickers.Settle()
        val halfDrawn = disney.recognise(dump("disney-picker-heading-only.xml"), "Bob")
        assertEquals(Outcome.NoSuchProfile, halfDrawn)
        repeat(3) { assertEquals(ProfilePickers.Settle.Decision.KeepLooking, settle.next(halfDrawn)) }

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

    // ---- scope -----------------------------------------------------------

    @Test
    fun onlyServicesWithReadableScreensAreOffered() {
        assertEquals(listOf("disneyplus", "primevideo"), ProfilePickers.ALL.map { it.serviceId })
        assertEquals("primevideo", ProfilePickers.forPackage("com.amazon.firebat")?.serviceId)
        assertEquals(null, ProfilePickers.forPackage("com.netflix.ninja"))
    }

    // ---- a uiautomator dump as ProfilePickers.Node -------------------------

    private class DumpNode(attrs: Map<String, String>, override val parent: DumpNode?) : ProfilePickers.Node {
        override val text = attrs["text"] ?: ""
        override val desc = attrs["content-desc"] ?: ""
        override val viewId = attrs["resource-id"] ?: ""
        override val clickable = attrs["clickable"] == "true"
        override val bounds = Regex("-?\\d+").findAll(attrs["bounds"] ?: "").map { it.value.toInt() }.toList()
        override val children = mutableListOf<ProfilePickers.Node>()
    }

    private fun dump(name: String): ProfilePickers.Node {
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
            val node = DumpNode(attrs, current)
            current.children += node
            if (m.groupValues[2] != "/") current = node
        }
        return root
    }

    private fun unescape(s: String) = s.replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", "\n").replace("&amp;", "&")
}
