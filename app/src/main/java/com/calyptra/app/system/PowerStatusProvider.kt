package com.calyptra.app.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Battery-optimization and VPN-settings integration points (PWR-L3).
 *  Status is always read live — exemption can change behind our back. */
class PowerStatusProvider(private val context: Context) {

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestExemptionIntent(): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    fun vpnSettingsIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS)

    /** Whether Calyptra may post notifications at all (#64). On Android 13+
     *  this is off until POST_NOTIFICATIONS is granted; on any version the
     *  parent can switch it off. Parent alerts (revoked, restore failed) are
     *  notifications, so with this off they never show. */
    fun areNotificationsAllowed(): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    /** Calyptra's own notification settings page. */
    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
