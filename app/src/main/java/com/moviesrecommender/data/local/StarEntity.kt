package com.moviesrecommender.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stars")
data class StarEntity(
    @PrimaryKey val tmdbId: Int,
    val mediaType: String = "MOVIE"
)
