package com.felix.streamhub

import com.felix.streamhub.keys.KeyServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun onlyFixedKeysExist() {
        assertEquals(setOf("UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME", "PLAYPAUSE"), KeyServer.KEYS.keys)
        assertEquals(KeyServer.KEYS.keys, TvKeys.Key.values().map { it.name }.toSet())
    }
}
