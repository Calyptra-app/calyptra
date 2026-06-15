package com.calyptra.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pins the F13 retention semantics (FR-13.6): an event is pruned only when it
 * is BOTH outside the newest-[RetentionPolicy.MAX_EVENTS] AND older than
 * [RetentionPolicy.MIN_RETAINED_DAYS]. The 30-day window is never punctured,
 * so off-durations computed from adjacent events stay honest.
 */
class RetentionPolicyTest {

    private val now = 1_750_000_000_000L
    private val dayMillis = TimeUnit.DAYS.toMillis(1)

    private fun event(id: Long, ageDays: Long) = ProtectionEvent(
        id = id,
        timestampMillis = now - ageDays * dayMillis,
        type = ProtectionEventType.ENABLED_USER.name
    )

    @Test
    fun `keeps newest 500 when over cap and outside the window`() {
        // 600 events, all older than 30 days: only the newest 500 survive.
        val events = (1L..600L).map { i -> event(id = i, ageDays = 31 + (600 - i)) }
        val retained = RetentionPolicy.retain(events, now)
        assertEquals(500, retained.size)
        assertEquals((101L..600L).toList(), retained.map { it.id }.sorted())
    }

    @Test
    fun `keeps everything under the cap regardless of age`() {
        // ≥ 30 days of history guaranteed; under the cap nothing is deleted,
        // even events much older than the window.
        val events = (1L..100L).map { i -> event(id = i, ageDays = i) } // up to 100 days old
        val retained = RetentionPolicy.retain(events, now)
        assertEquals(events.map { it.id }, retained.map { it.id })
    }

    @Test
    fun `never prunes events inside the 30-day window even over the cap`() {
        // Pathological flapping: 600 recent events. The window guarantee
        // dominates so durations remain computable.
        val events = (1L..600L).map { i -> event(id = i, ageDays = i % 29) }
        val retained = RetentionPolicy.retain(events, now)
        assertEquals(600, retained.size)
    }

    @Test
    fun `mixed old and recent events prune only the old overflow`() {
        val old = (1L..550L).map { i -> event(id = i, ageDays = 40) }      // outside window
        val recent = (551L..560L).map { i -> event(id = i, ageDays = 1) }  // inside window
        val retained = RetentionPolicy.retain(old + recent, now)
        // Newest 500 by timestamp = the 10 recent + 490 newest old (ties broken by id desc).
        assertEquals(500, retained.size)
        assertEquals(recent.map { it.id }, retained.map { it.id }.filter { it > 550L }.sorted())
    }

    @Test
    fun `prune is idempotent`() {
        val events = (1L..600L).map { i -> event(id = i, ageDays = 31 + (600 - i)) }
        val once = RetentionPolicy.retain(events, now)
        val twice = RetentionPolicy.retain(once, now)
        assertEquals(once, twice)
    }

    @Test
    fun `min retained timestamp is 30 days before now`() {
        assertEquals(now - 30 * dayMillis, RetentionPolicy.minRetainedTimestamp(now))
    }
}
