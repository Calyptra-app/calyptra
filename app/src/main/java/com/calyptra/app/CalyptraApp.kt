package com.calyptra.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.calyptra.app.data.AppDatabase
import com.calyptra.app.data.PreferencesRepository
import com.calyptra.app.data.StatsRepository

import com.calyptra.app.blocklist.BlocklistManager
import com.calyptra.app.safesearch.SafeSearchManager
import com.calyptra.app.security.PinManager
import kotlinx.coroutines.launch

class CalyptraApp : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val preferencesRepository by lazy { PreferencesRepository(this) }
    val statsRepository by lazy { StatsRepository(database.statsDao()) }
    val blocklistManager by lazy { BlocklistManager(this) }
    val categoryBlockManager by lazy { com.calyptra.app.blocklist.CategoryBlockManager.fromResources(this) }
    val safeSearchManager by lazy { SafeSearchManager() }
    val pinManager by lazy { PinManager(preferencesRepository) }
    val networkMonitor by lazy { com.calyptra.app.vpn.NetworkEnvironmentMonitor(this) }
    val powerStatusProvider by lazy { com.calyptra.app.system.PowerStatusProvider(this) }
    val protectionEventRepository by lazy {
        com.calyptra.app.data.ProtectionEventRepository(database.protectionEventDao(), ioScope)
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    /** App-lifetime IO scope for fire-and-forget event logging (TML-L2, FR-13.2). */
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupWorker()
        setupWatchdogLockstep()
    }

    /** Fire-and-forget persistence of the "yielded to another VPN" flag (CFT-L1)
     *  on the app-lifetime scope, so the write survives the VPN service being
     *  torn down in the same breath (onRevoke -> stopVpn). */
    fun persistYieldAsync(yielded: Boolean) {
        ioScope.launch { preferencesRepository.setYieldedToOtherVpn(yielded) }
    }

    /** Watchdog runs iff protection is intended, no matter who writes the
     *  pref — ViewModel, boot receiver, or future writers (PWR-L2, FR-9.2). */
    private fun setupWatchdogLockstep() {
        appScope.launch {
            preferencesRepository.protectionEnabled.collect { enabled ->
                if (enabled) {
                    com.calyptra.app.worker.VpnWatchdogScheduler.schedule(this@CalyptraApp)
                } else {
                    com.calyptra.app.worker.VpnWatchdogScheduler.cancel(this@CalyptraApp)
                }
            }
        }
    }

    private fun setupWorker() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
            
        val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.calyptra.app.worker.BlocklistUpdateWorker>(
            7, java.util.concurrent.TimeUnit.DAYS
        ).setConstraints(constraints).build()
        
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "BlocklistUpdate",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Calyptra Protection"
            val descriptionText = "Persistent notification when protection is active"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.alert_channel_description)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "calyptra_channel"
        const val ALERT_CHANNEL_ID = "calyptra_alerts"
    }
}
