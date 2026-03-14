package com.moviesrecommender.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.moviesrecommender.data.remote.anthropic.AnthropicError
import com.moviesrecommender.data.remote.anthropic.AnthropicResult
import com.moviesrecommender.data.remote.dropbox.DropboxError
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.data.remote.tmdb.TmdbResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class RateUiState {
    object Loading : RateUiState()
    object InProgress : RateUiState()   // navigating between titles — RateScreen shows nothing
    object Uploading : RateUiState()
    data class Error(val message: String, val debugInfo: String? = null) : RateUiState()
}

class RateViewModel : ViewModel() {

    private val app = MoviesRecommenderApp.instance

    private val _uiState = MutableStateFlow<RateUiState>(RateUiState.Loading)
    val uiState: StateFlow<RateUiState> = _uiState.asStateFlow()

    private val _navigateToPreview = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 1)
    val navigateToPreview: SharedFlow<Pair<Int, String>> = _navigateToPreview.asSharedFlow()

    private val _navigateToActions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToActions: SharedFlow<Unit> = _navigateToActions.asSharedFlow()

    // Queue management lives in MoviesRecommenderApp (rateQueue/rateQueueIndex)
    // so PreviewViewModel can advance directly without routing through RateScreen.
    private var hasNavigatedToPreview = false
    private var isBusy = false

    init {
        app.rateMode = true
        startBatch()
    }

    override fun onCleared() {
        super.onCleared()
        if (app.rateMode && !isBusy) {
            app.rateMode = false
            app.cachedTitles.clear()
            val content = app.cachedListContent ?: return
            app.cachedListContent = null
            cleanupScope.launch { app.dropboxService.uploadList(content) }
        }
    }

    fun startBatch() {
        viewModelScope.launch {
            _uiState.value = RateUiState.Loading
            app.rateQueue = emptyList()
            app.rateQueueIndex = 0

            val listContent = app.cachedListContent
                ?: when (val r = app.dropboxService.downloadList()) {
                    is DropboxResult.Success -> r.value.also { app.cachedListContent = it }
                    is DropboxResult.Failure -> {
                        _uiState.value = RateUiState.Error(r.error.toDownloadMessage())
                        return@launch
                    }
                }

            Log.d("Rate", "listContent length=${listContent.length}")

            val result = app.anthropicService.sendPrompt("rate 10 titles", listContent)
            if (result is AnthropicResult.Failure) {
                _uiState.value = RateUiState.Error(result.error.toMessage())
                return@launch
            }
            val response = (result as AnthropicResult.Success).value
            Log.d("Rate", "Response: [$response]")

            val candidates = parseRateTitles(response)
                .filterNot { (t, _) -> isTitleInList(t, 0, listContent) }
                .distinctBy { it.first.lowercase() }
            Log.d("Rate", "${candidates.size} unique new candidates")

            if (candidates.isEmpty()) {
                _uiState.value = RateUiState.Error(
                    "Claude suggested no new titles.",
                    "Last response: [$response]"
                )
                return@launch
            }

            // TMDB lookup for all candidates in parallel; skip any that fail
            val tmdbResults = candidates.map { (title, year) ->
                async { app.tmdbService.fetchMetadata(title, year) }
            }.awaitAll()

            val successes = tmdbResults.zip(candidates)
                .mapNotNull { (r, _) -> (r as? TmdbResult.Success)?.value }
                .filter { it.trailerKeys.isNotEmpty() }
                .filter { it.runtime == null || it.runtime <= 150 }
            val failCount = tmdbResults.count { it is TmdbResult.Failure }
            if (failCount > 0) Log.w("Rate", "$failCount titles not found on TMDB, skipped")

            if (successes.isEmpty()) {
                _uiState.value = RateUiState.Error("None of the suggested titles were found on TMDB.")
                return@launch
            }

            app.cachedTitles.clear()
            successes.forEach { app.cachedTitles[it.id] = it }

            app.rateQueue = successes.map { Pair(it.id, it.mediaType.name) }
            app.rateQueueIndex = 0

            hasNavigatedToPreview = true
            _uiState.value = RateUiState.InProgress
            _navigateToPreview.emit(app.rateQueue[0])
        }
    }

    fun onResumedFromPreview() {
        if (!hasNavigatedToPreview) return
        hasNavigatedToPreview = false
        // Batch complete — start the next batch
        startBatch()
    }

    fun onBackPressed() {
        if (isBusy) return
        isBusy = true
        viewModelScope.launch {
            _uiState.value = RateUiState.Uploading
            val content = app.cachedListContent
            if (content != null) {
                when (val r = app.dropboxService.uploadList(content)) {
                    is DropboxResult.Failure -> {
                        _uiState.value = RateUiState.Error(r.error.toUploadMessage())
                        isBusy = false
                        return@launch
                    }
                    else -> {}
                }
            }
            app.rateMode = false
            app.cachedTitles.clear()
            app.cachedListContent = null
            isBusy = false
            _navigateToActions.emit(Unit)
        }
    }

    private fun uploadAndRepeat() {
        viewModelScope.launch {
            _uiState.value = RateUiState.Uploading
            val content = app.cachedListContent
            if (content != null) {
                when (val r = app.dropboxService.uploadList(content)) {
                    is DropboxResult.Failure -> {
                        _uiState.value = RateUiState.Error(r.error.toUploadMessage())
                        return@launch
                    }
                    else -> {}
                }
            }
            // Cache already matches what was just uploaded — reuse it for the next batch
            startBatch()
        }
    }

    companion object {
        // Outlives the ViewModel — used for cleanup uploads when the VM is cleared abruptly
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val NUMBERED_REGEX = Regex("""^\d{1,2}\.\s+(.+?)\s*\((\d{4})\)\s*$""")

        /** True if the title already appears anywhere in the list body (any rating, including 0). */
        fun isTitleInList(title: String, year: Int, listBody: String): Boolean {
            val titleLower = title.lowercase().trim()
            return listBody.lines().any { line ->
                val cleaned = line.trimStart('-', ' ').trim().lowercase()
                cleaned == titleLower || cleaned.startsWith("$titleLower (")
            }
        }

        fun parseRateTitles(response: String): List<Pair<String, Int>> =
            response.lines().mapNotNull { line ->
                val clean = line.trim().replace(Regex("""[*_]+"""), "")
                val match = NUMBERED_REGEX.matchEntire(clean) ?: return@mapNotNull null
                val title = match.groupValues[1].trim()
                val year = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Pair(title, year)
            }
    }
}

