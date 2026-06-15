package com.calyptra.app.ui

import com.calyptra.app.vpn.VpnState

/** Why protection is compromised even though the parent wants it on (CFT-L3). */
enum class ConflictState {
    NONE,

    /** Another VPN app revoked ours or holds the system VPN permission. */
    OTHER_VPN,

    /** OS Private DNS (strict mode) bypasses our port-53 interception. */
    PRIVATE_DNS,
}

object ConflictStateReducer {

    fun reduce(
        prefEnabled: Boolean,
        vpnState: VpnState,
        vpnPermissionHeldByOther: Boolean,
        privateDnsActive: Boolean
    ): ConflictState = when {
        // Protection intentionally off: nothing to warn about.
        !prefEnabled -> ConflictState.NONE
        vpnState == VpnState.Revoked -> ConflictState.OTHER_VPN
        vpnState == VpnState.Stopped && vpnPermissionHeldByOther -> ConflictState.OTHER_VPN
        privateDnsActive -> ConflictState.PRIVATE_DNS
        else -> ConflictState.NONE
    }
}
