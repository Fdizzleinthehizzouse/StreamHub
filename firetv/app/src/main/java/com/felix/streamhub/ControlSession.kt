package com.felix.streamhub

import fi.iki.elonen.NanoHTTPD

/**
 * The control server plus the locks that keep it reachable, tied together so
 * neither can exist without the other.
 *
 * This is the whole reason the pairing is a class rather than four lines in
 * [ControlService]: off-device the tests can build one of these with a real
 * ControlServer and a fake [StandbyLocks], and assert that a running server is
 * always a locked one. They cannot build a Service.
 */
class ControlSession(
    private val locks: StandbyLocks,
    private val makeServer: () -> ControlServer
) {

    private var server: ControlServer? = null

    val isRunning: Boolean get() = server != null

    /**
     * Locks first, then the server: the TV can drop into standby between the
     * two, and a server that came up unlocked would be unreachable with no
     * sign of it anywhere.
     */
    fun start() {
        if (server != null) return
        locks.hold()
        val started = makeServer()
        runCatching { started.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onSuccess { server = started }
            .onFailure { e ->
                // Nothing is listening, so holding the TV's CPU awake for it
                // would be a lie that costs power. Let go and say so.
                locks.release()
                android.util.Log.e(TAG, "control server failed to start", e)
            }
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        locks.release()
    }

    /** Throw every paired phone off - called when the code is rotated. */
    fun revokeAll() = server?.revokeAll()

    private companion object {
        const val TAG = "StreamHubControl"
    }
}
