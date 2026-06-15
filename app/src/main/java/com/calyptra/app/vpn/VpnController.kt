package com.calyptra.app.vpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnState {
    data object Stopped : VpnState
    data object Running : VpnState

    /** Another app took the VPN (onRevoke). Distinct from Stopped so the UI
     *  and watchdog can tell a conflict from a normal user-initiated stop. */
    data object Revoked : VpnState
}

object VpnController {

    private val _state = MutableStateFlow<VpnState>(VpnState.Stopped)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Whether the system Always-on VPN setting targets us (PWR-L3, API 29+).
     *  Updated by the service after each establish(). */
    private val _alwaysOnActive = MutableStateFlow(false)
    val alwaysOnActive: StateFlow<Boolean> = _alwaysOnActive.asStateFlow()

    fun updateAlwaysOn(active: Boolean) {
        _alwaysOnActive.value = active
    }

    fun updateState(running: Boolean) {
        if (running) {
            setState(VpnState.Running)
        } else if (_state.value != VpnState.Revoked) {
            // The service's cleanup path calls updateState(false) right after
            // notifyRevoked(); Revoked must survive it.
            setState(VpnState.Stopped)
        }
    }

    fun notifyRevoked() {
        setState(VpnState.Revoked)
    }

    /** Called when the user explicitly disables protection (or re-enables it),
     *  consuming a pending Revoked signal. */
    fun acknowledgeRevoked() {
        if (_state.value == VpnState.Revoked) {
            setState(VpnState.Stopped)
        }
    }

    private fun setState(state: VpnState) {
        _state.value = state
        _isRunning.value = state == VpnState.Running
    }

    fun startVpn(context: Context) {
        val intent = Intent(context, AdBlockVpnService::class.java)
        context.startForegroundService(intent)
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, AdBlockVpnService::class.java)
        intent.action = AdBlockVpnService.ACTION_STOP
        context.startService(intent)
    }
}
