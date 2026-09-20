package com.felix.streamhub

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * The two locks the control server needs to survive the TV going to standby.
 *
 * Without them the phone's page dies the moment the TV sleeps: Fire OS parks
 * the wifi chip and suspends the CPU, so NanoHTTPD's accept loop never sees the
 * phone's packet. Everything goes at once - browsing, search, and "Wake TV"
 * itself, which is the cruel part: the one button that would fix it is served
 * by the server that just went away.
 *
 * An interface because Android's real locks cannot be built off-device, and the
 * bug here was never in the locking itself - it was that nothing acquired them
 * at all. [ControlSession] is what the tests drive to prove that can't recur.
 */
interface StandbyLocks {
    fun hold()
    fun release()

    companion object {
        fun forTv(context: Context): StandbyLocks = AndroidStandbyLocks(context)

        /** For tests and for any caller that manages its own lifetime. */
        val NONE: StandbyLocks = object : StandbyLocks {
            override fun hold() {}
            override fun release() {}
        }
    }
}

private class AndroidStandbyLocks(context: Context) : StandbyLocks {

    private val appContext = context.applicationContext
    private var wifi: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null

    override fun hold() {
        if (wifi != null) return
        runCatching {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // WIFI_MODE_FULL_HIGH_PERF was deprecated at API 29, but targetSdk
            // is deliberately 28 (see build.gradle.kts), so it is still
            // honoured here - the plain FULL mode is not enough to stop the
            // chip being parked in standby.
            @Suppress("DEPRECATION")
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }

            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            // PARTIAL_WAKE_LOCK does NOT keep the TV awake: the screen still
            // turns off and standby still happens exactly as before. It keeps
            // the CPU running underneath it, which is all the server needs. The
            // TV is mains-powered, so there is no battery being traded away.
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            android.util.Log.e(TAG, "could not hold the standby locks; the phone will lose the TV in standby", it)
            release()
        }
    }

    override fun release() {
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        runCatching { cpu?.takeIf { it.isHeld }?.release() }
        wifi = null
        cpu = null
    }

    private companion object {
        const val TAG = "StreamHubControl"
        const val WIFI_TAG = "streamhub:server"
        const val CPU_TAG = "streamhub:server"
    }
}
