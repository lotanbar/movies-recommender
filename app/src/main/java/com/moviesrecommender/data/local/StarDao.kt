package com.moviesrecommender.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StarDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(star: StarEntity)

    @Delete
    suspend fun delete(star: StarEntity)

    @Query("SELECT tmdbId FROM stars")
    suspend fun getAll(): List<Int>

    @Query("SELECT * FROM stars")
    suspend fun getAllEntities(): List<StarEntity>
}
