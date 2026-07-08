package com.moviesrecommender.data.remote.anthropic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        .readTimeout(180, TimeUnit.SECONDS)
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
        val messages = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        executeConversation(apiKey, modelId, system, messages)
    }

    override suspend fun sendCachedMessage(
        apiKey: String,
        modelId: String,
        cachedContent: String,
        instruction: String,
        system: String?
    ): String = withContext(Dispatchers.IO) {
        val systemBlocks = system?.let {
            org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", it)
                    put("cache_control", JSONObject().apply { put("type", "ephemeral") })
                })
            }
        }

        val contentBlocks = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", cachedContent)
                put("cache_control", JSONObject().apply { put("type", "ephemeral") })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", instruction)
            })
        }

        val messages = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", contentBlocks)
            })
        }

        // "medium" effort trims Sonnet's default (high) thinking depth — faster,
        // cheaper, and tends to make fewer/more-consolidated tool calls too.
        executeConversation(apiKey, modelId, systemBlocks, messages, effort = "medium")
    }

    private suspend fun executeConversation(
        apiKey: String,
        modelId: String,
        system: Any?,
        messages: org.json.JSONArray,
        effort: String? = null
    ): String {
        var response = requestWithWebSearch(apiKey, modelId, system, messages, effort)
        var content = response.getJSONArray("content")
        var inputTokens = 0L
        var outputTokens = 0L
        var cacheWriteTokens = 0L
        var cacheReadTokens = 0L
        fun accumulateUsage(r: JSONObject) {
            val usage = r.optJSONObject("usage") ?: return
            inputTokens += usage.optLong("input_tokens")
            outputTokens += usage.optLong("output_tokens")
            cacheWriteTokens += usage.optLong("cache_creation_input_tokens")
            cacheReadTokens += usage.optLong("cache_read_input_tokens")
        }
        accumulateUsage(response)

        // Server-side tool loop (web_search) hits its iteration cap: resume by
        // re-sending the assistant turn so far, since the API detects the
        // trailing server_tool_use block and continues automatically.
        var continuations = 0
        while (response.optString("stop_reason") == "pause_turn" && continuations < 5) {
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", content)
            })
            response = requestWithWebSearch(apiKey, modelId, system, messages, effort)
            content = response.getJSONArray("content")
            accumulateUsage(response)
            continuations++
        }

        logUsageAndCost(modelId, inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens)

        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.getString("type") == "text") text.append(block.getString("text"))
        }
        if (text.isEmpty()) throw AnthropicApiException.ServerError("No text block in response")
        return text.toString()
    }

    /** Per-MTok pricing, current intro rates where applicable (see platform.claude.com/docs/en/pricing). */
    private fun logUsageAndCost(
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheWriteTokens: Long,
        cacheReadTokens: Long
    ) {
        val (inputRate, outputRate) = when {
            modelId.contains("haiku") -> 1.00 to 5.00
            else -> 2.00 to 10.00 // Sonnet 5 intro pricing
        }
        val cost = (inputTokens * inputRate +
            outputTokens * outputRate +
            cacheWriteTokens * inputRate * 1.25 +
            cacheReadTokens * inputRate * 0.1) / 1_000_000.0
        Log.d(
            "AnthropicUsage",
            "model=$modelId input=$inputTokens output=$outputTokens cacheWrite=$cacheWriteTokens cacheRead=$cacheReadTokens cost=$${"%.4f".format(cost)}"
        )
    }

    private suspend fun requestWithWebSearch(
        apiKey: String,
        modelId: String,
        system: Any?,
        messages: org.json.JSONArray,
        effort: String? = null
    ): JSONObject {
        val bodyJson = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            if (system != null) put("system", system)
            if (effort != null) put("output_config", JSONObject().apply { put("effort", effort) })
            put("messages", messages)
            put("tools", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "web_search_20260209")
                    put("name", "web_search")
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(bodyJson.toRequestBody(json))
            .build()

        return JSONObject(execute(apiKey, request))
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

    private suspend fun execute(apiKey: String, request: Request, retryCount: Int = 0): String {
        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            when {
                response.code in 200..299 -> body
                response.code == 401 -> {
                    Log.e("Anthropic", "401 Unauthorized — body: $body")
                    throw AnthropicApiException.Unauthorized()
                }
                response.code == 429 -> {
                    if (retryCount < 3) {
                        delay((1L shl retryCount) * 1000L) // 1s, 2s, 4s
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
