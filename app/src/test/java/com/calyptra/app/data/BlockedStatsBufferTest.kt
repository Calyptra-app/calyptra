package com.calyptra.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BlockedStatsBufferTest {

    private val day1 = LocalDate.of(2026, 3, 1)
    private val day2 = LocalDate.of(2026, 3, 2)

    @Test
    fun `record accumulates per date`() {
        val buffer = BlockedStatsBuffer()
        repeat(3) { buffer.record(day1) }
        buffer.record(day2, 4)

        assertEquals(mapOf(day1 to 3, day2 to 4), buffer.drain())
    }

    @Test
    fun `drain resets the buffer`() {
        val buffer = BlockedStatsBuffer()
        buffer.record(day1)
        buffer.drain()

        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `restore merges with counts recorded after the drain`() {
        val buffer = BlockedStatsBuffer()
        buffer.record(day1, 2)
        val snapshot = buffer.drain()
        buffer.record(day1)

        buffer.restore(snapshot)

        assertEquals(mapOf(day1 to 3), buffer.drain())
    }

    @Test
    fun `non-positive counts are ignored`() {
        val buffer = BlockedStatsBuffer()
        buffer.record(day1, 0)
        buffer.record(day1, -2)

        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `concurrent records are not lost`() {
        val buffer = BlockedStatsBuffer()
        val threads = (1..8).map {
            Thread { repeat(10_000) { buffer.record(day1) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(80_000, buffer.drain()[day1])
    }
}
