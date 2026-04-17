package com.moviesrecommender.data.remote.anthropic

class AnthropicService(
    val authManager: AnthropicAuthManager,
    private val apiClient: AnthropicApiClient
) {
    companion object {
        private const val API_KEY = "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"
        private const val MODEL_SONNET = "claude-sonnet-4-6"
        private const val MODEL_HAIKU = "claude-haiku-4-5"
    }

    private fun effectiveModel(): String =
        if (authManager.getUseHaiku()) MODEL_HAIKU else MODEL_SONNET

    fun isConfigured(): Boolean = true

    /** Send a multi-turn conversation, with optional system prompt. Pass modelOverride to use a different model than stored. */
    suspend fun sendMessages(messages: List<Pair<String, String>>, system: String? = null, modelOverride: String? = null): AnthropicResult<String> {
        return try {
            AnthropicResult.Success(apiClient.sendMessages(API_KEY, modelOverride ?: effectiveModel(), messages, system).trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }

    /** Send a pre-built user message, with optional system prompt. */
    suspend fun sendRawMessage(prompt: String, system: String? = null): AnthropicResult<String> {
        return try {
            AnthropicResult.Success(apiClient.sendMessage(API_KEY, effectiveModel(), prompt, system).trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }

    suspend fun sendPrompt(mode: String, listContent: String): AnthropicResult<String> {
        return try {
            val response = apiClient.sendMessage(API_KEY, effectiveModel(), "$mode\n\n$listContent")
            AnthropicResult.Success(response.trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }
}

internal fun AnthropicApiException.toAnthropicError(): AnthropicError = when (this) {
    is AnthropicApiException.Unauthorized -> AnthropicError.InvalidApiKey
    is AnthropicApiException.NoNetwork -> AnthropicError.NoInternet
    is AnthropicApiException.ServerError -> AnthropicError.ApiError(message ?: "Unknown error")
}
