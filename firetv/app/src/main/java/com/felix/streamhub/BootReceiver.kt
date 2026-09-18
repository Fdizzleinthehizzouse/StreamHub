package com.felix.streamhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.felix.streamhub.data.Store

/**
 * Starts the remote again after the TV reboots.
 *
 * Without this, the first thing you'd know about an overnight reboot is your
 * phone failing to reach the TV - and the fix would be finding the TV remote and
 * opening StreamHub by hand, which is the exact chore this app exists to avoid.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (Store(context).controlEnabled) ControlService.start(context)
    }
}
