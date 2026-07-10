package com.moviesrecommender.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(usage: UsageEntity)

    @Query("SELECT * FROM usage ORDER BY timestamp DESC")
    suspend fun getAll(): List<UsageEntity>
}
