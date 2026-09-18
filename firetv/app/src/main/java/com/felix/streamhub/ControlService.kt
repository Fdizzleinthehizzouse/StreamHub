package com.felix.streamhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.felix.streamhub.data.Store
import fi.iki.elonen.NanoHTTPD

/**
 * The receiving half of "control the TV from the PC or phone".
 *
 * Runs a small HTTP server on the TV's own address. Your phone opens it as a
 * web page, and everything - browsing, searching, launching Netflix - happens
 * between those two devices on your own wifi.
 *
 * Pairing: the TV mints its own code, shows it on screen, and never accepts one
 * offered by a caller.
 */
class ControlService : Service() {

    private var server: ControlServer? = null
    private lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        store.ensureControlToken()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (server == null) {
            server = ControlServer(applicationContext, store).also {
                runCatching { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                    .onFailure { e -> android.util.Log.e(TAG, "control server failed to start", e) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        super.onDestroy()
    }

    /** Throw every paired phone off - called when the code is rotated. */
    fun unpairAll() = server?.revokeAll()

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "streamhub-control"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "StreamHub remote", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification: Notification = builder
            .setContentTitle("StreamHub")
            .setContentText("Ready for your phone")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        // Not swallowed: on API 26+ the system kills the app a few seconds
        // after startForegroundService if this never succeeds, and a silent
        // catch turns a diagnosable crash into a mystery disappearance.
        try {
            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "startForeground failed; the control link will not stay up", e)
        }
    }

    companion object {
        private const val TAG = "StreamHubControl"
        private const val NOTIF_ID = 4711
        const val PORT = 8723
        const val MAX_BODY_BYTES = 64L * 1024

        fun start(context: Context) {
            val intent = Intent(context, ControlService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ControlService::class.java)) }
        }
    }
}
