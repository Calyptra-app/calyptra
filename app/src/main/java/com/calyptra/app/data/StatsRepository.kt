package com.calyptra.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class StatsRepository(private val statsDao: StatsDao) {

    val totalBlocked: Flow<Int> = statsDao.getTotalBlocked().map { it ?: 0 }
    
    val todayBlocked: Flow<Int> = statsDao.getBlockedCountForDate(LocalDate.now()).map { it ?: 0 }

    suspend fun incrementCount() {
        statsDao.incrementCount(LocalDate.now())
    }
}