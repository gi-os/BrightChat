package com.gios.lightchat

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gios.lightchat.api.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gios.lightchat.socket.SocketService
import java.util.concurrent.TimeUnit

/**
 * A second, independent way for the catch-up to happen.
 *
 * [PollAlarm] is a chain, not a schedule: allow-while-idle alarms have no repeating form,
 * so each firing arms the next and any single break is permanent. Breaks are ordinary —
 * a force-stop cancels every alarm the app owns, so does clearing its data, and an app
 * update can land while the process is dead with nothing to re-arm from. The symptom is
 * exactly the one this whole file family exists to fix: everything looks armed and
 * connected, and no message arrives until the app is opened by hand.
 *
 * WorkManager is worth adding *because it fails differently*. Periodic work lives in
 * JobScheduler, in the system's own store: it survives process death and an app update,
 * is restored after a reboot without a `BOOT_COMPLETED` receiver, and re-enqueues itself
 * without the app running. (Not a force-stop — that clears jobs as well as alarms; the
 * activity and the package-replaced receiver cover that one.) What it can't do is be prompt — periodic work has a 15 minute minimum and is
 * deferred to a Doze maintenance window, so it is useless as *the* delivery mechanism and
 * good as the thing that notices the alarm chain is gone and rebuilds it.
 *
 * So this does three things, in order of what's most likely to be broken: re-arm the alarm,
 * restart the socket service if it isn't running, and run a catch-up.
 */
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if ((!Store.hasPassword(app) || Store.baseUrl(app) == null) &&
            !com.gios.lightchat.beeper.BeeperEngine.hasSession(app)
        ) return Result.success()

        // First, because it's the layer that actually delivers on time and the one most
        // likely to have gone missing.
        runCatching { PollAlarm.schedule(app) }

        // The service is START_STICKY, but a force-stop or a low-memory kill can leave it
        // down with nothing to bring it back until the app is opened. Starting an FGS from
        // the background is restricted on 14 — holding SYSTEM_ALERT_WINDOW is one of the
        // exemptions, which this app has for the heads-up box anyway — and it's harmless if
        // it's already up (onStartCommand on a running service).
        runCatching { app.startForegroundService(Intent(app, SocketService::class.java)) }
            .onFailure { Log.d(TAG, "couldn't start the socket service: $it") }

        // doWork runs on Dispatchers.Default; CatchUp is blocking HttpURLConnection work
        // that can hold a thread for tens of seconds.
        withContext(Dispatchers.IO) { runCatching { CatchUp.run(app) } }
        // Always success, even when the server was unreachable. `retry()` looks right and
        // is a trap here: its backoff is exponential from 30s up to five hours and only
        // resets on success, so a night with the tunnel down would push the
        // chain-of-last-resort out to hours — exactly when it's most needed. The 15 minute
        // period is already the retry.
        return Result.success()
    }

    companion object {
        private const val TAG = "DeliveryWorker"
        private const val NAME = "delivery-backstop"

        /**
         * Enqueues the periodic work. UPDATE rather than REPLACE or KEEP: REPLACE resets
         * the period on every call, and this is called from app start, boot and the service
         * — an app opened every ten minutes would mean the backstop never ran at all. KEEP
         * avoids that but freezes the period and constraints at whatever the first install
         * enqueued, so a later version couldn't change them. UPDATE keeps the existing
         * schedule and applies the current spec.
         */
        fun ensure(context: Context) {
            val request = PeriodicWorkRequestBuilder<DeliveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    // No point waking up to make a REST call with no network. Note this is
                    // the only constraint: requiring an idle or charging device is how a
                    // backstop ends up never running.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }.onFailure { Log.w(TAG, "couldn't enqueue: $it") }
        }
    }
}
