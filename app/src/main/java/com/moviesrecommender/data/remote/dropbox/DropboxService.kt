package com.moviesrecommender.data.remote.dropbox

class DropboxService(
    val authManager: DropboxAuthManager,
    private val apiClient: DropboxApiClient
) {
    companion object {
        const val APP_KEY = "pj3my54okmjjdrd"
    }

    fun isAuthenticated(): Boolean = authManager.isAuthenticated()

    fun getAuthUrl(): String = authManager.buildAuthUrl(APP_KEY)

    suspend fun handleAuthCallback(code: String): DropboxResult<Unit> {
        val verifier = authManager.getCodeVerifier()
            ?: return DropboxResult.Failure(DropboxError.Unknown("Missing code verifier"))
        val appKey = authManager.getAppKey() ?: APP_KEY
        return try {
            val (accessToken, refreshToken) = apiClient.exchangeCode(code, verifier, appKey)
            authManager.saveTokens(accessToken, refreshToken)
            DropboxResult.Success(Unit)
        } catch (e: DropboxApiException) {
            DropboxResult.Failure(e.toDropboxError())
        }
    }

    suspend fun listEntries(path: String): DropboxResult<DropboxEntries> {
        return executeWithRefresh { token -> apiClient.listEntries(path, token) }
    }

    suspend fun downloadList(): DropboxResult<String> {
        val path = authManager.getListPath()
            ?: return DropboxResult.Failure(DropboxError.Unknown("List file not configured"))
        return executeWithRefresh { token -> apiClient.download(path, token) }
    }

    suspend fun uploadList(content: String): DropboxResult<Unit> {
        val path = authManager.getListPath()
            ?: return DropboxResult.Failure(DropboxError.Unknown("List file not configured"))
        return executeWithRefresh { token -> apiClient.upload(path, content, token) }
    }

    suspend fun refreshToken(): DropboxResult<Unit> {
        val refreshToken = authManager.getRefreshToken()
            ?: return DropboxResult.Failure(DropboxError.TokenExpired)
        val appKey = authManager.getAppKey() ?: APP_KEY
        return try {
            val newToken = apiClient.refreshToken(refreshToken, appKey)
            authManager.updateAccessToken(newToken)
            DropboxResult.Success(Unit)
        } catch (e: DropboxApiException) {
            DropboxResult.Failure(e.toDropboxError())
        }
    }

    private suspend fun <T> executeWithRefresh(
        block: suspend (accessToken: String) -> T
    ): DropboxResult<T> {
        val token = authManager.getAccessToken()
            ?: return DropboxResult.Failure(DropboxError.TokenExpired)
        return try {
            DropboxResult.Success(block(token))
        } catch (e: DropboxApiException.Unauthorized) {
            when (val refreshResult = refreshToken()) {
                is DropboxResult.Success -> {
                    val newToken = authManager.getAccessToken()
                        ?: return DropboxResult.Failure(DropboxError.TokenExpired)
                    try {
                        DropboxResult.Success(block(newToken))
                    } catch (e2: DropboxApiException) {
                        DropboxResult.Failure(e2.toDropboxError())
                    }
                }
                is DropboxResult.Failure -> refreshResult
            }
        } catch (e: DropboxApiException) {
            DropboxResult.Failure(e.toDropboxError())
        }
    }
}

internal fun DropboxApiException.toDropboxError(): DropboxError = when (this) {
    is DropboxApiException.Unauthorized -> DropboxError.TokenExpired
    is DropboxApiException.InsufficientStorage -> DropboxError.StorageFull
    is DropboxApiException.RateLimited -> DropboxError.RateLimit
    is DropboxApiException.NoNetwork -> DropboxError.NoInternet
    is DropboxApiException.ServerError -> DropboxError.Unknown(message ?: "Unknown error")
}
