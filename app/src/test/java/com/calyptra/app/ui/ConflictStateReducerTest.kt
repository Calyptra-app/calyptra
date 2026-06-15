package com.calyptra.app.ui

import com.calyptra.app.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictStateReducerTest {

    private fun reduce(
        prefEnabled: Boolean,
        vpnState: VpnState,
        vpnPermissionHeldByOther: Boolean,
        privateDnsActive: Boolean
    ) = ConflictStateReducer.reduce(prefEnabled, vpnState, vpnPermissionHeldByOther, privateDnsActive)

    @Test
    fun `no conflict when protected and running normally`() {
        assertEquals(
            ConflictState.NONE,
            reduce(prefEnabled = true, vpnState = VpnState.Running, vpnPermissionHeldByOther = false, privateDnsActive = false)
        )
    }

    @Test
    fun `revoked while protection intended is an OTHER_VPN conflict`() {
        assertEquals(
            ConflictState.OTHER_VPN,
            reduce(prefEnabled = true, vpnState = VpnState.Revoked, vpnPermissionHeldByOther = true, privateDnsActive = false)
        )
    }

    @Test
    fun `revoked counts even before the permission check refreshes`() {
        assertEquals(
            ConflictState.OTHER_VPN,
            reduce(prefEnabled = true, vpnState = VpnState.Revoked, vpnPermissionHeldByOther = false, privateDnsActive = false)
        )
    }

    @Test
    fun `stopped but another app holds the VPN permission is OTHER_VPN`() {
        // E.g. after a reboot where another VPN auto-started.
        assertEquals(
            ConflictState.OTHER_VPN,
            reduce(prefEnabled = true, vpnState = VpnState.Stopped, vpnPermissionHeldByOther = true, privateDnsActive = false)
        )
    }

    @Test
    fun `running with our own permission is never OTHER_VPN`() {
        // prepare() returning non-null while we are Running is a transient
        // read; Running is authoritative.
        assertEquals(
            ConflictState.NONE,
            reduce(prefEnabled = true, vpnState = VpnState.Running, vpnPermissionHeldByOther = true, privateDnsActive = false)
        )
    }

    @Test
    fun `private DNS while protected shows PRIVATE_DNS`() {
        assertEquals(
            ConflictState.PRIVATE_DNS,
            reduce(prefEnabled = true, vpnState = VpnState.Running, vpnPermissionHeldByOther = false, privateDnsActive = true)
        )
    }

    @Test
    fun `OTHER_VPN dominates PRIVATE_DNS`() {
        assertEquals(
            ConflictState.OTHER_VPN,
            reduce(prefEnabled = true, vpnState = VpnState.Revoked, vpnPermissionHeldByOther = true, privateDnsActive = true)
        )
    }

    @Test
    fun `no conflicts surfaced when protection is intentionally off`() {
        for (state in listOf(VpnState.Stopped, VpnState.Revoked)) {
            for (held in listOf(true, false)) {
                for (dns in listOf(true, false)) {
                    assertEquals(
                        "pref off must always be NONE (state=$state held=$held dns=$dns)",
                        ConflictState.NONE,
                        reduce(prefEnabled = false, vpnState = state, vpnPermissionHeldByOther = held, privateDnsActive = dns)
                    )
                }
            }
        }
    }

    @Test
    fun `stopped without other holder and without private DNS is NONE`() {
        assertEquals(
            ConflictState.NONE,
            reduce(prefEnabled = true, vpnState = VpnState.Stopped, vpnPermissionHeldByOther = false, privateDnsActive = false)
        )
    }
}
