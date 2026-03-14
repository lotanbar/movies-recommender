package com.moviesrecommender.data.remote.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException

class OkHttpTmdbApiClient(
    private val httpClient: OkHttpClient = OkHttpClient()
) : TmdbApiClient {

    override suspend fun validateApiKey(apiKey: String): Unit = withContext(Dispatchers.IO) {
        executeGet("https://api.themoviedb.org/3/authentication?api_key=$apiKey")
    }

    override suspend fun search(query: String, apiKey: String, page: Int): SearchPage =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val root = JSONObject(
                executeGet("https://api.themoviedb.org/3/search/multi?query=$encoded&api_key=$apiKey&page=$page")
            )
            val totalPages = root.optInt("total_pages", 1)
            val results = root.getJSONArray("results")
            val titles = mutableListOf<Title>()
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val mediaType = when (obj.optString("media_type")) {
                    "movie" -> MediaType.MOVIE
                    "tv" -> MediaType.TV
                    else -> continue
                }
                val title = if (mediaType == MediaType.MOVIE) obj.optString("title")
                            else obj.optString("name")
                val dateStr = if (mediaType == MediaType.MOVIE) obj.optString("release_date")
                              else obj.optString("first_air_date")
                val year = dateStr.take(4).toIntOrNull() ?: continue
                titles.add(
                    Title(
                        id = obj.getInt("id"),
                        title = title,
                        year = year,
                        posterPath = obj.optString("poster_path").takeIf { it.isNotEmpty() },
                        overview = obj.optString("overview").takeIf { it.isNotEmpty() },
                        genres = emptyList(),
                        country = null,
                        director = null,
                        leadActors = emptyList(),
                        productionCompany = null,
                        mediaType = mediaType
                    )
                )
            }
            SearchPage(titles, totalPages)
        }

    override suspend fun fetchDetails(id: Int, mediaType: MediaType, apiKey: String): Title =
        withContext(Dispatchers.IO) {
            val endpoint = if (mediaType == MediaType.MOVIE) "movie" else "tv"
            val body = executeGet(
                "https://api.themoviedb.org/3/$endpoint/$id?api_key=$apiKey&append_to_response=credits,external_ids,videos,images"
            )
            val obj = JSONObject(body)

            val title = if (mediaType == MediaType.MOVIE) obj.optString("title")
                        else obj.optString("name")
            val dateStr = if (mediaType == MediaType.MOVIE) obj.optString("release_date")
                          else obj.optString("first_air_date")
            val year = dateStr.take(4).toIntOrNull() ?: 0

            val genres = buildList {
                val arr = obj.optJSONArray("genres") ?: return@buildList
                for (i in 0 until arr.length()) add(arr.getJSONObject(i).getString("name"))
            }

            val country = obj.optJSONArray("origin_country")?.optString(0)?.takeIf { it.isNotEmpty() }

            val productionCompany = obj.optJSONArray("production_companies")
                ?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotEmpty() }

            val credits = obj.optJSONObject("credits")

            val leadActors = buildList {
                val cast = credits?.optJSONArray("cast") ?: return@buildList
                for (i in 0 until minOf(cast.length(), 3)) {
                    cast.getJSONObject(i).optString("name").takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }

            var director: String? = null
            var writer: String? = null
            val producers = mutableListOf<String>()
            val crew = credits?.optJSONArray("crew")
            if (crew != null) {
                for (i in 0 until crew.length()) {
                    val member = crew.getJSONObject(i)
                    val job = member.optString("job")
                    val name = member.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    when {
                        director == null && job == "Director" -> director = name
                        writer == null && job in listOf("Screenplay", "Writer", "Story") -> writer = name
                        producers.size < 2 && job in listOf("Producer", "Executive Producer") -> producers.add(name)
                    }
                }
            }
            // For TV, pick up creator as writer if not found
            if (writer == null && mediaType == MediaType.TV) {
                val createdBy = obj.optJSONArray("created_by")
                if (createdBy != null && createdBy.length() > 0) {
                    writer = createdBy.getJSONObject(0).optString("name").takeIf { it.isNotEmpty() }
                }
            }

            val trailerKeys = buildList {
                val videos = obj.optJSONObject("videos")?.optJSONArray("results")
                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val v = videos.getJSONObject(i)
                        if (v.optString("site") == "YouTube" && v.optString("type") == "Trailer") {
                            v.optString("key").takeIf { it.isNotEmpty() }?.let { add(it) }
                        }
                    }
                }
            }

            val mainPosterPath = obj.optString("poster_path").takeIf { it.isNotEmpty() }
            val runtime = obj.optInt("runtime", 0).takeIf { it > 0 }
                ?: obj.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
            val extraPosterPaths = buildList {
                val posters = obj.optJSONObject("images")?.optJSONArray("posters")
                if (posters != null) {
                    for (i in 0 until posters.length()) {
                        if (size >= 3) break
                        val path = posters.getJSONObject(i).optString("file_path")
                            .takeIf { it.isNotEmpty() } ?: continue
                        if (path != mainPosterPath) add(path)
                    }
                }
            }

            Title(
                id = id,
                title = title,
                year = year,
                posterPath = mainPosterPath,
                overview = obj.optString("overview").takeIf { it.isNotEmpty() },
                genres = genres,
                country = country,
                director = director,
                leadActors = leadActors,
                productionCompany = productionCompany,
                mediaType = mediaType,
                imdbId = obj.optString("imdb_id").takeIf { it.isNotEmpty() }
                    ?: obj.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotEmpty() },
                trailerKeys = trailerKeys,
                writer = writer,
                producers = producers,
                extraPosterPaths = extraPosterPaths,
                runtime = runtime
            )
        }

    private fun executeGet(url: String): String {
        return try {
            val response = httpClient.newCall(Request.Builder().url(url).get().build()).execute()
            val body = response.body?.string() ?: ""
            when {
                response.code in 200..299 -> body
                response.code == 401 -> throw TmdbApiException.Unauthorized()
                else -> throw TmdbApiException.ServerError("HTTP ${response.code}: $body")
            }
        } catch (e: TmdbApiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw TmdbApiException.NoNetwork()
        } catch (e: IOException) {
            throw TmdbApiException.NoNetwork()
        }
    }
}
