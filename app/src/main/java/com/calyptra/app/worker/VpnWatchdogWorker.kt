package com.calyptra.app.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.calyptra.app.CalyptraApp
import com.calyptra.app.MainActivity
import com.calyptra.app.R
import com.calyptra.app.data.ProtectionEventType
import com.calyptra.app.vpn.VpnController
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

enum class WatchdogAction { NONE, RESTART_VPN, ALERT_PERMISSION_LOST }

/** Pure decision table for the watchdog (PWR-L2) — see F9 spec. */
object VpnWatchdogPolicy {

    fun decide(protectionEnabled: Boolean, vpnRunning: Boolean, permissionHeld: Boolean): WatchdogAction =
        when {
            !protectionEnabled -> WatchdogAction.NONE
            vpnRunning -> WatchdogAction.NONE
            permissionHeld -> WatchdogAction.RESTART_VPN
            else -> WatchdogAction.ALERT_PERMISSION_LOST
        }
}

/** Periodic continuity check: restores the VPN after an OS kill, or alerts the
 *  parent when restore is impossible because the permission was lost (PWR-L2). */
class VpnWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CalyptraApp
        val action = VpnWatchdogPolicy.decide(
            protectionEnabled = app.preferencesRepository.protectionEnabled.first(),
            vpnRunning = VpnController.isRunning.value,
            permissionHeld = VpnService.prepare(applicationContext) == null
        )
        val events = app.protectionEventRepository
        when (action) {
            WatchdogAction.NONE -> Unit
            WatchdogAction.RESTART_VPN -> {
                // Gap start first, then the restore, so the timeline shows
                // "stopped → restored" with an honest off-duration (TML-L2).
                events.logUnexpectedStopOnce()
                VpnController.startVpn(applicationContext)
                events.log(ProtectionEventType.ENABLED_WATCHDOG)
            }
            WatchdogAction.ALERT_PERMISSION_LOST -> {
                events.logUnexpectedStopOnce()
                events.logRestoreFailedOnce()
                postAlert()
            }
        }
        return Result.success()
    }

    private fun postAlert() {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(applicationContext, CalyptraApp.ALERT_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.watchdog_alert_title))
            .setContentText(applicationContext.getString(R.string.watchdog_alert_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(WATCHDOG_NOTIFICATION_ID, notification)
    }

    companion object {
        const val WATCHDOG_NOTIFICATION_ID = 3
    }
}

/** Keeps the watchdog scheduled iff protection is intended (FR-9.2). */
object VpnWatchdogScheduler {

    private const val WORK_NAME = "vpn_watchdog"
    private const val WORK_NAME_NOW = "vpn_watchdog_now"

    fun schedule(context: Context) {
        // Initial delay = one period: a fresh periodic request runs immediately,
        // racing the still-establishing VPN (isRunning not yet true) and logging
        // a spurious STOPPED_UNEXPECTED/ENABLED_WATCHDOG pair on every enable
        // (TML-L2). The watchdog checks continuity, not launch; immediate checks
        // go through checkNow().
        val request = PeriodicWorkRequestBuilder<VpnWatchdogWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Immediate one-shot check, used on network changes (PWR-L4). */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<VpnWatchdogWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_NOW, ExistingWorkPolicy.REPLACE, request
        )
    }
}
