package com.moviesrecommender.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.local.ListEntryParser
import com.moviesrecommender.data.remote.anthropic.AnthropicError
import com.moviesrecommender.data.remote.anthropic.AnthropicResult
import com.moviesrecommender.data.remote.dropbox.DropboxError
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.data.remote.tmdb.TmdbResult
import com.moviesrecommender.util.ToastManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class RecommendUiState {
    object FetchingList : RecommendUiState()
    data class Loading(val attempt: Int = 1) : RecommendUiState()
    data class Error(val message: String, val debugInfo: String? = null) : RecommendUiState()
}

class RecommendViewModel : ViewModel() {

    private val app = MoviesRecommenderApp.instance

    private val _uiState = MutableStateFlow<RecommendUiState>(RecommendUiState.Loading())
    val uiState: StateFlow<RecommendUiState> = _uiState.asStateFlow()

    private val _navigateToPreview = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 1)
    val navigateToPreview: SharedFlow<Pair<Int, String>> = _navigateToPreview.asSharedFlow()

    private var hasNavigatedToPreview = false
    private var batchListContent: String? = null
    private var batchJob: Job? = null

    init {
        app.recommendSkippedTitles.clear()
        app.recommendQueue = emptyList()
        app.recommendQueueIndex = 0
        startBatch(1)
    }

    fun startBatch(attempt: Int = 1) {
        batchJob = viewModelScope.launch {
            _uiState.value = RecommendUiState.Loading(attempt)
            if (attempt == 1) {
                app.recommendQueue = emptyList()
                app.recommendQueueIndex = 0
                batchListContent = null
                _uiState.value = RecommendUiState.FetchingList
            }

            val listContent = batchListContent
                ?: when (val r = app.dropboxService.downloadList()) {
                    is DropboxResult.Success -> r.value.also {
                        batchListContent = it
                        app.cachedListContent = it
                    }
                    is DropboxResult.Failure -> {
                        _uiState.value = RecommendUiState.Error(r.error.toMessage())
                        return@launch
                    }
                }

            _uiState.value = RecommendUiState.Loading(attempt)

            Log.d("Recommend", "listContent length=${listContent.length}")

            val count = app.anthropicService.authManager.getRecommendCount()
            val titlesClause = if (app.recommendSpec.isNotBlank()) "titles about: ${app.recommendSpec.trim()}" else "titles"
            val avoidClause = if (app.recommendSkippedTitles.isNotEmpty()) {
                "\n\nDo not recommend any of these — already shown or skipped this session:\n" +
                    app.recommendSkippedTitles.joinToString("\n") { "- $it" }
            } else ""
            val prompt = "recommend $count $titlesClause$avoidClause"
            val result = app.anthropicService.sendPrompt(prompt, listContent)
            if (result is AnthropicResult.Failure) {
                _uiState.value = RecommendUiState.Error(result.error.toMessage())
                return@launch
            }
            val response = (result as AnthropicResult.Success).value
            Log.d("Recommend", "Response: [$response]")

            val candidates = parseRecommendTitles(response)
                .filter { (_, y) -> y >= 1990 }
                .filterNot { (t, y) -> isTitleInRatedList(t, y, listContent) }
                .filterNot { (t, y) -> "$t ($y)" in app.recommendSkippedTitles }
                .distinctBy { it.first.lowercase() }

            Log.d("Recommend", "${candidates.size} valid candidates after filtering (attempt $attempt)")

            if (candidates.isEmpty()) {
                Log.d("Recommend", "No candidates, retrying (attempt ${attempt + 1})")
                startBatch(attempt + 1)
                return@launch
            }

            val tmdbResults = coroutineScope {
                candidates.map { (title, year) ->
                    async { app.tmdbService.fetchMetadata(title, year) }
                }.awaitAll()
            }

            val wishlistedIds = app.localStorageService.getStars().toSet()

            val afterTmdb = tmdbResults.zip(candidates).mapNotNull { (r, _) -> (r as? TmdbResult.Success)?.value }
            val failCount = tmdbResults.count { it is TmdbResult.Failure }
            Log.d("Recommend", "TMDB: ${afterTmdb.size} found, $failCount failed")

            val afterWishlist = afterTmdb.filterNot { it.id in wishlistedIds }
            Log.d("Recommend", "After wishlist filter: ${afterWishlist.size} (removed ${afterTmdb.size - afterWishlist.size})")

            val successes = afterWishlist.filter { it.runtime == null || it.runtime <= 150 }
            Log.d("Recommend", "After runtime filter: ${successes.size} (removed ${afterWishlist.size - successes.size})")
            afterWishlist.filter { it.runtime != null && it.runtime > 150 }.forEach {
                Log.d("Recommend", "  Runtime filtered: ${it.title} (${it.runtime}min)")
            }

            if (successes.isEmpty()) {
                Log.d("Recommend", "No TMDB successes, retrying (attempt ${attempt + 1})")
                startBatch(attempt + 1)
                return@launch
            }

            successes.forEach { app.cachedTitles[it.id] = it }
            app.recommendQueue = successes.map { Pair(it.id, it.mediaType.name) }
            app.recommendQueueIndex = 0
            // Remember all shown titles so next batch never re-suggests them
            successes.forEach { app.recommendSkippedTitles.add("${it.title} (${it.year})") }

            ToastManager.show("Claude returned ${candidates.size}, ${successes.size} kept after filtering")

            hasNavigatedToPreview = true
            _navigateToPreview.emit(app.recommendQueue[0])
        }
    }

    fun cancel() {
        batchJob?.cancel()
    }

    fun onScreenResumed() {
        if (hasNavigatedToPreview) {
            hasNavigatedToPreview = false
            startBatch()
        }
    }

    companion object {
private val TITLE_REGEX = Regex("""^(?:\d{1,2}[.)]\s+)?\[?(.+?)\s*\((\d{4})\)\]?\s*$""")

        fun parseRecommendTitles(response: String): List<Pair<String, Int>> =
            response.lines().mapNotNull { line ->
                val clean = line.trim().replace(Regex("""[*_]+"""), "").trimStart('[').trimEnd(']')
                val match = TITLE_REGEX.matchEntire(clean) ?: return@mapNotNull null
                val title = match.groupValues[1].trim()
                val year = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Pair(title, year)
            }

        fun isTitleInRatedList(title: String, year: Int?, listContent: String): Boolean =
            ListEntryParser.parseSegments(listContent, title, year).isNotEmpty()

        fun buildRatedTitlesBlacklist(listContent: String): String {
            val titles = mutableListOf<String>()
            var currentTier = -1
            for (line in listContent.lines()) {
                val trimmed = line.trim()
                val tier = ListEntryParser.headerTier(trimmed)
                if (tier != null) {
                    currentTier = tier
                } else if (currentTier in ListEntryParser.TIERS && trimmed.startsWith("-")) {
                    titles.add(trimmed.trimStart('-', ' ').trim())
                }
            }
            return titles.joinToString("\n")
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

private fun DropboxError.toMessage(): String = when (this) {
    DropboxError.NoInternet -> "Download failed: No internet connection."
    DropboxError.TokenExpired -> "Dropbox session expired - please re-authenticate."
    DropboxError.FileNotFound -> "List file not found. Please update the path in Setup."
    DropboxError.StorageFull -> "Dropbox storage is full."
    DropboxError.RateLimit -> "Too many requests. Try again shortly."
    is DropboxError.Unknown -> "Download failed: $message"
}
