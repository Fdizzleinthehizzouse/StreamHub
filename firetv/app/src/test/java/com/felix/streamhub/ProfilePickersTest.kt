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

    @Test
    fun primeFindsExactlyTheSentTitleInItsResults() {
        val tile = prime.resultTile!!(dump("prime-results.xml"), "The Boys")!!
        assertEquals("The Boys, MOST LIKED", tile.desc) // non-breaking space, as on the TV
    }

    @Test
    fun primeNeverOpensAMerelySimilarTitle() {
        // "The Boys Presents: Diabolical" and "Prime Rewind: Inside The Boys" are right there.
        assertEquals(null, prime.resultTile!!(dump("prime-results.xml"), "The"))
        assertEquals(null, prime.resultTile!!(dump("prime-results.xml"), "Boys"))
        assertEquals(null, prime.resultTile!!(dump("prime-results.xml"), "The Boys Presents"))
    }

    @Test
    fun primeFindsWatchNowOnlyOnATitlesPage() {
        assertEquals(true, prime.playButton!!(dump("prime-title.xml"))!!.viewId.endsWith(":id/watch_now_button"))
        assertEquals(null, prime.playButton!!(dump("prime-results.xml")))
    }

    @Test
    fun primeNamesTheTitleOnItsPage() {
        assertEquals("The Boys", prime.pageTitle!!(dump("prime-title.xml")))
    }

    // ---- Disney+ autoplay (real screens) --------------------------------------

    @Test
    fun disneyFindsExactlyTheSentTitleInItsResults() {
        val tile = disney.resultTile!!(dump("disney-results.xml"), "Moana")!!
        assertTrue(tile.viewId.endsWith(":id/shelfItemRootLayout"))
        assertTrue(tile.desc.startsWith("Moana, Rated"))
    }

    @Test
    fun disneyNeverOpensTheSequelForTheOriginal() {
        // "Moana 2" is the next tile over.
        val tile = disney.resultTile!!(dump("disney-results.xml"), "Moana 2")!!
        assertTrue(tile.desc.startsWith("Moana 2,"))
        assertEquals(null, disney.resultTile!!(dump("disney-results.xml"), "Moan"))
    }

    @Test
    fun disneyFindsPlayAndTheTitleOnItsPage() {
        assertTrue(disney.playButton!!(dump("disney-title.xml"))!!.viewId.endsWith(":id/detailPageMainButtonOne"))
        assertEquals("Moana", disney.pageTitle!!(dump("disney-title.xml")))
        assertEquals(null, disney.playButton!!(dump("disney-results.xml")))
    }

    @Test
    fun titlesWithCommasStillMatch() {
        assertTrue(ProfilePickers.describesTitle("Love, Death & Robots, NEW SEASON", "Love, Death & Robots"))
        assertTrue(ProfilePickers.describesTitle("Love, Death & Robots", "love, death & robots"))
        assertEquals(false, ProfilePickers.describesTitle("Love, Death & Robots", "Love"))
        // Prime separates the badge with a non-breaking space.
        assertTrue(ProfilePickers.describesTitle("The Boys, MOST LIKED", "The Boys"))
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
