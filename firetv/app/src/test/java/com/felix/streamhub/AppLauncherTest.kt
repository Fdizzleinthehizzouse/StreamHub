package com.felix.streamhub

import com.felix.streamhub.data.Services
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launcher entries exactly as a real Fire TV (AFT6E0FA, Fire OS 7.7.1.4)
 * reported them via `cmd package resolve-activity`.
 */
class AppLauncherTest {

    private val tvLauncher = setOf(
        "com.netflix.ninja", "com.disney.disneyplus", "com.hbo.hbonow", "com.amazon.firebat"
    )
    private val phoneLauncher = setOf(
        "com.netflix.ninja", "com.disney.disneyplus", "com.amazon.avod"
    )

    private fun pick(serviceId: String) = AppLauncher.pickPackage(
        Services.byId(serviceId)!!.packages,
        hasTvLauncher = { it in tvLauncher },
        hasLauncher = { it in phoneLauncher }
    )

    @Test
    fun hboMaxIsFoundThoughItHasOnlyATvLauncherEntry() {
        assertEquals("com.hbo.hbonow", pick("hbomax"))
    }

    @Test
    fun primeVideoOpensTheTvAppNotTheBackgroundPackage() {
        assertEquals("com.amazon.firebat", pick("primevideo"))
    }

    @Test
    fun appsInBothLaunchersStillResolve() {
        assertEquals("com.netflix.ninja", pick("netflix"))
        assertEquals("com.disney.disneyplus", pick("disneyplus"))
    }

    // ---- sending a title: each service's own search, never a browser ------

    @Test
    fun primeVideoSearchCarriesTheTitle() {
        val s = Services.byId("primevideo")!!.search!!
        assertEquals("https://app.primevideo.com/search?phrase=The%20Boys", s.link("The Boys"))
        assertEquals("search", AppLauncher.searchKind(s))
    }

    @Test
    fun disneyAndHboOpenTheirSearchPage() {
        val disney = Services.byId("disneyplus")!!.search!!
        assertEquals("disneyplus://www.disneyplus.com/search", disney.link("Moana"))
        assertTrue(disney.typeQuery)
        val hbo = Services.byId("hbomax")!!.search!!
        assertEquals("https://play.max.com/search", hbo.link("The Last of Us"))
        assertEquals("search-page", AppLauncher.searchKind(hbo))
    }

    /**
     * Each link must use a scheme and host the app itself claimed on a real
     * Fire TV (dumpsys package). A link it does not claim fails to start
     * rather than falling through - and never reaches a browser, because the
     * intent is pinned to the app's package.
     */
    @Test
    fun everySearchLinkIsOneTheAppClaims() {
        val claimed = mapOf(
            "disneyplus" to listOf("disneyplus://www.disneyplus.com/", "disneyplus://disneyplus.com/"),
            "hbomax" to listOf("https://play.max.com/", "https://play.hbomax.com/"),
            "primevideo" to listOf("https://app.primevideo.com/")
        )
        for (svc in Services.ALL) {
            val link = svc.search?.let { it.link("x") } ?: continue
            val ok = claimed[svc.id].orEmpty().any { link.startsWith(it) }
            assertTrue("${svc.id} search link $link is not one its app claims", ok)
        }
    }

    @Test
    fun aPhoneOnlyPackageIsStillUsedWhenNothingElseIsThere() {
        val got = AppLauncher.pickPackage(listOf("a", "b"), hasTvLauncher = { false }, hasLauncher = { it == "b" })
        assertEquals("b", got)
    }
}
