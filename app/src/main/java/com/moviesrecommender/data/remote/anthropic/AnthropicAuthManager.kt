package com.moviesrecommender.data.remote.anthropic

import com.moviesrecommender.data.remote.dropbox.TokenStore

class AnthropicAuthManager(private val store: TokenStore) {

    companion object {
        private const val KEY_API_KEY = "anthropic_api_key"
        private const val KEY_MODEL_ID = "anthropic_model_id"
        private const val KEY_USE_HAIKU = "anthropic_use_haiku"
    }

    fun saveApiKey(key: String) { store[KEY_API_KEY] = key }
    fun getApiKey(): String? = store[KEY_API_KEY]

    fun saveModelId(id: String) { store[KEY_MODEL_ID] = id }
    fun getModelId(): String? = store[KEY_MODEL_ID]

    fun getUseHaiku(): Boolean = store[KEY_USE_HAIKU] == "true"
    fun setUseHaiku(value: Boolean) { store[KEY_USE_HAIKU] = if (value) "true" else "false" }

    fun isConfigured(): Boolean = store[KEY_API_KEY] != null && store[KEY_MODEL_ID] != null

    fun clearCredentials() {
        store[KEY_API_KEY] = null
        store[KEY_MODEL_ID] = null
    }
}
