package com.moviesrecommender.data.remote.omdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class OkHttpOmdbApiClient(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    suspend fun fetchShortPlot(imdbId: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://www.omdbapi.com/?i=$imdbId&plot=short&apikey=$apiKey")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val plot = JSONObject(body).optString("Plot")
                    if (plot.isBlank() || plot == "N/A") null else plot
                }
            } catch (_: Exception) {
                null
            }
        }
}
