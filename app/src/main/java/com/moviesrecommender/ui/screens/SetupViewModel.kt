package com.moviesrecommender.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.BuildConfig
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.util.ToastManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SetupViewModel : ViewModel() {

    private val app = MoviesRecommenderApp.instance
    private val dropboxService = app.dropboxService
    private val anthropicAuthManager = app.anthropicService.authManager

    private val _dropboxAuthenticated = MutableStateFlow(dropboxService.isAuthenticated())
    val dropboxAuthenticated = _dropboxAuthenticated.asStateFlow()

    private val _listPath = MutableStateFlow(dropboxService.authManager.getListPath())
    val listPath = _listPath.asStateFlow()

    private val _listPathSet = MutableStateFlow(dropboxService.authManager.getListPath() != null)
    val listPathSet = _listPathSet.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _useHaiku = MutableStateFlow(anthropicAuthManager.getUseHaiku())
    val useHaiku = _useHaiku.asStateFlow()

    private val _recommendCount = MutableStateFlow(anthropicAuthManager.getRecommendCount())
    val recommendCount = _recommendCount.asStateFlow()

    fun toggleUseHaiku(value: Boolean) {
        anthropicAuthManager.setUseHaiku(value)
        _useHaiku.value = value
    }

    fun setRecommendCount(value: Int) {
        val clamped = value.coerceIn(1, 50)
        anthropicAuthManager.setRecommendCount(clamped)
        _recommendCount.value = clamped
    }

    fun getDropboxAuthUrl(): String = dropboxService.getAuthUrl(BuildConfig.DROPBOX_APP_KEY)

    fun handleDropboxCallback(code: String) {
        viewModelScope.launch {
            when (val result = dropboxService.handleAuthCallback(code)) {
                is DropboxResult.Success -> _dropboxAuthenticated.value = true
                is DropboxResult.Failure -> ToastManager.show(
                    "Dropbox auth failed: ${result.error}"
                )
            }
        }
    }

    fun saveListPath(path: String) {
        dropboxService.authManager.setListPath(path.trim())
        _listPath.value = path.trim().ifBlank { null }
        _listPathSet.value = path.isNotBlank()
    }

    fun clearDropboxAuth() {
        dropboxService.authManager.clearTokens()
        dropboxService.authManager.clearListPath()
        _dropboxAuthenticated.value = false
        _listPath.value = null
        _listPathSet.value = false
    }

    fun clearListPath() {
        dropboxService.authManager.clearListPath()
        _listPath.value = null
        _listPathSet.value = false
    }

}
