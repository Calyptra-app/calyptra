package com.calyptra.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class LockoutPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `no lockout below the 5-failure threshold`() {
        for (failures in 0..4) {
            assertEquals(
                "failures=$failures should not lock out",
                0L,
                LockoutPolicy.lockoutUntil(failures, now)
            )
        }
    }

    @Test
    fun `fifth failure locks out for 30 seconds`() {
        assertEquals(now + 30_000L, LockoutPolicy.lockoutUntil(5, now))
    }

    @Test
    fun `sixth failure doubles to 60 seconds`() {
        assertEquals(now + 60_000L, LockoutPolicy.lockoutUntil(6, now))
    }

    @Test
    fun `seventh failure doubles to 120 seconds`() {
        assertEquals(now + 120_000L, LockoutPolicy.lockoutUntil(7, now))
    }

    @Test
    fun `lockout caps at 300 seconds`() {
        assertEquals(now + 300_000L, LockoutPolicy.lockoutUntil(9, now))
        assertEquals(now + 300_000L, LockoutPolicy.lockoutUntil(20, now))
        assertEquals("huge counters must not overflow", now + 300_000L, LockoutPolicy.lockoutUntil(1000, now))
    }

    @Test
    fun `eighth failure is 240 seconds, just under the cap`() {
        assertEquals(now + 240_000L, LockoutPolicy.lockoutUntil(8, now))
    }
}
