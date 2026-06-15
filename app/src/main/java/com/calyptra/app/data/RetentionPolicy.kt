package com.calyptra.app.data

import java.util.concurrent.TimeUnit

/**
 * F13 retention rule (FR-13.6): an event is pruned only when it is BOTH
 * outside the newest-[MAX_EVENTS] AND older than [MIN_RETAINED_DAYS]. The
 * 30-day window is never punctured, so off-durations derived from adjacent
 * events stay honest; the cap holds whenever fewer than [MAX_EVENTS]
 * transitions happen inside the window (anything else is watchdog flapping).
 *
 * [retain] is the pure mirror of [ProtectionEventDao.prune]'s SQL; the
 * instrumented DAO test pins the two to each other.
 */
object RetentionPolicy {

    const val MAX_EVENTS = 500
    const val MIN_RETAINED_DAYS = 30L

    fun minRetainedTimestamp(nowMillis: Long): Long =
        nowMillis - TimeUnit.DAYS.toMillis(MIN_RETAINED_DAYS)

    fun retain(events: List<ProtectionEvent>, nowMillis: Long): List<ProtectionEvent> {
        val minTimestamp = minRetainedTimestamp(nowMillis)
        val newestIds = events
            .sortedWith(compareByDescending<ProtectionEvent> { it.timestampMillis }.thenByDescending { it.id })
            .take(MAX_EVENTS)
            .mapTo(mutableSetOf()) { it.id }
        return events.filter { it.timestampMillis >= minTimestamp || it.id in newestIds }
    }
}
