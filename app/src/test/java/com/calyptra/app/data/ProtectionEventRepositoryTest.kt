package com.calyptra.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory DAO double; records call order so the insert→prune sequence is observable. */
private class FakeProtectionEventDao : ProtectionEventDao {
    val events = mutableListOf<ProtectionEvent>()
    val calls = mutableListOf<String>()
    var lastPruneKeepCount: Int? = null
    var lastPruneMinTimestamp: Long? = null
    private val mutex = Mutex()
    private var nextId = 1L

    override suspend fun insert(event: ProtectionEvent) {
        mutex.withLock {
            events += event.copy(id = nextId++)
            calls += "insert"
        }
    }

    override suspend fun prune(keepCount: Int, minTimestampMillis: Long) {
        mutex.withLock {
            calls += "prune"
            lastPruneKeepCount = keepCount
            lastPruneMinTimestamp = minTimestampMillis
        }
    }

    override suspend fun latestType(): String? =
        events.maxWithOrNull(compareBy({ it.timestampMillis }, { it.id }))?.type

    override fun eventsSince(cutoffMillis: Long): Flow<List<ProtectionEvent>> =
        MutableStateFlow(events.filter { it.timestampMillis >= cutoffMillis })
}

class ProtectionEventRepositoryTest {

    private val dao = FakeProtectionEventDao()

    private fun repository(scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long = { 1_000L }) =
        ProtectionEventRepository(dao, scope, clock)

    @Test
    fun `log stamps the injected clock and stores the type name`() = runTest {
        var now = 42_000L
        val repo = repository(this) { now }
        repo.log(ProtectionEventType.DISABLED_PARENT)
        now = 43_000L
        repo.log(ProtectionEventType.ENABLED_USER)

        assertEquals(listOf(42_000L, 43_000L), dao.events.map { it.timestampMillis })
        assertEquals(listOf("DISABLED_PARENT", "ENABLED_USER"), dao.events.map { it.type })
    }

    @Test
    fun `log inserts then prunes with the retention parameters`() = runTest {
        val now = 10_000_000_000L
        repository(this) { now }.log(ProtectionEventType.ENABLED_BOOT)

        assertEquals(listOf("insert", "prune"), dao.calls)
        assertEquals(RetentionPolicy.MAX_EVENTS, dao.lastPruneKeepCount)
        assertEquals(RetentionPolicy.minRetainedTimestamp(now), dao.lastPruneMinTimestamp)
    }

    @Test
    fun `concurrent logs do not lose events`() = runTest {
        val repo = repository(this)
        val jobs = (1..50).map { launch { repo.log(ProtectionEventType.ENABLED_USER) } }
        jobs.forEach { it.join() }
        assertEquals(50, dao.events.size)
    }

    @Test
    fun `logUnexpectedStopOnce records the gap start after an enabled event`() = runTest {
        val repo = repository(this)
        repo.log(ProtectionEventType.ENABLED_WATCHDOG)
        repo.logUnexpectedStopOnce()
        assertEquals("STOPPED_UNEXPECTED", dao.events.last().type)
    }

    @Test
    fun `logUnexpectedStopOnce logs on an empty history`() = runTest {
        repository(this).logUnexpectedStopOnce()
        assertEquals(listOf("STOPPED_UNEXPECTED"), dao.events.map { it.type })
    }

    @Test
    fun `logUnexpectedStopOnce does not duplicate an already-recorded gap`() = runTest {
        val repo = repository(this)
        repo.log(ProtectionEventType.STOPPED_UNEXPECTED)
        repo.logUnexpectedStopOnce()
        repo.logUnexpectedStopOnce()
        assertEquals(1, dao.events.size)
    }

    @Test
    fun `logUnexpectedStopOnce keeps a revoke as the gap start`() = runTest {
        // After REVOKED_OTHER_VPN the off-period already has a start; the 15-min
        // watchdog re-check must not add a second off-marker.
        val repo = repository(this)
        repo.log(ProtectionEventType.REVOKED_OTHER_VPN)
        repo.logUnexpectedStopOnce()
        assertEquals(listOf("REVOKED_OTHER_VPN"), dao.events.map { it.type })
    }

    @Test
    fun `logRestoreFailedOnce does not spam on repeated watchdog runs`() = runTest {
        val repo = repository(this)
        repo.log(ProtectionEventType.STOPPED_UNEXPECTED)
        repo.logRestoreFailedOnce()
        repo.logRestoreFailedOnce()
        repo.logRestoreFailedOnce()
        assertEquals(
            listOf("STOPPED_UNEXPECTED", "RESTORE_FAILED"),
            dao.events.map { it.type }
        )
    }

    @Test
    fun `logAsync fires on the given scope without blocking the caller`() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        )
        val repo = repository(scope)
        repo.logAsync(ProtectionEventType.ENABLED_USER)
        assertTrue(dao.events.isEmpty()) // queued, caller not blocked
        testScheduler.advanceUntilIdle()
        assertEquals(1, dao.events.size)
    }
}
