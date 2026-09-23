package com.gios.lightchat.socket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gios.lightchat.DeliveryWorker
import com.gios.lightchat.PollAlarm
import com.gios.lightchat.api.Store

/**
 * Puts delivery back together after the app updates itself.
 *
 * An update stops the process and, if the app wasn't running, nothing re-arms the alarm
 * chain or restarts the socket — so a phone that took an Obtainium update overnight and
 * wasn't opened afterwards goes quiet until it is, which reads exactly like the app being
 * broken. `ACTION_MY_PACKAGE_REPLACED` is delivered to the new version specifically so it
 * can repair itself, and unlike `BOOT_COMPLETED` it needs no permission.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if ((!Store.hasPassword(context) || Store.baseUrl(context) == null) &&
            !com.gios.lightchat.beeper.BeeperEngine.hasSession(context)
        ) return
        PollAlarm.schedule(context)
        DeliveryWorker.ensure(context)
        runCatching { context.startForegroundService(Intent(context, SocketService::class.java)) }
    }
}
