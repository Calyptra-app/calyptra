package com.calyptra.app.ui

/**
 * Settings actions that reduce protection and therefore require the parental
 * PIN (PIN-L3). Enabling protection is deliberately NOT a member: turning
 * protection ON must stay a single kid-friendly tap (Constitution II).
 */
enum class GatedAction {
    DISABLE_PROTECTION,
    GAME_ADS,
    SAFE_SEARCH,
    YOUTUBE_LEVEL,
    WHITELIST,
    CATEGORIES,
    /** Entering the parent settings screen (F12). The grace session opened on
     *  success makes the gated toggles inside proceed without re-prompting. */
    PARENT_SETTINGS,
}

sealed interface PinGateDecision {
    data object Proceed : PinGateDecision
    data object RequireSetup : PinGateDecision
    data object RequireChallenge : PinGateDecision
}

object PinGateReducer {

    @Suppress("UNUSED_PARAMETER") // action: all gated actions share one policy today
    fun decide(action: GatedAction, pinSet: Boolean, inGraceSession: Boolean): PinGateDecision =
        when {
            !pinSet -> PinGateDecision.RequireSetup
            inGraceSession -> PinGateDecision.Proceed
            else -> PinGateDecision.RequireChallenge
        }
}
