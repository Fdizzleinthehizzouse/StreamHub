package com.felix.streamhub

import com.felix.streamhub.keys.KeyServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The helper's only gate: who is on the other end of a connection. */
class KeyServerTest {

    // Rows in the format of the real TV's /proc/net/tcp. 0x2214 = 8724 (the
    // helper), 0xC350 = 50000 (a caller's ephemeral port).
    private val rows = listOf(
        "   0: 0100007F:2214 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 111 1 0",
        "   1: 0100007F:C350 0100007F:2214 01 00000000:00000000 00:00000000 00000000 10238        0 222 1 0",
        "   2: 0100007F:2214 0100007F:C350 01 00000000:00000000 00:00000000 00000000  2000        0 333 1 0",
        "   3: 0100007F:C351 0100007F:2214 01 00000000:00000000 00:00000000 00000000  2000        0 444 1 0"
    )

    @Test
    fun findsTheCallersUidFromItsSideOfTheConnection() {
        assertEquals(10238, KeyServer.uidFor(rows, localPort = 50000, remotePort = KeyServer.PORT))
    }

    @Test
    fun anotherCallerIsSeenAsItself() {
        assertEquals(2000, KeyServer.uidFor(rows, localPort = 50001, remotePort = KeyServer.PORT))
    }

    @Test
    fun theHelpersOwnEndIsNotMistakenForTheCaller() {
        // Row 2 (the helper's end, uid 2000) also joins 8724 and 50000; the
        // caller's row must be the one picked.
        assertEquals(10238, KeyServer.uidFor(rows.reversed(), localPort = 50000, remotePort = KeyServer.PORT))
    }

    @Test
    fun onlyEstablishedConnectionsCountAndUnknownCallersGetNothing() {
        assertNull(KeyServer.uidFor(rows, localPort = KeyServer.PORT, remotePort = 0)) // row 0: LISTEN
        assertNull(KeyServer.uidFor(rows, localPort = 1234, remotePort = KeyServer.PORT))
    }

    @Test
    fun playingIsReadFromTheMediaSessionDump() {
        // As `dumpsys media_session` printed on the real TV while Prime played.
        val dump = listOf(
            "      package=com.amazon.firebat",
            "      state=PlaybackState {state=3, position=0, buffered position=0, speed=1.0, updated=1117643266, actions=173318}",
            "      package=com.netflix.ninja",
            "      state=PlaybackState {state=1, position=0, buffered position=0, speed=1.0}",
            "      package=com.spotify.tv.android"
        )
        assertEquals(listOf("com.amazon.firebat"), KeyServer.playingFrom(dump))
        assertEquals(emptyList<String>(), KeyServer.playingFrom(dump.drop(2)))
    }

    @Test
    fun whatIsPlayingIsReadWithItsTitle() {
        // As printed on the real TV while HBO Max played The Last of Us.
        val dump = listOf(
            "      package=com.hbo.hbonow",
            "      state=PlaybackState {state=3, position=33508, buffered position=80080, speed=1.0}",
            "      metadata:size=5, description=When You're Lost in the Darkness, The Last of Us, null",
            "      package=com.netflix.ninja",
            "      state=PlaybackState {state=1, position=0, buffered position=0, speed=1.0}",
            "      metadata:size=0, description=null",
            "      package=com.amazon.firebat",
            "      state=PlaybackState {state=3, position=0}",
            "      metadata:size=8, description=Road House (2024), null, null",
            "      package=com.hbo.max.film",
            "      state=PlaybackState {state=3, position=0}",
            "      metadata:size=5, description=Dune, , null",
            "      package=com.amazon.vizzini",
            "      metadata:size=1, description=null, null, null"
        )
        assertEquals(
            listOf(
                "com.hbo.hbonow" to "When You're Lost in the Darkness, The Last of Us",
                "com.amazon.firebat" to "Road House (2024)",
                "com.hbo.max.film" to "Dune" // as HBO Max printed a film: "Dune, , null"
            ),
            KeyServer.nowPlayingFrom(dump)
        )
    }

    @Test
    fun theAppInFrontIsReadFromTheWindowDump() {
        val dump = listOf(
            "  mInputMethodTarget=null",
            "  mCurrentFocus=Window{ee73170 u0 com.hbo.hbonow/com.wbd.beam.BeamActivity}",
            "  mFocusedApp=AppWindowToken{66d7dff token=Token{79a711e ActivityRecord{4ead159 u0 com.amazon.firebat/.X t951}}}"
        )
        assertEquals("com.hbo.hbonow", KeyServer.frontFrom(dump))
        assertNull(KeyServer.frontFrom(listOf("  mCurrentFocus=null")))
    }

    @Test
    fun onlyShortPlainTitlesCanBeTyped() {
        assertTrue(KeyServer.TYPEABLE.matches("house of the dragon"))
        assertTrue(KeyServer.TYPEABLE.matches("1917"))
        for (bad in listOf("", "House", "a;b", "a\nb", "rm -rf", "x".repeat(61), "é")) {
            assertFalse(bad, KeyServer.TYPEABLE.matches(bad))
        }
    }

    @Test
    fun onlyNetflixMayBeClosed() {
        // Closing an app is the one thing here that isn't a key press: Netflix
        // has to be restarted to land on a screen the TV can predict.
        assertEquals(setOf("com.netflix.ninja"), KeyServer.STOPPABLE)
    }

    @Test
    fun onlyFixedKeysExist() {
        // A fixed list, so a caller can never ask the helper for anything else.
        assertEquals(
            setOf(
                "UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME", "PLAYPAUSE",
                "VOLUMEUP", "VOLUMEDOWN", "MUTE", "FORWARD", "REWIND", "NEXT", "PREVIOUS",
                "WAKE", "SLEEP"
            ),
            KeyServer.KEYS.keys
        )
        assertEquals(KeyServer.KEYS.keys, TvKeys.Key.values().map { it.name }.toSet())
        // Wake and sleep are separate: one POWER toggle would leave the phone
        // guessing which way round the TV is.
        assertTrue(KeyServer.KEYS.keys.none { it == "POWER" })
    }
}
