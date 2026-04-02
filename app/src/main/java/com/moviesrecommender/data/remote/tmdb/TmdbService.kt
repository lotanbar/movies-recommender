package com.moviesrecommender.data.remote.tmdb


class TmdbService(
    private val apiClient: TmdbApiClient
) {
    companion object {
        private const val API_KEY = "247857421ce7cbac468ebb57d8f0225f"
    }

    fun isConfigured(): Boolean = true

    suspend fun search(query: String, page: Int = 1): TmdbResult<SearchPage> {
        val apiKey = API_KEY
        return try {
            TmdbResult.Success(apiClient.search(query, apiKey, page))
        } catch (e: TmdbApiException.Unauthorized) {
            TmdbResult.Failure(TmdbError.InvalidApiKey)
        } catch (e: TmdbApiException.NoNetwork) {
            TmdbResult.Failure(TmdbError.NoInternet)
        } catch (e: TmdbApiException.ServerError) {
            TmdbResult.Failure(TmdbError.ApiError(e.message ?: "Unknown error"))
        }
    }

    suspend fun fetchDetails(id: Int, mediaType: MediaType): TmdbResult<Title> {
        val apiKey = API_KEY
        return try {
            TmdbResult.Success(apiClient.fetchDetails(id, mediaType, apiKey))
        } catch (e: TmdbApiException.Unauthorized) {
            TmdbResult.Failure(TmdbError.InvalidApiKey)
        } catch (e: TmdbApiException.NoNetwork) {
            TmdbResult.Failure(TmdbError.NoInternet)
        } catch (e: TmdbApiException.ServerError) {
            TmdbResult.Failure(TmdbError.ApiError(e.message ?: "Unknown error"))
        }
    }

    suspend fun fetchMetadata(title: String, year: Int): TmdbResult<Title> {
        val apiKey = API_KEY
        return try {
            val candidates = apiClient.search(title, apiKey, 1).titles
            val match = candidates.firstOrNull { it.year == year } ?: candidates.firstOrNull()
                ?: return TmdbResult.Failure(TmdbError.NotFound)
            TmdbResult.Success(apiClient.fetchDetails(match.id, match.mediaType, apiKey))
        } catch (e: TmdbApiException.Unauthorized) {
            TmdbResult.Failure(TmdbError.InvalidApiKey)
        } catch (e: TmdbApiException.NoNetwork) {
            TmdbResult.Failure(TmdbError.NoInternet)
        } catch (e: TmdbApiException.ServerError) {
            TmdbResult.Failure(TmdbError.ApiError(e.message ?: "Unknown error"))
        }
    }
}
