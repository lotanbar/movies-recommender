package com.moviesrecommender.data.remote.anthropic

class AnthropicService(
    val authManager: AnthropicAuthManager,
    private val apiClient: AnthropicApiClient
) {
    fun isConfigured(): Boolean = authManager.isConfigured()
    fun getStoredApiKey(): String? = authManager.getApiKey()
    fun getStoredModelId(): String? = authManager.getModelId()

    suspend fun saveApiKeyAndSelectModel(key: String): AnthropicResult<String> {
        return try {
            val models = apiClient.fetchModels(key)
            val latestSonnet = models
                .filter { it.id.contains("sonnet", ignoreCase = true) }
                .maxByOrNull { it.createdAt }
                ?: return AnthropicResult.Failure(AnthropicError.NoSonnetModelFound)
            authManager.saveApiKey(key)
            authManager.saveModelId(latestSonnet.id)
            AnthropicResult.Success(latestSonnet.id)
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }

    /** Send a multi-turn conversation, with optional system prompt. Pass modelOverride to use a different model than stored. */
    suspend fun sendMessages(messages: List<Pair<String, String>>, system: String? = null, modelOverride: String? = null): AnthropicResult<String> {
        val apiKey = authManager.getApiKey()
            ?: return AnthropicResult.Failure(AnthropicError.ApiKeyMissing)
        val modelId = modelOverride ?: authManager.getModelId()
            ?: return AnthropicResult.Failure(AnthropicError.ModelNotSelected)
        return try {
            AnthropicResult.Success(apiClient.sendMessages(apiKey, modelId, messages, system).trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }

    /** Send a pre-built user message, with optional system prompt. */
    suspend fun sendRawMessage(prompt: String, system: String? = null): AnthropicResult<String> {
        val apiKey = authManager.getApiKey()
            ?: return AnthropicResult.Failure(AnthropicError.ApiKeyMissing)
        val modelId = authManager.getModelId()
            ?: return AnthropicResult.Failure(AnthropicError.ModelNotSelected)
        return try {
            AnthropicResult.Success(apiClient.sendMessage(apiKey, modelId, prompt, system).trim())
        } catch (e: AnthropicApiException) {
            AnthropicResult.Failure(e.toAnthropicError())
        }
    }

    suspend fun sendPrompt(mode: String, listContent: String): AnthropicResult<String> {
        val apiKey = authManager.getApiKey()
            ?: return AnthropicResult.Failure(AnthropicError.ApiKeyMissing)
        val modelId = authManager.getModelId()
            ?: return AnthropicResult.Failure(AnthropicError.ModelNotSelected)
        return try {
            val response = apiClient.sendMessage(apiKey, modelId, "$listContent\n\n$mode")
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
