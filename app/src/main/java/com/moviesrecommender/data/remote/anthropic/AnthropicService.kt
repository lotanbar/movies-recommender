package com.moviesrecommender.data.remote.anthropic

import android.util.Log
import com.moviesrecommender.data.local.UsageStatsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AnthropicService(
    val authManager: AnthropicAuthManager,
    private val apiClient: AnthropicApiClient,
    private val recommendSystemPrompt: String,
    private val usageStatsService: UsageStatsService? = null
) {
    companion object {
        private const val MODEL_SONNET = "claude-sonnet-5"
        private const val MODEL_HAIKU = "claude-haiku-4-5-20251001"
    }

    private fun effectiveModel(): String =
        if (authManager.getUseHaiku()) MODEL_HAIKU else MODEL_SONNET

    private fun requireApiKey(): String =
        authManager.getApiKey()?.takeIf { it.isNotBlank() }
            ?: throw AnthropicApiException.Unauthorized()

    fun isConfigured(): Boolean = authManager.getApiKey()?.isNotBlank() == true

    /** Send a multi-turn conversation, with optional system prompt. Pass modelOverride to use a different model than stored. */
    suspend fun sendMessages(messages: List<Pair<String, String>>, system: String? = null, modelOverride: String? = null): AnthropicResult<String> {
        return try {
            AnthropicResult.Success(apiClient.sendMessages(requireApiKey(), modelOverride ?: effectiveModel(), messages, system).trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }

    /** Send a pre-built user message, with optional system prompt. */
    suspend fun sendRawMessage(prompt: String, system: String? = null): AnthropicResult<String> {
        return try {
            AnthropicResult.Success(apiClient.sendMessage(requireApiKey(), effectiveModel(), prompt, system).trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }

    /** [listContent] and the system prompt are cached (identical across retries); [mode] varies per request. */
    suspend fun sendPrompt(mode: String, listContent: String): AnthropicResult<String> {
        return try {
            val isAssess = mode.endsWith("assess")
            val effort = if (isAssess) "medium" else "high"
            val statsMode = if (isAssess) "assess" else "recommend"
            val response = apiClient.sendCachedMessage(
                requireApiKey(),
                effectiveModel(),
                listContent,
                mode,
                recommendSystemPrompt,
                effort
            ) { stats ->
                usageStatsService?.let { service ->
                    CoroutineScope(Dispatchers.IO).launch {
                        service.record(
                            statsMode,
                            stats.inputTokens,
                            stats.outputTokens,
                            stats.cacheWriteTokens,
                            stats.cacheReadTokens,
                            stats.costUsd,
                            stats.durationMs
                        )
                    }
                }
            }
            AnthropicResult.Success(response.trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }
}

internal fun AnthropicApiException.toAnthropicError(): AnthropicError = when (this) {
    is AnthropicApiException.Unauthorized -> AnthropicError.InvalidApiKey
    is AnthropicApiException.NoNetwork -> AnthropicError.NoInternet
    is AnthropicApiException.ServerError -> {
        Log.e("Anthropic", "API error: $message")
        AnthropicError.ApiError(message ?: "Unknown error")
    }
}
