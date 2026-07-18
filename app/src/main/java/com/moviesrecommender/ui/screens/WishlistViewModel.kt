package com.moviesrecommender.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.local.ListEntryParser
import com.moviesrecommender.data.remote.anthropic.AnthropicError
import com.moviesrecommender.data.remote.anthropic.AnthropicResult
import com.moviesrecommender.data.remote.dropbox.DropboxError
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.data.remote.tmdb.MediaType
import com.moviesrecommender.data.remote.tmdb.Title
import com.moviesrecommender.data.remote.tmdb.TmdbResult
import com.moviesrecommender.util.ToastManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WishlistItem(
    val title: Title,
    val rating: Int?
)

sealed class WishlistUiState {
    object Loading : WishlistUiState()
    data class Loaded(
        val items: List<WishlistItem>,
        val conflicts: List<WishlistItem>
    ) : WishlistUiState()
    data class Error(val message: String) : WishlistUiState()
}

class WishlistViewModel : ViewModel() {

    private val app = MoviesRecommenderApp.instance
    private val tmdbService = app.tmdbService
    private val dropboxService = app.dropboxService
    private val localStorage = app.localStorageService

    private val _uiState = MutableStateFlow<WishlistUiState>(WishlistUiState.Loading)
    val uiState: StateFlow<WishlistUiState> = _uiState.asStateFlow()

    private val _pendingRemovalId = MutableStateFlow<Int?>(null)
    val pendingRemovalId: StateFlow<Int?> = _pendingRemovalId.asStateFlow()

    private var listContent: String? = null

    private val _assessingId = MutableStateFlow<Int?>(null)
    val assessingId: StateFlow<Int?> = _assessingId.asStateFlow()

    private val _assessedTiers = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val assessedTiers: StateFlow<Map<Int, Int>> = _assessedTiers.asStateFlow()

    private var assessJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() = coroutineScope {
        _uiState.value = WishlistUiState.Loading

        val starredEntities = localStorage.getStarsWithType()
        if (starredEntities.isEmpty()) {
            _uiState.value = WishlistUiState.Loaded(emptyList(), emptyList())
            return@coroutineScope
        }

        // Download list and fetch TMDB details concurrently (was sequential before, doubling load time).
        val listDeferred = async {
            val listResult = dropboxService.downloadList()
            if (listResult is DropboxResult.Success) {
                app.cachedListContent = listResult.value
                listResult.value
            } else {
                app.cachedListContent
            }
        }

        // Reuse titles already fetched elsewhere in the app (e.g. Recommend/Preview) instead of
        // re-fetching full TMDB details — including credits/videos/images — on every visit.
        val titlesDeferred = starredEntities.map { entity ->
            async {
                app.cachedTitles[entity.tmdbId] ?: run {
                    val mediaType = if (entity.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    when (val result = tmdbService.fetchDetails(entity.tmdbId, mediaType)) {
                        is TmdbResult.Success -> result.value.also { app.cachedTitles[entity.tmdbId] = it }
                        is TmdbResult.Failure -> null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        val downloadedListContent = listDeferred.await()
        listContent = downloadedListContent
        val items = titlesDeferred.map { title ->
            WishlistItem(title, downloadedListContent?.let { SearchViewModel.parseRating(it, title.title, title.year) })
        }

        val conflicts = items.filter { it.rating != null }
        _uiState.value = WishlistUiState.Loaded(items, conflicts)
    }

    fun onLongPressAssess(tmdbId: Int, title: String, year: Int, currentRating: Int?) {
        if (currentRating != null) {
            ToastManager.show("Already rated as ${ListEntryParser.tierLabel(currentRating)} — no need to assess.")
            return
        }
        if (_assessingId.value != null) return
        _assessingId.value = tmdbId
        assessJob = viewModelScope.launch {
            val content = listContent ?: app.cachedListContent ?: run {
                when (val r = dropboxService.downloadList()) {
                    is DropboxResult.Success -> r.value.also { listContent = it; app.cachedListContent = it }
                    is DropboxResult.Failure -> {
                        _assessingId.value = null
                        ToastManager.show(r.error.toMessage())
                        return@launch
                    }
                }
            }
            val result = app.anthropicService.sendPrompt("$title ($year) assess", content)
            _assessingId.value = null
            when (result) {
                is AnthropicResult.Success -> ListEntryParser.parseAssessTier(result.value)?.let {
                    _assessedTiers.value = _assessedTiers.value + (tmdbId to it)
                }
                is AnthropicResult.Failure -> ToastManager.show(result.error.toMessage())
            }
        }
    }

    fun cancelAssess() {
        assessJob?.cancel()
        _assessingId.value = null
    }

    fun onStarTap(tmdbId: Int) {
        val pending = _pendingRemovalId.value
        if (pending == tmdbId) {
            // Second tap — remove from wishlist
            _pendingRemovalId.value = null
            viewModelScope.launch {
                localStorage.removeStar(tmdbId)
                val current = _uiState.value as? WishlistUiState.Loaded ?: return@launch
                val updatedItems = current.items.filter { it.title.id != tmdbId }
                val updatedConflicts = current.conflicts.filter { it.title.id != tmdbId }
                _uiState.value = current.copy(items = updatedItems, conflicts = updatedConflicts)
            }
        } else {
            _pendingRemovalId.value = tmdbId
            ToastManager.show("Tap again to remove from wishlist.")
        }
    }

    fun onConflictBadgeTap() {
        val loaded = _uiState.value as? WishlistUiState.Loaded ?: return
        loaded.conflicts.forEach { item ->
            ToastManager.show("${item.title.title} is already in your list.")
        }
    }
}

private fun DropboxError.toMessage(): String = when (this) {
    DropboxError.NoInternet -> "Download failed: No internet connection."
    DropboxError.TokenExpired -> "Dropbox session expired - please re-authenticate."
    DropboxError.FileNotFound -> "List file not found. Please update the path in Setup."
    DropboxError.StorageFull -> "Dropbox storage is full."
    DropboxError.RateLimit -> "Too many requests. Try again shortly."
    is DropboxError.Unknown -> "Download failed: $message"
}

private fun AnthropicError.toMessage(): String = when (this) {
    AnthropicError.ApiKeyMissing -> "Claude API key not configured. Please go to Setup."
    AnthropicError.ModelNotSelected -> "Claude model not selected. Please go to Setup."
    AnthropicError.InvalidApiKey -> "Invalid Claude API key. Please go to Setup."
    AnthropicError.NoInternet -> "Claude request failed: No internet connection."
    AnthropicError.NoSonnetModelFound -> "No Claude model found. Please go to Setup."
    is AnthropicError.ApiError -> "Claude request failed: $message"
}
