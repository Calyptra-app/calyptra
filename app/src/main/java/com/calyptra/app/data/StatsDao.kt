package com.calyptra.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface StatsDao {
    @Query("SELECT SUM(count) FROM BlockedStat")
    fun getTotalBlocked(): Flow<Int?>

    @Query("SELECT count FROM BlockedStat WHERE date = :date")
    fun getBlockedCountForDate(date: LocalDate): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stat: BlockedStat)
    
    @Query("INSERT INTO BlockedStat (date, count) VALUES (:date, 1) ON CONFLICT(date) DO UPDATE SET count = count + 1")
    suspend fun incrementCount(date: LocalDate)
}