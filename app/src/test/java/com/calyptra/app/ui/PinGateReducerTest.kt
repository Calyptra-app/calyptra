package com.calyptra.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PinGateReducerTest {

    @Test
    fun `every gated action requires setup when no pin is set`() {
        for (action in GatedAction.entries) {
            assertEquals(
                "$action with no pin should trigger setup",
                PinGateDecision.RequireSetup,
                PinGateReducer.decide(action, pinSet = false, inGraceSession = false)
            )
        }
    }

    @Test
    fun `every gated action requires a challenge when pin is set`() {
        for (action in GatedAction.entries) {
            assertEquals(
                "$action with pin set should challenge",
                PinGateDecision.RequireChallenge,
                PinGateReducer.decide(action, pinSet = true, inGraceSession = false)
            )
        }
    }

    @Test
    fun `grace session bypasses the challenge for all actions`() {
        for (action in GatedAction.entries) {
            assertEquals(
                "$action during grace session should proceed",
                PinGateDecision.Proceed,
                PinGateReducer.decide(action, pinSet = true, inGraceSession = true)
            )
        }
    }

    @Test
    fun `grace session never applies while pin is unset`() {
        // Defensive: an unset pin always routes to setup, grace flag is irrelevant.
        assertEquals(
            PinGateDecision.RequireSetup,
            PinGateReducer.decide(GatedAction.DISABLE_PROTECTION, pinSet = false, inGraceSession = true)
        )
    }

    @Test
    fun `enabling protection is not a gated action by design`() {
        // Kid-Friendly Simplicity: turning protection ON must never hit the PIN.
        // The gate is only reachable through GatedAction members — assert none
        // of them models enabling.
        assertFalse(GatedAction.entries.any { it.name.contains("ENABLE") })
    }

    @Test
    fun `expected gated actions are exactly the protection reductions`() {
        val expected = setOf(
            "DISABLE_PROTECTION", "GAME_ADS", "SAFE_SEARCH",
            "YOUTUBE_LEVEL", "WHITELIST", "CATEGORIES",
            // F12: entering the parent settings screen is the door to all of
            // the above; gating it opens the grace session at the threshold.
            "PARENT_SETTINGS"
        )
        assertEquals(expected, GatedAction.entries.map { it.name }.toSet())
    }
}
