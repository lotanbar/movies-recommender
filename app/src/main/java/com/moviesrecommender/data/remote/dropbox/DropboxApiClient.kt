package com.moviesrecommender.data.remote.dropbox

data class DropboxFolder(val name: String, val pathDisplay: String)
data class DropboxFile(val name: String, val pathDisplay: String)
data class DropboxEntries(val folders: List<DropboxFolder>, val files: List<DropboxFile>)

interface DropboxApiClient {
    suspend fun download(path: String, accessToken: String): String
    suspend fun upload(path: String, content: String, accessToken: String)
    suspend fun listEntries(path: String, accessToken: String): DropboxEntries
    suspend fun exchangeCode(code: String, codeVerifier: String, appKey: String): Pair<String, String>
    suspend fun refreshToken(refreshToken: String, appKey: String): String
}

sealed class DropboxApiException(message: String) : Exception(message) {
    class Unauthorized : DropboxApiException("Unauthorized")
    class NotFound : DropboxApiException("File not found")
    class InsufficientStorage : DropboxApiException("Storage full")
    class RateLimited : DropboxApiException("Rate limited")
    class NoNetwork : DropboxApiException("No network")
    class ServerError(message: String) : DropboxApiException(message)
}