private fun AnthropicError.toMessage(): String = when (this) {
    AnthropicError.ApiKeyMissing -> "Claude API key not configured. Please go to Setup."
    AnthropicError.ModelNotSelected -> "Claude model not selected. Please go to Setup."
    AnthropicError.InvalidApiKey -> "Invalid Claude API key. Please go to Setup."
    AnthropicError.NoInternet -> "Claude request failed: No internet connection."
    AnthropicError.NoSonnetModelFound -> "No Claude model found. Please go to Setup."
    is AnthropicError.ApiError -> "Claude request failed: $message"
}

private fun DropboxError.toDownloadMessage(): String = when (this) {
    DropboxError.NoInternet -> "Download failed: No internet connection."
    DropboxError.TokenExpired -> "Dropbox session expired - please re-authenticate."
    DropboxError.StorageFull -> "Dropbox storage is full."
    DropboxError.RateLimit -> "Too many requests. Try again shortly."
    is DropboxError.Unknown -> "Download failed: $message"
}

private fun DropboxError.toUploadMessage(): String = when (this) {
    DropboxError.NoInternet -> "Upload failed: No internet connection."
    DropboxError.TokenExpired -> "Upload failed: Dropbox session expired - please re-authenticate."
    DropboxError.StorageFull -> "Upload failed: Dropbox storage is full."
    DropboxError.RateLimit -> "Upload failed: Too many requests. Try again shortly."
    is DropboxError.Unknown -> "Upload failed: $message"
}

