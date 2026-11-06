package com.calyptra.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
abstract class StatsDao {
    @Query("SELECT SUM(count) FROM BlockedStat")
    abstract fun getTotalBlocked(): Flow<Int?>

    @Query("SELECT count FROM BlockedStat WHERE date = :date")
    abstract fun getBlockedCountForDate(date: LocalDate): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrUpdate(stat: BlockedStat)

    @Query("INSERT OR IGNORE INTO BlockedStat (date, count) VALUES (:date, 0)")
    protected abstract suspend fun insertZeroIfAbsent(date: LocalDate)

    @Query("UPDATE BlockedStat SET count = count + :n WHERE date = :date")
    protected abstract suspend fun addToCount(date: LocalDate, n: Int)

    /**
     * Adds [n] to the blocked count for [date], creating the row if needed.
     *
     * This avoids UPSERT (`INSERT ... ON CONFLICT DO UPDATE`), which needs
     * SQLite 3.24+. Room uses the framework SQLite, which is older on API 26-29
     * (minSdk 26), so the upsert threw there and counts never moved (#66).
     * INSERT OR IGNORE then UPDATE, in one transaction, works on every SQLite
     * version Android ships.
     */
    @Transaction
    open suspend fun incrementCount(date: LocalDate, n: Int = 1) {
        if (n <= 0) return
        insertZeroIfAbsent(date)
        addToCount(date, n)
    }
}
