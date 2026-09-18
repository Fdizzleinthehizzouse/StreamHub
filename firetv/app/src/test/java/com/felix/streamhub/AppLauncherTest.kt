package com.felix.streamhub

import com.felix.streamhub.data.Services
import org.junit.Assert.assertEquals
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

    @Test
    fun aPhoneOnlyPackageIsStillUsedWhenNothingElseIsThere() {
        val got = AppLauncher.pickPackage(listOf("a", "b"), hasTvLauncher = { false }, hasLauncher = { it == "b" })
        assertEquals("b", got)
    }
}
