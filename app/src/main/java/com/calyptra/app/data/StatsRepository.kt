package com.calyptra.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Blocked-query statistics.
 *
 * Writes are batched. [recordBlocked] only bumps an in-memory per-date counter
 * (non-suspending, safe on the DNS hot path), and [flush] persists it. The app
 * runs [startPeriodicFlush] on its lifetime scope and also flushes when the VPN
 * stops.
 *
 * **Loss window:** blocks recorded since the last successful flush live only in
 * memory. If the process dies without a clean VPN stop (OOM kill, force-stop,
 * crash), up to [FLUSH_INTERVAL] (30 s) of block counts are lost. A clean VPN
 * stop triggers an immediate flush.
 *
 * @param clock source of the current instant (injectable for tests).
 * @param zone the current zone. It is re-read on every evaluation, so a device
 *   timezone change is picked up (Android resets the JVM default zone on
 *   ACTION_TIMEZONE_CHANGED).
 */
class StatsRepository(
    private val statsDao: StatsDao,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val buffer = BlockedStatsBuffer()
    private val flushMutex = Mutex()

    val totalBlocked: Flow<Int> = statsDao.getTotalBlocked().map { it ?: 0 }

    /** Today's persisted count. Follows the local date across midnight and
     *  timezone changes (#68). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todayBlocked: Flow<Int> = currentDate()
        .flatMapLatest { date -> statsDao.getBlockedCountForDate(date) }
        .map { it ?: 0 }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock.withZone(zone()))

    /**
     * Emits the local date now, then again whenever it changes. Wakes at the
     * next local midnight but never sleeps longer than [DATE_RECHECK_INTERVAL],
     * so a timezone change is noticed within a minute without a broadcast
     * receiver.
     */
    fun currentDate(): Flow<LocalDate> = flow {
        while (true) {
            val now = now()
            emit(now.toLocalDate())
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            val untilMidnight = Duration.between(now, nextMidnight)
            delay(minOf(untilMidnight, DATE_RECHECK_INTERVAL).toMillis().coerceAtLeast(1))
        }
    }.distinctUntilChanged()

    /** Records one blocked query against the local date at the time of the
     *  block, so a midnight crossing splits correctly. Non-suspending. */
    fun recordBlocked() {
        buffer.record(now().toLocalDate())
    }

    /** Persists all pending counts. Writes that fail are put back for the next flush. */
    suspend fun flush() {
        flushMutex.withLock {
            val pending = buffer.drain()
            if (pending.isEmpty()) return
            val unwritten = HashMap(pending)
            try {
                for ((date, n) in pending) {
                    try {
                        statsDao.incrementCount(date, n)
                        unwritten.remove(date)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Stats flush failed for $date; will retry", e)
                    }
                }
            } finally {
                if (unwritten.isNotEmpty()) buffer.restore(unwritten)
            }
        }
    }

    /** Flushes every [FLUSH_INTERVAL] for the lifetime of [scope]. */
    fun startPeriodicFlush(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(FLUSH_INTERVAL.toMillis())
            flush()
        }
    }

    companion object {
        private const val TAG = "StatsRepository"
        val FLUSH_INTERVAL: Duration = Duration.ofSeconds(30)
        val DATE_RECHECK_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
