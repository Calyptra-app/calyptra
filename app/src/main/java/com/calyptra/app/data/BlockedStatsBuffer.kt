package com.calyptra.app.data

import java.time.LocalDate

/**
 * In-memory, per-date tally of blocked queries waiting to be flushed to the DB.
 *
 * [record] is a short, non-suspending critical section, so the DNS hot path
 * never waits on persistence. [drain] returns everything pending and resets the
 * buffer. [restore] merges a drained snapshot back after a failed write, so a
 * transient DB error doesn't drop counts.
 */
class BlockedStatsBuffer {
    private val lock = Any()
    private var pending = HashMap<LocalDate, Int>()

    fun record(date: LocalDate, n: Int = 1) {
        if (n <= 0) return
        synchronized(lock) {
            pending[date] = (pending[date] ?: 0) + n
        }
    }

    fun drain(): Map<LocalDate, Int> = synchronized(lock) {
        val snapshot = pending
        pending = HashMap()
        snapshot
    }

    fun restore(counts: Map<LocalDate, Int>) {
        counts.forEach { (date, n) -> record(date, n) }
    }
}
