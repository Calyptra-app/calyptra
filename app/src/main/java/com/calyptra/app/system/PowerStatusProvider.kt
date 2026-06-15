package com.calyptra.app.system

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
}
