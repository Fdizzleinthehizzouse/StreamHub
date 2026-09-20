package com.felix.streamhub

import android.content.ContextWrapper
import com.felix.streamhub.data.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * The phone's page died whenever the TV went to standby: the server was started
 * with nothing holding the wifi chip or the CPU awake, so Fire OS parked both
 * and the accept loop stopped hearing the phone. Browsing, search and Wake TV
 * all went together, and Wake TV was the button that would have fixed it.
 *
 * These drive the real ControlServer with a counting [StandbyLocks], so a
 * server that comes up unlocked fails here rather than on the sofa.
 */
class StandbyLocksTest {

    private val locks = CountingLocks()
    private var session: ControlSession? = null
    private val store = Store(MemoryPrefs())

    @After
    fun stop() {
        session?.stop()
    }

    private fun session(port: Int = 0): ControlSession = ControlSession(locks) {
        // Loopback only, matching the other server tests: listening on every
        // interface makes Windows ask for a firewall exception on each fresh JDK.
        ControlServer(ContextWrapper(null), store, port = port, host = "127.0.0.1")
    }.also { session = it }

    @Test
    fun aRunningServerIsAlwaysALockedServer() {
        val s = session()
        s.start()

        assertTrue("the server should be up", s.isRunning)
        assertTrue(
            "the server came up with nothing holding the TV awake: the phone " +
                "would lose it the moment the TV slept",
            locks.held
        )
    }

    @Test
    fun theLocksAreLetGoWhenTheServerStops() {
        val s = session()
        s.start()
        s.stop()

        assertFalse(s.isRunning)
        assertFalse("standby locks outlived the server", locks.held)
    }

    @Test
    fun aServerThatCannotStartDoesNotLeaveTheTvAwakeForNothing() {
        // Occupy a port, then hand the session that same port so its bind fails.
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            val s = session(port = taken.localPort)
            s.start()

            assertFalse("nothing is listening", s.isRunning)
            assertFalse(
                "the server failed to start but the TV was left pinned awake",
                locks.held
            )
        }
    }

    @Test
    fun startingTwiceHoldsOnceAndStillReleasesCleanly() {
        val s = session()
        s.start()
        s.start()

        assertEquals("the second start should be a no-op", 1, locks.holds)

        s.stop()
        assertFalse(locks.held)
    }

    private class CountingLocks : StandbyLocks {
        var holds = 0
        var held = false
        override fun hold() {
            holds++
            held = true
        }

        override fun release() {
            held = false
        }
    }
}
