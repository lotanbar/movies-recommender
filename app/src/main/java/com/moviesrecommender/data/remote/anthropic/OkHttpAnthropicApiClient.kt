package com.moviesrecommender.data.remote.anthropic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpAnthropicApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : AnthropicApiClient {

    companion object {
        // Wall-clock ceiling across a full sendCachedMessage call, including any
        // pause_turn continuations — a safety net against a genuinely stuck
        // connection, not a bound on how long Claude is allowed to work.
        private const val OVERALL_CONVERSATION_TIMEOUT_MS = 10 * 60 * 1000L
    }

    // Streaming reads arrive incrementally (deltas + periodic pings), so the per-chunk
    // gap only needs to cover the pause between events, not the whole generation.
    private val streamingHttpClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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
        system: String?,
        effort: String,
        onUsage: ((UsageStats) -> Unit)?
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

        executeConversation(apiKey, modelId, systemBlocks, messages, effort = effort, onUsage = onUsage)
    }

    private suspend fun executeConversation(
        apiKey: String,
        modelId: String,
        system: Any?,
        messages: org.json.JSONArray,
        effort: String? = null,
        onUsage: ((UsageStats) -> Unit)? = null
    ): String {
        val startedAt = System.currentTimeMillis()
        try {
            return withTimeout(OVERALL_CONVERSATION_TIMEOUT_MS) {
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
                // trailing server_tool_use block and continues automatically. Each
                // request streams (see requestWithWebSearch); the timeout above is the
                // wall-clock ceiling across all continuations, not any single request.
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

                val durationMs = System.currentTimeMillis() - startedAt
                val cost = costUsd(modelId, inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens)
                logUsage(modelId, inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, cost)
                onUsage?.invoke(UsageStats(inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, cost, durationMs))

                val text = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.getString("type") == "text") text.append(block.getString("text"))
                }
                if (text.isEmpty()) throw AnthropicApiException.ServerError("No text block in response")
                text.toString()
            }
        } catch (e: TimeoutCancellationException) {
            throw AnthropicApiException.ServerError(
                "Request timed out after ${OVERALL_CONVERSATION_TIMEOUT_MS / 60_000} minutes — Claude took too long to respond."
            )
        }
    }

    /** Per-MTok pricing, current intro rates where applicable (see platform.claude.com/docs/en/pricing). */
    private fun costUsd(
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheWriteTokens: Long,
        cacheReadTokens: Long
    ): Double {
        val (inputRate, outputRate) = when {
            modelId.contains("haiku") -> 1.00 to 5.00
            else -> 2.00 to 10.00 // Sonnet 5 intro pricing
        }
        return (inputTokens * inputRate +
            outputTokens * outputRate +
            cacheWriteTokens * inputRate * 1.25 +
            cacheReadTokens * inputRate * 0.1) / 1_000_000.0
    }

    private fun logUsage(
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheWriteTokens: Long,
        cacheReadTokens: Long,
        cost: Double
    ) {
        Log.d(
            "AnthropicUsage",
            "model=$modelId input=$inputTokens output=$outputTokens cacheWrite=$cacheWriteTokens cacheRead=$cacheReadTokens cost=$${"%.4f".format(cost)}"
        )
    }

    /** Streams the response (see [streamOnce]) and retries on 429 the same way [execute] does. */
    private suspend fun requestWithWebSearch(
        apiKey: String,
        modelId: String,
        system: Any?,
        messages: org.json.JSONArray,
        effort: String? = null,
        retryCount: Int = 0
    ): JSONObject {
        val bodyJson = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            put("stream", true)
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

        return try {
            streamOnce(request)
        } catch (e: RateLimitSignal) {
            if (retryCount < 3) {
                delay((1L shl retryCount) * 1000L) // 1s, 2s, 4s
                requestWithWebSearch(apiKey, modelId, system, messages, effort, retryCount + 1)
            } else {
                throw AnthropicApiException.ServerError("Rate limit exceeded — please try again shortly.")
            }
        }
    }

    /** Internal signal used to trigger the 429 retry from inside [streamOnce]'s callback. */
    private class RateLimitSignal : Exception()

    /**
     * Opens an SSE connection and reconstructs the equivalent non-streaming response shape
     * (`content` array, `stop_reason`, `usage`) by accumulating `content_block_delta` events.
     * Text deltas concatenate into the block's `text`; tool-input deltas (`partial_json`)
     * concatenate and get parsed back into the block's `input` on `content_block_stop`, so the
     * reconstructed content — including tool_use/server_tool_use/web_search_tool_result blocks —
     * round-trips correctly when echoed back as the assistant turn in the pause_turn loop.
     */
    private suspend fun streamOnce(request: Request): JSONObject = suspendCancellableCoroutine { cont ->
        var usage = JSONObject()
        var stopReason: String? = null
        val blockTemplates = LinkedHashMap<Int, JSONObject>()
        val textBuffers = HashMap<Int, StringBuilder>()
        val jsonBuffers = HashMap<Int, StringBuilder>()

        fun finish() {
            if (!cont.isActive) return
            val contentArray = org.json.JSONArray()
            for (index in blockTemplates.keys.sorted()) contentArray.put(blockTemplates.getValue(index))
            val result = JSONObject().apply {
                put("content", contentArray)
                put("stop_reason", stopReason)
                put("usage", usage)
            }
            cont.resume(result)
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (!cont.isActive) return
                val obj = try { JSONObject(data) } catch (e: Exception) { return }
                when (obj.optString("type")) {
                    "message_start" -> {
                        obj.optJSONObject("message")?.optJSONObject("usage")?.let { usage = it }
                    }
                    "content_block_start" -> {
                        val index = obj.getInt("index")
                        blockTemplates[index] = JSONObject(obj.getJSONObject("content_block").toString())
                    }
                    "content_block_delta" -> {
                        val index = obj.getInt("index")
                        val delta = obj.getJSONObject("delta")
                        when (delta.optString("type")) {
                            "text_delta" -> textBuffers.getOrPut(index) { StringBuilder() }.append(delta.optString("text"))
                            "input_json_delta" -> jsonBuffers.getOrPut(index) { StringBuilder() }.append(delta.optString("partial_json"))
                        }
                    }
                    "content_block_stop" -> {
                        val index = obj.getInt("index")
                        val template = blockTemplates[index] ?: JSONObject().also { blockTemplates[index] = it }
                        textBuffers[index]?.let { template.put("text", it.toString()) }
                        jsonBuffers[index]?.takeIf { it.isNotEmpty() }?.let {
                            try { template.put("input", JSONObject(it.toString())) } catch (e: Exception) { /* leave as-is */ }
                        }
                    }
                    "message_delta" -> {
                        obj.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() }?.let { stopReason = it }
                        obj.optJSONObject("usage")?.let { deltaUsage ->
                            deltaUsage.keys().forEach { key -> usage.put(key, deltaUsage.get(key)) }
                        }
                    }
                    "error" -> {
                        val message = obj.optJSONObject("error")?.optString("message") ?: "Stream error"
                        cont.resumeWithException(AnthropicApiException.ServerError(message))
                    }
                    // "message_stop" and "ping" carry no data we need; content finalizes in onClosed.
                }
            }

            override fun onClosed(eventSource: EventSource) = finish()

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!cont.isActive) return
                val code = response?.code
                when {
                    code == 401 -> cont.resumeWithException(AnthropicApiException.Unauthorized())
                    code == 429 -> cont.resumeWithException(RateLimitSignal())
                    code != null -> {
                        val bodySnippet = try { response.body?.string() } catch (e: Exception) { null }
                        cont.resumeWithException(AnthropicApiException.ServerError("HTTP $code: ${bodySnippet ?: t?.message ?: "request failed"}"))
                    }
                    t is SocketTimeoutException -> cont.resumeWithException(AnthropicApiException.ServerError("Request timed out — Claude took too long to respond."))
                    t is UnknownHostException -> cont.resumeWithException(AnthropicApiException.NoNetwork())
                    t is IOException -> cont.resumeWithException(AnthropicApiException.NoNetwork())
                    else -> cont.resumeWithException(AnthropicApiException.ServerError(t?.message ?: "Unknown streaming error"))
                }
            }
        }

        val eventSource = EventSources.createFactory(streamingHttpClient).newEventSource(request, listener)
        cont.invokeOnCancellation { eventSource.cancel() }
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
