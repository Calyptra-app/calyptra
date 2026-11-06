package com.calyptra.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StatsRepositoryTest {

    /** In-memory DAO: a map of date -> count exposed as flows. */
    private class FakeStatsDao : StatsDao() {
        val rows = MutableStateFlow<Map<LocalDate, Int>>(emptyMap())
        val writes = mutableListOf<Pair<LocalDate, Int>>()

        override fun getTotalBlocked(): Flow<Int?> = rows.map { it.values.sum() }
        override fun getBlockedCountForDate(date: LocalDate): Flow<Int?> = rows.map { it[date] }
        override suspend fun insertOrUpdate(stat: BlockedStat) {
            rows.value = rows.value + (stat.date to stat.count)
        }
        override suspend fun insertZeroIfAbsent(date: LocalDate) {
            if (date !in rows.value) rows.value = rows.value + (date to 0)
        }
        override suspend fun addToCount(date: LocalDate, n: Int) {
            rows.value[date]?.let { rows.value = rows.value + (date to it + n) }
        }
        override suspend fun incrementCount(date: LocalDate, n: Int) {
            writes += date to n
            super.incrementCount(date, n)
        }
    }

    /** A Clock driven by the test scheduler's virtual time. */
    private class VirtualClock(
        private val scope: TestScope,
        private val start: Instant,
        private val zoneId: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = VirtualClock(scope, start, zone)
        override fun instant(): Instant = start.plusMillis(scope.testScheduler.currentTime)
    }

    private val start = Instant.parse("2026-03-01T23:59:00Z")
    private val day1 = LocalDate.of(2026, 3, 1)
    private val day2 = LocalDate.of(2026, 3, 2)

    @Test
    fun `todayBlocked rolls over to the new date at midnight`() = runTest {
        val dao = FakeStatsDao()
        dao.rows.value = mapOf(day1 to 5, day2 to 9)
        val repo = StatsRepository(dao, VirtualClock(this, start), zone = { ZoneOffset.UTC })

        val seen = mutableListOf<Int>()
        val job = launch { repo.todayBlocked.collect { seen += it } }
        runCurrent()
        assertEquals(listOf(5), seen)

        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(listOf(5), seen)

        advanceTimeBy(1_001) // crosses 00:00 UTC
        runCurrent()
        assertEquals(listOf(5, 9), seen)
        job.cancel()
    }

    @Test
    fun `currentDate follows a timezone change within a minute`() = runTest {
        val dao = FakeStatsDao()
        // 2026-03-01T12:00Z: still March 1 in UTC, already March 2 in UTC+14.
        var zone: ZoneId = ZoneOffset.UTC
        val clock = VirtualClock(this, Instant.parse("2026-03-01T12:00:00Z"))
        val repo = StatsRepository(dao, clock, zone = { zone })

        val seen = mutableListOf<LocalDate>()
        val job = launch { repo.currentDate().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(day1), seen)

        zone = ZoneOffset.ofHours(14)
        advanceTimeBy(StatsRepository.DATE_RECHECK_INTERVAL.toMillis() + 1)
        runCurrent()
        assertEquals(listOf(day1, day2), seen)
        job.cancel()
    }

    @Test
    fun `recordBlocked does not touch the DB until flush, then writes one batch`() = runTest {
        val dao = FakeStatsDao()
        val repo = StatsRepository(dao, VirtualClock(this, Instant.parse("2026-03-01T10:00:00Z")))

        repeat(5) { repo.recordBlocked() }
        assertEquals(emptyList<Pair<LocalDate, Int>>(), dao.writes)

        repo.flush()
        assertEquals(listOf(day1 to 5), dao.writes)
        assertEquals(5, dao.rows.value[day1])

        repo.flush() // nothing pending -> no write
        assertEquals(1, dao.writes.size)
    }

    @Test
    fun `blocks either side of midnight are credited to their own date`() = runTest {
        val dao = FakeStatsDao()
        val repo = StatsRepository(dao, VirtualClock(this, start), zone = { ZoneOffset.UTC })

        repeat(2) { repo.recordBlocked() } // 23:59 on day1
        advanceTimeBy(120_000)             // 00:01 on day2
        repeat(3) { repo.recordBlocked() }
        repo.flush()

        assertEquals(mapOf(day1 to 2, day2 to 3), dao.rows.value)
    }

    @Test
    fun `periodic flush persists pending counts every interval`() = runTest {
        val dao = FakeStatsDao()
        val repo = StatsRepository(dao, VirtualClock(this, Instant.parse("2026-03-01T10:00:00Z")))
        val job = repo.startPeriodicFlush(this)

        repo.recordBlocked()
        advanceTimeBy(StatsRepository.FLUSH_INTERVAL.toMillis() - 1)
        runCurrent()
        assertEquals(null, dao.rows.value[day1])

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, dao.rows.value[day1])
        job.cancel()
    }
}
