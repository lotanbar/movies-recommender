package com.moviesrecommender.data.local

class LocalStorageService(private val dao: StarDao) {
    suspend fun addStar(tmdbId: Int, mediaType: String = "MOVIE") = dao.insert(StarEntity(tmdbId, mediaType))
    suspend fun removeStar(tmdbId: Int) = dao.deleteById(tmdbId)
    suspend fun getStars(): List<Int> = dao.getAll()
    suspend fun getStarsWithType(): List<StarEntity> = dao.getAllEntities()
    suspend fun isStarred(tmdbId: Int): Boolean = tmdbId in dao.getAll()
}
