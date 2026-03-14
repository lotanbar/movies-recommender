package com.moviesrecommender.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(star: StarEntity)

    @Query("DELETE FROM stars WHERE tmdbId = :tmdbId")
    suspend fun deleteById(tmdbId: Int)

    @Query("SELECT tmdbId FROM stars")
    suspend fun getAll(): List<Int>

    @Query("SELECT * FROM stars")
    suspend fun getAllEntities(): List<StarEntity>
}
