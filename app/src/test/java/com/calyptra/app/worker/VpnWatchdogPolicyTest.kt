package com.calyptra.app.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnWatchdogPolicyTest {

    @Test
    fun `does nothing when protection is disabled`() {
        for (running in listOf(true, false)) {
            for (held in listOf(true, false)) {
                assertEquals(
                    "disabled must be NONE (running=$running held=$held)",
                    WatchdogAction.NONE,
                    VpnWatchdogPolicy.decide(protectionEnabled = false, vpnRunning = running, permissionHeld = held)
                )
            }
        }
    }

    @Test
    fun `does nothing when protection is enabled and VPN is running`() {
        for (held in listOf(true, false)) {
            assertEquals(
                WatchdogAction.NONE,
                VpnWatchdogPolicy.decide(protectionEnabled = true, vpnRunning = true, permissionHeld = held)
            )
        }
    }

    @Test
    fun `restarts the VPN when intended, not running, and permission still held`() {
        assertEquals(
            WatchdogAction.RESTART_VPN,
            VpnWatchdogPolicy.decide(protectionEnabled = true, vpnRunning = false, permissionHeld = true)
        )
    }

    @Test
    fun `alerts the parent when intended, not running, and permission was lost`() {
        assertEquals(
            WatchdogAction.ALERT_PERMISSION_LOST,
            VpnWatchdogPolicy.decide(protectionEnabled = true, vpnRunning = false, permissionHeld = false)
        )
    }

    @Test
    fun `yields to another VPN even when permission still appears held`() {
        // Tailscale revoked us; permissionHeld can still read true on some devices.
        // We must NOT restart, or we would kill the other VPN.
        for (held in listOf(true, false)) {
            assertEquals(
                "revoked-by-other-VPN must be NONE (held=$held)",
                WatchdogAction.NONE,
                VpnWatchdogPolicy.decide(
                    protectionEnabled = true,
                    vpnRunning = false,
                    permissionHeld = held,
                    yieldedToOtherVpn = true
                )
            )
        }
    }
}
