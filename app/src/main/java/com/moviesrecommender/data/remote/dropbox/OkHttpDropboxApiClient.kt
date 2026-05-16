package com.moviesrecommender.data.remote.dropbox

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import org.json.JSONObject
import java.net.UnknownHostException

class OkHttpDropboxApiClient(
    private val httpClient: OkHttpClient = OkHttpClient()
) : DropboxApiClient {

    private val octetStream = "application/octet-stream".toMediaType()
    private val formEncoded = "application/x-www-form-urlencoded".toMediaType()
    private val json = "application/json".toMediaType()

    override suspend fun download(path: String, accessToken: String): String =
        withContext(Dispatchers.IO) {
            val arg = """{"path":"${path.toAsciiJson()}"}"""
            val request = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/download")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Dropbox-API-Arg", arg)
                .post("".toRequestBody(octetStream))
                .build()
            execute(request)
        }

    override suspend fun listEntries(path: String, accessToken: String): DropboxEntries =
        withContext(Dispatchers.IO) {
            val body = """{"path":"${path.toAsciiJson()}","recursive":false,"include_non_downloadable_files":false}"""
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/2/files/list_folder")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(body.toRequestBody(json))
                .build()
            val entries = JSONObject(execute(request)).getJSONArray("entries")
            val objs = (0 until entries.length()).map { entries.getJSONObject(it) }
            DropboxEntries(
                folders = objs.filter { it.getString(".tag") == "folder" }
                    .map { DropboxFolder(it.getString("name"), it.getString("path_display")) }
                    .sortedBy { it.name.lowercase() },
                files = objs.filter { it.getString(".tag") == "file" && it.getString("name").endsWith(".txt") }
                    .map { DropboxFile(it.getString("name"), it.getString("path_display")) }
                    .sortedBy { it.name.lowercase() }
            )
        }

    override suspend fun upload(path: String, content: String, accessToken: String) =
        withContext(Dispatchers.IO) {
            val arg = """{"path":"${path.toAsciiJson()}","mode":"overwrite","autorename":false}"""
            val request = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/upload")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Dropbox-API-Arg", arg)
                .post(content.toByteArray(Charsets.UTF_8).toRequestBody(octetStream))
                .build()
            execute(request)
            Unit
        }

    override suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        appKey: String
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val body = "code=${enc(code)}" +
            "&grant_type=authorization_code" +
            "&code_verifier=${enc(codeVerifier)}" +
            "&client_id=${enc(appKey)}" +
            "&redirect_uri=${enc(DropboxAuthManager.REDIRECT_URI)}"
        val request = Request.Builder()
            .url("https://api.dropboxapi.com/oauth2/token")
            .post(body.toRequestBody(formEncoded))
            .build()
        val json = JSONObject(execute(request))
        Pair(json.getString("access_token"), json.getString("refresh_token"))
    }

    override suspend fun refreshToken(refreshToken: String, appKey: String): String =
        withContext(Dispatchers.IO) {
            val body = "refresh_token=$refreshToken&grant_type=refresh_token&client_id=$appKey"
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/oauth2/token")
                .post(body.toRequestBody(formEncoded))
                .build()
            JSONObject(execute(request)).getString("access_token")
        }

    private fun String.toAsciiJson(): String = buildString {
        for (ch in this@toAsciiJson) {
            when {
                ch == '"'  -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch.code > 127 -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }

    private fun execute(request: Request): String {
        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            when {
                response.code in 200..299 -> body
                response.code == 401 -> throw DropboxApiException.Unauthorized()
                response.code == 409 -> {
                    val summary = try { org.json.JSONObject(body).optString("error_summary", "") } catch (_: Exception) { "" }
                    if (summary.startsWith("path/not_found")) throw DropboxApiException.NotFound()
                    else throw DropboxApiException.ServerError("HTTP 409: $body")
                }
                response.code == 429 -> throw DropboxApiException.RateLimited()
                response.code == 507 -> throw DropboxApiException.InsufficientStorage()
                else -> throw DropboxApiException.ServerError("HTTP ${response.code}: $body")
            }
        } catch (e: DropboxApiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw DropboxApiException.NoNetwork()
        } catch (e: IOException) {
            throw DropboxApiException.NoNetwork()
        }
    }
}
