package com.calyptra.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Append-only protection event log (TML-L2). Writes are rare (a handful per
 * day) and always off the DNS packet path: callers on hot or main-ish paths
 * use [logAsync], which fires on [scope] — an app-lifetime Dispatchers.IO
 * scope in production — so no caller ever blocks (FR-13.2).
 */
class ProtectionEventRepository(
    private val dao: ProtectionEventDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Stamps the clock, inserts, prunes — one transaction (FR-13.6). */
    suspend fun log(type: ProtectionEventType) {
        val now = clock()
        dao.insertAndPrune(
            ProtectionEvent(timestampMillis = now, type = type.name),
            RetentionPolicy.MAX_EVENTS,
            RetentionPolicy.minRetainedTimestamp(now)
        )
    }

    /** Fire-and-forget [log] for non-suspending hook sites. */
    fun logAsync(type: ProtectionEventType) {
        scope.launch { log(type) }
    }

    /** Watchdog found protection down: record the gap start once. Skipped when
     *  the latest event already marks an off state (a revoke, an earlier
     *  watchdog find, or a parent disable), so 15-min re-checks of the same
     *  outage don't duplicate rows (FR-13.1 "no duplicates"). */
    suspend fun logUnexpectedStopOnce() {
        val latest = latestType()
        if (latest == null || latest.isEnabledKind) log(ProtectionEventType.STOPPED_UNEXPECTED)
    }

    /** Watchdog gave up: logged once per outage, not once per 15-min re-check. */
    suspend fun logRestoreFailedOnce() {
        if (latestType() != ProtectionEventType.RESTORE_FAILED) log(ProtectionEventType.RESTORE_FAILED)
    }

    fun eventsSince(cutoffMillis: Long): Flow<List<ProtectionEvent>> = dao.eventsSince(cutoffMillis)

    private suspend fun latestType(): ProtectionEventType? =
        dao.latestType()?.let { name ->
            // Unknown names (downgrade after a future version) are treated as absent.
            ProtectionEventType.entries.firstOrNull { it.name == name }
        }
}
