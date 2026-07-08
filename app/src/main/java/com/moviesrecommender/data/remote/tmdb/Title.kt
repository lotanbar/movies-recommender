package com.moviesrecommender.data.remote.tmdb

enum class MediaType { MOVIE, TV }

data class SeasonInfo(
    val seasonNumber: Int,
    val year: Int?,
    val episodeCount: Int
)

data class Title(
    val id: Int,
    val title: String,
    val year: Int,
    val posterPath: String?,
    val overview: String?,
    val genres: List<String>,
    val country: String?,
    val director: String?,
    val leadActors: List<String>,
    val productionCompany: String?,
    val mediaType: MediaType,
    val imdbId: String? = null,
    val trailerKeys: List<String> = emptyList(),
    val writer: String? = null,
    val producers: List<String> = emptyList(),
    val extraPosterPaths: List<String> = emptyList(),
    val runtime: Int? = null,
    val seasons: List<SeasonInfo> = emptyList()
) {
    fun posterUrl(width: Int = 500): String? =
        posterPath?.let { "https://image.tmdb.org/t/p/w$width$it" }

    fun allPosterUrls(width: Int = 500): List<String> = buildList {
        posterPath?.let { add("https://image.tmdb.org/t/p/w$width$it") }
        extraPosterPaths.forEach { add("https://image.tmdb.org/t/p/w$width$it") }
    }
}
