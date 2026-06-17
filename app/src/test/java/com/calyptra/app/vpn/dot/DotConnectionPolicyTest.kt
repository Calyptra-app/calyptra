package com.calyptra.app.vpn.dot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DotConnectionPolicyTest {

    private val timeout = DotConnectionPolicy.IDLE_TIMEOUT_MS

    @Test
    fun `connection just used is reusable`() {
        assertTrue(DotConnectionPolicy.isReusable(idleSinceMs = 1_000, nowMs = 1_000))
    }

    @Test
    fun `connection idle within the window is reusable`() {
        assertTrue(DotConnectionPolicy.isReusable(idleSinceMs = 1_000, nowMs = 1_000 + timeout - 1))
    }

    @Test
    fun `connection idle exactly at the timeout is not reusable`() {
        // Window is half-open: at the timeout the connection is considered aged out.
        assertFalse(DotConnectionPolicy.isReusable(idleSinceMs = 1_000, nowMs = 1_000 + timeout))
    }

    @Test
    fun `connection idle past the window is not reusable`() {
        assertFalse(DotConnectionPolicy.isReusable(idleSinceMs = 1_000, nowMs = 1_000 + timeout + 5_000))
    }

    @Test
    fun `negative elapsed from clock skew is not reusable`() {
        // Clock moved backwards since the connection was pooled — don't trust it.
        assertFalse(DotConnectionPolicy.isReusable(idleSinceMs = 5_000, nowMs = 4_000))
    }
}
