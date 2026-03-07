package com.moviesrecommender.data.remote.anthropic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class OkHttpAnthropicApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : AnthropicApiClient {

    private val json = "application/json".toMediaType()

    override suspend fun fetchModels(apiKey: String): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .get()
                .build()
            val body = execute(apiKey, request)
            val data = JSONObject(body).getJSONArray("data")
            (0 until data.length()).map { i ->
                val obj = data.getJSONObject(i)
                ModelInfo(
                    id = obj.getString("id"),
                    createdAt = obj.optString("created_at", "")
                )
            }
        }

    override suspend fun sendMessage(
        apiKey: String,
        modelId: String,
        prompt: String,
        system: String?
    ): String = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 2048)
            if (system != null) put("system", system)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(bodyJson.toRequestBody(json))
            .build()

        val responseBody = execute(apiKey, request)
        val content = JSONObject(responseBody).getJSONArray("content")
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.getString("type") == "text") return@withContext block.getString("text")
        }
        throw AnthropicApiException.ServerError("No text block in response")
    }

    override suspend fun sendMessages(
        apiKey: String,
        modelId: String,
        messages: List<Pair<String, String>>,
        system: String?
    ): String = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 2048)
            if (system != null) put("system", system)
            put("messages", org.json.JSONArray().apply {
                messages.forEach { (role, content) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            })
        }.toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(bodyJson.toRequestBody(json))
            .build()

        val responseBody = execute(apiKey, request)
        val content = JSONObject(responseBody).getJSONArray("content")
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.getString("type") == "text") return@withContext block.getString("text")
        }
        throw AnthropicApiException.ServerError("No text block in response")
    }

    private fun execute(apiKey: String, request: Request, retryCount: Int = 0): String {
        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            when {
                response.code in 200..299 -> body
                response.code == 401 -> throw AnthropicApiException.Unauthorized()
                response.code == 429 -> {
                    if (retryCount < 3) {
                        Thread.sleep((1L shl retryCount) * 1000L) // 1s, 2s, 4s
                        execute(apiKey, request, retryCount + 1)
                    } else {
                        throw AnthropicApiException.ServerError("Rate limit exceeded — please try again shortly.")
                    }
                }
                else -> throw AnthropicApiException.ServerError("HTTP ${response.code}: $body")
            }
        } catch (e: AnthropicApiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AnthropicApiException.NoNetwork()
        } catch (e: SocketTimeoutException) {
            throw AnthropicApiException.ServerError("Request timed out — Claude took too long to respond.")
        } catch (e: IOException) {
            throw AnthropicApiException.NoNetwork()
        }
    }
}
