package com.calyptra.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects environment-level DNS bypasses (CFT-L4) and network transitions
 * (PWR-L4 hook). Detection only — never changes OS settings.
 */
class NetworkEnvironmentMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _privateDnsActive = MutableStateFlow(false)
    val privateDnsActive: StateFlow<Boolean> = _privateDnsActive.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Re-reads Private DNS state. Call on app resume and on network changes.
     *  Only strict mode (an explicit hostname) counts — Automatic/opportunistic
     *  mode falls back to plain DNS under our VPN and is not a bypass. */
    fun refresh() {
        _privateDnsActive.value = isPrivateDnsStrict()
    }

    /** True when another app's VPN is up. Side-effect free, unlike
     *  VpnService.prepare(), which for an already-consented app SWITCHES the
     *  active VPN to us and disconnects the other one (#80). On API 30+ our
     *  own tunnel is excluded by owner uid (getOwnerUid() is API 30; calling
     *  it on Android 10 throws NoSuchMethodError). Below that every VPN counts,
     *  which is safe because every caller acts on it only while ours is down
     *  (conflict banner: Stopped state; watchdog/boot: not running). */
    fun isOtherVpnActive(): Boolean {
        @Suppress("DEPRECATION") // allNetworks: no non-callback replacement for a one-shot read
        return connectivityManager.allNetworks.any { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || caps.ownerUid != Process.myUid())
        }
    }

    private fun isPrivateDnsStrict(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val network = connectivityManager.activeNetwork ?: return false
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return false
        return linkProperties.privateDnsServerName != null
    }

    /** Registers for connectivity transitions while the VPN runs. The callback
     *  fires on Wi-Fi/mobile handover and network loss (PWR-L4). */
    fun startWatching(onNetworkChange: () -> Unit) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh()
                onNetworkChange()
            }

            override fun onLost(network: Network) {
                refresh()
                onNetworkChange()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    fun stopWatching() {
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
    }
}
