package com.calyptra.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.calyptra.app.CalyptraApp
import com.calyptra.app.data.ProtectionEventType
import com.calyptra.app.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as CalyptraApp
        // The pref read is async; goAsync keeps the receiver alive for it (SYS-L1).
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Don't resurrect protection if we yielded the VPN slot to another
                // VPN — auto-starting on boot would kill it again (CFT-L1).
                val yielded = app.preferencesRepository.yieldedToOtherVpn.first()
                if (app.preferencesRepository.protectionEnabled.first() && !yielded &&
                    !app.networkMonitor.isOtherVpnActive() &&
                    // Last: prepare() takes the VPN from any other app (#80).
                    // Without the permission the service can't establish anyway.
                    VpnService.prepare(context) == null
                ) {
                    VpnController.startVpn(context)
                    app.protectionEventRepository.log(ProtectionEventType.ENABLED_BOOT)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
