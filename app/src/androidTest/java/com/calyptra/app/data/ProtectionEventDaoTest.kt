package com.calyptra.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Insert / flow / prune round-trip; pins the prune SQL to the
 *  [RetentionPolicy.retain] semantics (FR-13.6). */
@RunWith(AndroidJUnit4::class)
class ProtectionEventDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProtectionEventDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.protectionEventDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadBack_newestFirst() = runBlocking {
        dao.insert(ProtectionEvent(timestampMillis = 1_000L, type = "ENABLED_USER"))
        dao.insert(ProtectionEvent(timestampMillis = 3_000L, type = "DISABLED_PARENT"))
        dao.insert(ProtectionEvent(timestampMillis = 2_000L, type = "ENABLED_BOOT"))

        val events = dao.eventsSince(0L).first()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), events.map { it.timestampMillis })
        assertEquals("DISABLED_PARENT", events.first().type)
    }

    @Test
    fun eventsSince_appliesCutoff() = runBlocking {
        dao.insert(ProtectionEvent(timestampMillis = 1_000L, type = "ENABLED_USER"))
        dao.insert(ProtectionEvent(timestampMillis = 5_000L, type = "DISABLED_PARENT"))

        assertEquals(1, dao.eventsSince(2_000L).first().size)
    }

    @Test
    fun latestType_breaksTimestampTiesByid() = runBlocking {
        dao.insert(ProtectionEvent(timestampMillis = 1_000L, type = "STOPPED_UNEXPECTED"))
        dao.insert(ProtectionEvent(timestampMillis = 1_000L, type = "ENABLED_WATCHDOG"))

        assertEquals("ENABLED_WATCHDOG", dao.latestType())
    }

    @Test
    fun prune_matchesRetentionPolicySemantics() = runBlocking {
        val now = 10_000_000_000L
        val dayMillis = 86_400_000L
        // 510 events older than the 30-day window + 5 recent ones.
        repeat(510) { i ->
            dao.insert(ProtectionEvent(timestampMillis = now - 40 * dayMillis + i, type = "ENABLED_USER"))
        }
        repeat(5) { i ->
            dao.insert(ProtectionEvent(timestampMillis = now - i * 1_000L, type = "DISABLED_PARENT"))
        }

        dao.prune(RetentionPolicy.MAX_EVENTS, RetentionPolicy.minRetainedTimestamp(now))

        val retained = dao.eventsSince(0L).first()
        // Newest 500 survive: 5 recent + 495 newest of the old block.
        assertEquals(500, retained.size)
        assertEquals(5, retained.count { it.type == "DISABLED_PARENT" })

        // Idempotent: a second prune deletes nothing more.
        dao.prune(RetentionPolicy.MAX_EVENTS, RetentionPolicy.minRetainedTimestamp(now))
        assertEquals(500, dao.eventsSince(0L).first().size)
    }

    @Test
    fun prune_keepsEverythingUnderTheCap() = runBlocking {
        val now = 10_000_000_000L
        // Old events, but under the cap: the ≥30-day guarantee keeps them all.
        repeat(50) { i ->
            dao.insert(ProtectionEvent(timestampMillis = i.toLong(), type = "ENABLED_USER"))
        }
        dao.prune(RetentionPolicy.MAX_EVENTS, RetentionPolicy.minRetainedTimestamp(now))
        assertEquals(50, dao.eventsSince(0L).first().size)
    }

    @Test
    fun insertAndPrune_runsBothInOneCall() = runBlocking {
        val now = 10_000_000_000L
        dao.insertAndPrune(
            ProtectionEvent(timestampMillis = now, type = "ENABLED_USER"),
            RetentionPolicy.MAX_EVENTS,
            RetentionPolicy.minRetainedTimestamp(now)
        )
        assertEquals(1, dao.eventsSince(0L).first().size)
    }
}
