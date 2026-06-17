package com.calyptra.app.blocklist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistUpdatePolicyTest {

    private val floor = BlocklistUpdatePolicy.ABSOLUTE_FLOOR

    @Test
    fun `empty update is rejected`() {
        assertFalse(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 0, currentCount = 50_000))
        assertFalse(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 0, currentCount = 0))
    }

    @Test
    fun `update below the absolute floor is rejected even with no cache`() {
        assertFalse(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = floor - 1, currentCount = 0))
    }

    @Test
    fun `first update with no cache is accepted once it clears the floor`() {
        assertTrue(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = floor, currentCount = 0))
        assertTrue(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 60_000, currentCount = 0))
    }

    @Test
    fun `collapse below half of the current cache is rejected`() {
        // 70k cached, feed returns 5k -> implausible, keep previous.
        assertFalse(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 5_000, currentCount = 70_000))
        // Just under half is rejected.
        assertFalse(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 34_999, currentCount = 70_000))
    }

    @Test
    fun `normal fluctuation above half is accepted`() {
        // Exactly half is retained -> accepted.
        assertTrue(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 35_000, currentCount = 70_000))
        // Small shrink.
        assertTrue(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 68_000, currentCount = 70_000))
        // Growth.
        assertTrue(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 90_000, currentCount = 70_000))
    }

    @Test
    fun `retention ratio is skipped when there is no usable cache`() {
        // currentCount below the floor (e.g. corrupt/empty) must not let the
        // ratio reject an otherwise-healthy update.
        assertTrue(BlocklistUpdatePolicy.isPlausibleUpdate(newCount = 60_000, currentCount = 10))
    }
}
