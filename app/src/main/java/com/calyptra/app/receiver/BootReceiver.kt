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
        // Without the VPN permission the service can't establish anyway.
        if (VpnService.prepare(context) != null) return

        val app = context.applicationContext as CalyptraApp
        // The pref read is async; goAsync keeps the receiver alive for it (SYS-L1).
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.preferencesRepository.protectionEnabled.first()) {
                    VpnController.startVpn(context)
                    app.protectionEventRepository.log(ProtectionEventType.ENABLED_BOOT)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
