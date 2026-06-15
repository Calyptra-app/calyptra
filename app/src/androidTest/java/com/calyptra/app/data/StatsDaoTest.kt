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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class StatsDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: StatsDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // for testing only
            .build()
        dao = db.statsDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun writeAndReadStats() = runBlocking {
        val today = LocalDate.now()
        val stat = BlockedStat(today, 10)
        dao.insertOrUpdate(stat)
        
        val count = dao.getBlockedCountForDate(today).first()
        assertEquals(10, count)
        
        val total = dao.getTotalBlocked().first()
        assertEquals(10, total)
    }

    @Test
    fun incrementCountIncrementsExisting() = runBlocking {
        val today = LocalDate.now()
        val stat = BlockedStat(today, 10)
        dao.insertOrUpdate(stat)
        
        dao.incrementCount(today)
        
        val count = dao.getBlockedCountForDate(today).first()
        assertEquals(11, count)
    }
    
    @Test
    fun incrementCountCreatesNew() = runBlocking {
        val today = LocalDate.now()
        // No insert
        
        dao.incrementCount(today)
        
        val count = dao.getBlockedCountForDate(today).first()
        assertEquals(1, count)
    }
}
