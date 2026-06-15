package com.calyptra.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartGuardTest {

    private var now = 0L
    private fun guard() = RestartGuard(clock = { now })

    @Test
    fun `first two failures within the window allow retry`() {
        val guard = guard()
        assertTrue(guard.shouldRetryAfterFailure())
        now += 10_000
        assertTrue(guard.shouldRetryAfterFailure())
    }

    @Test
    fun `third failure within five minutes gives up`() {
        val guard = guard()
        guard.shouldRetryAfterFailure()
        now += 60_000
        guard.shouldRetryAfterFailure()
        now += 60_000
        assertFalse("3 failures in 5 min must give up", guard.shouldRetryAfterFailure())
    }

    @Test
    fun `failures outside the window do not count`() {
        val guard = guard()
        guard.shouldRetryAfterFailure()
        guard.shouldRetryAfterFailure()
        now += 6 * 60_000 // both failures age out of the 5-min window
        assertTrue("old failures must be forgotten", guard.shouldRetryAfterFailure())
    }

    @Test
    fun `reset clears the failure history`() {
        val guard = guard()
        guard.shouldRetryAfterFailure()
        guard.shouldRetryAfterFailure()
        guard.reset()
        assertTrue(guard.shouldRetryAfterFailure())
        assertTrue(guard.shouldRetryAfterFailure())
        assertFalse(guard.shouldRetryAfterFailure())
    }

    @Test
    fun `sliding window only counts recent failures`() {
        val guard = guard()
        guard.shouldRetryAfterFailure()      // t=0, ages out later
        now += 4 * 60_000
        guard.shouldRetryAfterFailure()      // t=4min
        now += 2 * 60_000                     // t=6min: first failure aged out
        assertTrue(guard.shouldRetryAfterFailure())
    }
}
