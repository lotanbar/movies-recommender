package com.moviesrecommender.data.remote.anthropic

import com.moviesrecommender.data.remote.dropbox.FakeTokenStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicServiceTest {

    private lateinit var tokenStore: FakeTokenStore
    private lateinit var authManager: AnthropicAuthManager
    private lateinit var apiClient: FakeAnthropicApiClient
    private lateinit var service: AnthropicService

    @Before
    fun setUp() {
        tokenStore = FakeTokenStore()
        authManager = AnthropicAuthManager(tokenStore)
        apiClient = FakeAnthropicApiClient()
        service = AnthropicService(authManager, apiClient)
    }

    @Test
    fun `saveApiKeyAndSelectModel stores key and latest Sonnet model`() = runTest {
        apiClient.models = listOf(
            ModelInfo("claude-opus-4-5", "2025-09-15"),
            ModelInfo("claude-sonnet-4-5", "2025-09-15"),
            ModelInfo("claude-3-7-sonnet-20250219", "2025-02-19")
        )
        val result = service.saveApiKeyAndSelectModel("test-key")
        assertTrue(result is AnthropicResult.Success)
        assertEquals("claude-sonnet-4-5", (result as AnthropicResult.Success).value)
        assertEquals("test-key", authManager.getApiKey())
        assertEquals("claude-sonnet-4-5", authManager.getModelId())
    }

    @Test
    fun `saveApiKeyAndSelectModel picks latest Sonnet by createdAt`() = runTest {
        apiClient.models = listOf(
            ModelInfo("claude-3-7-sonnet-20250219", "2025-02-19"),
            ModelInfo("claude-sonnet-4-5", "2025-09-15")
        )
        val result = service.saveApiKeyAndSelectModel("key")
        assertEquals("claude-sonnet-4-5", (result as AnthropicResult.Success).value)
    }

    @Test
    fun `saveApiKeyAndSelectModel returns InvalidApiKey on 401`() = runTest {
        apiClient.fetchException = AnthropicApiException.Unauthorized()
        val result = service.saveApiKeyAndSelectModel("bad-key")
        assertTrue(result is AnthropicResult.Failure)
        assertEquals(AnthropicError.InvalidApiKey, (result as AnthropicResult.Failure).error)
    }

    @Test
    fun `saveApiKeyAndSelectModel returns NoSonnetModelFound when list has no Sonnet`() = runTest {
        apiClient.models = listOf(ModelInfo("claude-opus-4-5", "2025-09-15"))
        val result = service.saveApiKeyAndSelectModel("key")
        assertTrue(result is AnthropicResult.Failure)
        assertEquals(AnthropicError.NoSonnetModelFound, (result as AnthropicResult.Failure).error)
    }

    @Test
    fun `sendPrompt returns Claude text response`() = runTest {
        authManager.saveApiKey("key")
        authManager.saveModelId("claude-sonnet-4-5")
        apiClient.sendResponse = "Inception (2010)"
        val result = service.sendPrompt("recommend", "LIST CONTENT")
        assertTrue(result is AnthropicResult.Success)
        assertEquals("Inception (2010)", (result as AnthropicResult.Success).value)
    }

    @Test
    fun `sendPrompt returns ApiKeyMissing when no key stored`() = runTest {
        val result = service.sendPrompt("recommend", "LIST")
        assertTrue(result is AnthropicResult.Failure)
        assertEquals(AnthropicError.ApiKeyMissing, (result as AnthropicResult.Failure).error)
    }

    @Test
    fun `sendPrompt returns ApiError on server error`() = runTest {
        authManager.saveApiKey("key")
        authManager.saveModelId("claude-sonnet-4-5")
        apiClient.sendException = AnthropicApiException.ServerError("overloaded")
        val result = service.sendPrompt("rate", "LIST")
        assertTrue(result is AnthropicResult.Failure)
        assertTrue((result as AnthropicResult.Failure).error is AnthropicError.ApiError)
    }
}

// ─── Fakes ───────────────────────────────────────────────────────────────────

class FakeAnthropicApiClient : AnthropicApiClient {

    var models: List<ModelInfo> = emptyList()
    var fetchException: AnthropicApiException? = null

    var sendResponse: String = ""
    var sendException: AnthropicApiException? = null

    override suspend fun fetchModels(apiKey: String): List<ModelInfo> {
        fetchException?.let { throw it }
        return models
    }

    override suspend fun sendMessage(apiKey: String, modelId: String, prompt: String, system: String?): String {
        sendException?.let { throw it }
        return sendResponse
    }

    override suspend fun sendMessages(apiKey: String, modelId: String, messages: List<Pair<String, String>>, system: String?): String {
        sendException?.let { throw it }
        return sendResponse
    }
}
