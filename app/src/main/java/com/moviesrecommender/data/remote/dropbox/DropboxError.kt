package com.moviesrecommender.data.remote.dropbox

sealed class DropboxError {
    data object NoInternet : DropboxError()
    data object TokenExpired : DropboxError()
    data object FileNotFound : DropboxError()
    data object StorageFull : DropboxError()
    data object RateLimit : DropboxError()
    data class Unknown(val message: String) : DropboxError()
}

sealed class DropboxResult<out T> {
    data class Success<out T>(val value: T) : DropboxResult<T>()
    data class Failure(val error: DropboxError) : DropboxResult<Nothing>()
}
