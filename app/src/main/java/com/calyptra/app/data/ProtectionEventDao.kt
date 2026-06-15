package com.calyptra.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtectionEventDao {

    @Insert
    suspend fun insert(event: ProtectionEvent)

    @Query(
        "SELECT * FROM protection_events WHERE timestampMillis >= :cutoffMillis " +
            "ORDER BY timestampMillis DESC, id DESC"
    )
    fun eventsSince(cutoffMillis: Long): Flow<List<ProtectionEvent>>

    /** Type of the most recent event, for watchdog dedup (TML-L2). */
    @Query("SELECT type FROM protection_events ORDER BY timestampMillis DESC, id DESC LIMIT 1")
    suspend fun latestType(): String?

    /** SQL form of [RetentionPolicy.retain]: delete only rows that are both
     *  outside the newest-:keepCount and older than :minTimestampMillis. */
    @Query(
        "DELETE FROM protection_events WHERE timestampMillis < :minTimestampMillis AND id NOT IN " +
            "(SELECT id FROM protection_events ORDER BY timestampMillis DESC, id DESC LIMIT :keepCount)"
    )
    suspend fun prune(keepCount: Int, minTimestampMillis: Long)

    /** Opportunistic prune on every insert, atomically (FR-13.6). */
    @Transaction
    suspend fun insertAndPrune(event: ProtectionEvent, keepCount: Int, minTimestampMillis: Long) {
        insert(event)
        prune(keepCount, minTimestampMillis)
    }
}
