package com.calyptra.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VpnControllerTest {

    @Before
    fun resetState() {
        // VpnController is a process-wide object; drive it back to Stopped
        // through the public API so tests stay order-independent.
        VpnController.acknowledgeRevoked()
        VpnController.updateState(false)
    }

    @Test
    fun `initial state is Stopped and not running`() {
        assertEquals(VpnState.Stopped, VpnController.state.value)
        assertFalse(VpnController.isRunning.value)
    }

    @Test
    fun `updateState true moves to Running`() {
        VpnController.updateState(true)
        assertEquals(VpnState.Running, VpnController.state.value)
        assertTrue(VpnController.isRunning.value)
    }

    @Test
    fun `updateState false moves to Stopped`() {
        VpnController.updateState(true)
        VpnController.updateState(false)
        assertEquals(VpnState.Stopped, VpnController.state.value)
        assertFalse(VpnController.isRunning.value)
    }

    @Test
    fun `notifyRevoked from Running moves to Revoked and stops running`() {
        VpnController.updateState(true)
        VpnController.notifyRevoked()
        assertEquals(VpnState.Revoked, VpnController.state.value)
        assertFalse(VpnController.isRunning.value)
    }

    @Test
    fun `updateState false does not downgrade Revoked to Stopped`() {
        // onRevoke() -> notifyRevoked() is followed by the service's own
        // stopVpn() -> updateState(false); the Revoked signal must survive it
        // so the UI and watchdog can distinguish conflict from a normal stop.
        VpnController.updateState(true)
        VpnController.notifyRevoked()
        VpnController.updateState(false)
        assertEquals(VpnState.Revoked, VpnController.state.value)
    }

    @Test
    fun `updateState true clears Revoked back to Running`() {
        VpnController.notifyRevoked()
        VpnController.updateState(true)
        assertEquals(VpnState.Running, VpnController.state.value)
        assertTrue(VpnController.isRunning.value)
    }

    @Test
    fun `acknowledgeRevoked clears Revoked to Stopped`() {
        VpnController.notifyRevoked()
        VpnController.acknowledgeRevoked()
        assertEquals(VpnState.Stopped, VpnController.state.value)
    }

    @Test
    fun `acknowledgeRevoked is a no-op when Running`() {
        VpnController.updateState(true)
        VpnController.acknowledgeRevoked()
        assertEquals(VpnState.Running, VpnController.state.value)
        assertTrue(VpnController.isRunning.value)
    }

    @Test
    fun `acknowledgeRevoked is a no-op when Stopped`() {
        VpnController.acknowledgeRevoked()
        assertEquals(VpnState.Stopped, VpnController.state.value)
    }

    @Test
    fun `isRunning stays consistent with state across transitions`() {
        VpnController.updateState(true)
        assertTrue(VpnController.isRunning.value)
        VpnController.notifyRevoked()
        assertFalse(VpnController.isRunning.value)
        VpnController.updateState(true)
        assertTrue(VpnController.isRunning.value)
        VpnController.updateState(false)
        assertFalse(VpnController.isRunning.value)
    }
}
