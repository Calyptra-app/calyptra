package com.calyptra.app.blocklist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BlocklistStalenessTest {
    private val now = 1_800_000_000_000L
    private fun daysAgo(d: Long) = now - TimeUnit.DAYS.toMillis(d)

    @Test fun `never updated catches up`() = assertTrue(BlocklistStaleness.needsCatchUp(0L, now))
    @Test fun `within the weekly schedule does not`() = assertFalse(BlocklistStaleness.needsCatchUp(daysAgo(7), now))
    @Test fun `older than eight days catches up`() = assertTrue(BlocklistStaleness.needsCatchUp(daysAgo(9), now))
}
