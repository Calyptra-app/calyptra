package com.calyptra.app.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calyptra.app.data.PreferencesRepository
import com.calyptra.app.data.ProtectionEventRepository
import com.calyptra.app.data.ProtectionEventType
import com.calyptra.app.data.StatsRepository
import com.calyptra.app.security.PinManager
import com.calyptra.app.security.VerifyResult
import com.calyptra.app.system.PowerStatusProvider
import com.calyptra.app.vpn.NetworkEnvironmentMonitor
import com.calyptra.app.vpn.VpnController
import com.calyptra.app.vpn.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Marked @Immutable so the Compose compiler treats it as stable: every field
 *  is a val and the [blockedCategories] set is only ever replaced, never mutated
 *  in place. Without this, the `Set<String>` field (an interface type the
 *  compiler can't prove immutable) makes the whole class unstable and stops
 *  KidHomeContent / ParentSettingsContent from being skippable on recomposition. */
@Immutable
data class MainUiState(
    val isProtectionEnabled: Boolean = false,
    val gameAdsAllowed: Boolean = false,
    val adsBlockedToday: Int = 0,
    val adsBlockedTotal: Int = 0,
    val lastUpdate: Long = 0,
    val safeSearchEnabled: Boolean = true,
    val youtubeRestrictLevel: String = "strict",
    val conflictState: ConflictState = ConflictState.NONE,
    val batteryExempt: Boolean = true,
    val alwaysOnActive: Boolean = false,
    val batteryPromptShown: Boolean = true,
    val blockedCategories: Set<String> = emptySet()
)

/** Active PIN dialog, if any. isSetup=true shows the create-PIN flow. */
data class PinPromptState(
    val action: GatedAction,
    val isSetup: Boolean,
    val attemptsLeft: Int? = null,
    val lockedUntilMillis: Long? = null
)

class MainViewModel(
    private val statsRepository: StatsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val pinManager: PinManager,
    private val networkMonitor: NetworkEnvironmentMonitor,
    private val powerStatusProvider: PowerStatusProvider,
    private val protectionEventRepository: ProtectionEventRepository
) : ViewModel() {

    /** True when VpnService.prepare() returned non-null on last resume —
     *  i.e. we don't hold the system VPN permission (CFT-L3). */
    private val vpnPermissionHeldByOther = MutableStateFlow(false)

    /** Live battery-exemption status, refreshed on resume (PWR-L3). */
    private val batteryExempt = MutableStateFlow(true)

    /** Called from the UI on every ON_RESUME with a fresh prepare() check. */
    fun onResumed(prepareNeeded: Boolean) {
        vpnPermissionHeldByOther.value = prepareNeeded
        batteryExempt.value = powerStatusProvider.isIgnoringBatteryOptimizations()
        networkMonitor.refresh()
    }

    fun batteryExemptionIntent() = powerStatusProvider.requestExemptionIntent()
    fun vpnSettingsIntent() = powerStatusProvider.vpnSettingsIntent()

    fun markBatteryPromptShown() {
        viewModelScope.launch {
            preferencesRepository.setBatteryPromptShown()
        }
    }

    private val _pinPrompt = MutableStateFlow<PinPromptState?>(null)
    val pinPrompt: StateFlow<PinPromptState?> = _pinPrompt.asStateFlow()

    private var pendingAuthorized: (() -> Unit)? = null

    /** Routes a protection-reducing action through the PIN gate (PIN-L3).
     *  onAuthorized runs immediately during a grace session, after PIN setup,
     *  or after a successful challenge. Enabling protection must never call this. */
    fun requirePin(action: GatedAction, onAuthorized: () -> Unit) {
        viewModelScope.launch {
            when (PinGateReducer.decide(action, pinManager.isPinSet(), pinManager.isInGraceSession())) {
                PinGateDecision.Proceed -> onAuthorized()
                PinGateDecision.RequireSetup -> {
                    pendingAuthorized = onAuthorized
                    _pinPrompt.value = PinPromptState(action, isSetup = true)
                }
                PinGateDecision.RequireChallenge -> {
                    pendingAuthorized = onAuthorized
                    _pinPrompt.value = PinPromptState(action, isSetup = false)
                }
            }
        }
    }

    fun submitPinSetup(pin: String) {
        viewModelScope.launch {
            pinManager.setPin(pin)
            consumePendingAction()
        }
    }

    fun submitPinChallenge(pin: String) {
        viewModelScope.launch {
            when (val result = pinManager.verify(pin)) {
                VerifyResult.Ok -> consumePendingAction()
                is VerifyResult.Wrong -> _pinPrompt.update {
                    it?.copy(attemptsLeft = result.attemptsLeft, lockedUntilMillis = null)
                }
                is VerifyResult.LockedOut -> _pinPrompt.update {
                    it?.copy(attemptsLeft = null, lockedUntilMillis = result.untilMillis)
                }
            }
        }
    }

    fun dismissPinPrompt() {
        pendingAuthorized = null
        _pinPrompt.value = null
    }

    private fun consumePendingAction() {
        val action = pendingAuthorized
        pendingAuthorized = null
        _pinPrompt.value = null
        action?.invoke()
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MainUiState> = combine(
        VpnController.isRunning,
        statsRepository.todayBlocked,
        statsRepository.totalBlocked,
        preferencesRepository.lastBlocklistUpdate,
        preferencesRepository.gameAdsAllowed,
        preferencesRepository.safeSearchEnabled,
        preferencesRepository.youtubeRestrictLevel,
        VpnController.state,
        preferencesRepository.protectionEnabled,
        networkMonitor.privateDnsActive,
        vpnPermissionHeldByOther,
        batteryExempt,
        VpnController.alwaysOnActive,
        preferencesRepository.batteryPromptShown,
        preferencesRepository.blockedCategories
    ) { values ->
        MainUiState(
            isProtectionEnabled = values[0] as Boolean,
            adsBlockedToday = (values[1] as? Int) ?: 0,
            adsBlockedTotal = (values[2] as? Int) ?: 0,
            lastUpdate = values[3] as Long,
            gameAdsAllowed = values[4] as Boolean,
            safeSearchEnabled = values[5] as Boolean,
            youtubeRestrictLevel = values[6] as String,
            conflictState = ConflictStateReducer.reduce(
                prefEnabled = values[8] as Boolean,
                vpnState = values[7] as VpnState,
                vpnPermissionHeldByOther = values[10] as Boolean,
                privateDnsActive = values[9] as Boolean
            ),
            batteryExempt = values[11] as Boolean,
            alwaysOnActive = values[12] as Boolean,
            batteryPromptShown = values[13] as Boolean,
            blockedCategories = values[14] as Set<String>
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    fun setProtectionEnabled(enabled: Boolean) {
        if (!enabled) {
            // Explicit parent decision consumes a pending Revoked signal (CFT-L1).
            VpnController.acknowledgeRevoked()
        }
        // The disable path only runs PIN-authorized (PIN-L3), so parent
        // attribution is sound (TML-L2).
        protectionEventRepository.logAsync(
            if (enabled) ProtectionEventType.ENABLED_USER else ProtectionEventType.DISABLED_PARENT
        )
        viewModelScope.launch {
            preferencesRepository.setProtectionEnabled(enabled)
            // An explicit parent toggle ends any yield to another VPN (CFT-L1):
            // re-enabling means we want the slot back; disabling makes it moot.
            preferencesRepository.setYieldedToOtherVpn(false)
        }
    }

    fun setGameAdsAllowed(allowed: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setGameAdsAllowed(allowed)
        }
    }

    fun setSafeSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSafeSearchEnabled(enabled)
        }
    }

    fun setYoutubeRestrictLevel(level: String) {
        viewModelScope.launch {
            preferencesRepository.setYoutubeRestrictLevel(level)
        }
    }

    fun setCategoryBlocked(category: com.calyptra.app.blocklist.SocialCategory, blocked: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setCategoryBlocked(category.key, blocked)
        }
    }
}
