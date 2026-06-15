package com.calyptra.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM WhitelistedApp")
    fun getAll(): Flow<List<WhitelistedApp>>
    
    @Query("SELECT packageName FROM WhitelistedApp")
    suspend fun getAllPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: WhitelistedApp)

    @Delete
    suspend fun delete(app: WhitelistedApp)
}
