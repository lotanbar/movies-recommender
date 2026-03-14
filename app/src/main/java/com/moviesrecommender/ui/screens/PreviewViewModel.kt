package com.moviesrecommender.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.data.remote.tmdb.MediaType
import com.moviesrecommender.data.remote.tmdb.Title
import com.moviesrecommender.data.remote.tmdb.TmdbResult
import com.moviesrecommender.data.remote.wikidata.Award
import com.moviesrecommender.util.ToastManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PreviewUiState {
    object Loading : PreviewUiState()
    data class Loaded(
        val title: Title,
        val rating: Int?,
        val isStarred: Boolean = false,
        val wikipediaUrl: String? = null,
        val wikipediaReady: Boolean = false,
        val awards: List<Award> = emptyList(),
        val awardsReady: Boolean = false,
        val isUploading: Boolean = false,
        val uploadError: Boolean = false
    ) : PreviewUiState()
    data class Error(val message: String) : PreviewUiState()
}

class PreviewViewModel(
    private val tmdbId: Int,
    private val mediaTypeStr: String,
    private val source: String = "search"
) : ViewModel() {

    private val app = MoviesRecommenderApp.instance
    private val tmdbService = app.tmdbService
    private val dropboxService = app.dropboxService
    private val wikidataApiClient = app.wikidataApiClient
    private val mediaType = if (mediaTypeStr == "TV") MediaType.TV else MediaType.MOVIE

    private var listContent: String? = null

    private val _uiState = MutableStateFlow<PreviewUiState>(PreviewUiState.Loading)
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    // Emitted after rating in Rate mode:
    // - non-null: navigate directly to next title (no RateScreen round-trip)
    // - null: batch done, pop back to RateScreen to trigger next batch
    private val _autoAdvance = MutableSharedFlow<Pair<Int, String>?>(extraBufferCapacity = 1)
    val autoAdvance: SharedFlow<Pair<Int, String>?> = _autoAdvance.asSharedFlow()

    init {
        val preloaded = app.cachedTitles[tmdbId]
        val cachedList = app.cachedListContent
        if (preloaded != null && cachedList != null) {
            // Rate flow: data is pre-fetched — go straight to Loaded, no network needed
            listContent = cachedList
            _uiState.value = PreviewUiState.Loaded(
                title = preloaded,
                rating = SearchViewModel.parseRating(cachedList, preloaded.title)
            )
            loadWikidataMetadata(preloaded)
            viewModelScope.launch { loadStarStatus() }
        } else {
            viewModelScope.launch { load() }
        }
    }

    private suspend fun loadStarStatus() {
        val starred = app.localStorageService.isStarred(tmdbId)
        val current = _uiState.value as? PreviewUiState.Loaded ?: return
        _uiState.value = current.copy(isStarred = starred)
    }

    private fun loadWikidataMetadata(title: Title) {
        viewModelScope.launch {
            val url = wikidataApiClient.getWikipediaUrl(tmdbId, mediaType == MediaType.MOVIE, title.title)
            val current = _uiState.value as? PreviewUiState.Loaded ?: return@launch
            _uiState.value = current.copy(wikipediaUrl = url, wikipediaReady = true)
        }
        viewModelScope.launch {
            val awards = wikidataApiClient.getAwards(tmdbId, mediaType == MediaType.MOVIE)
            val current = _uiState.value as? PreviewUiState.Loaded ?: return@launch
            _uiState.value = current.copy(awards = awards, awardsReady = true)
        }
    }

    private suspend fun load() = coroutineScope {
        val detailsDeferred = async { tmdbService.fetchDetails(tmdbId, mediaType) }
        val isStarredDeferred = async { app.localStorageService.isStarred(tmdbId) }
        // Use cached list from Recommend/Rate flow to avoid redundant download
        val cached = app.cachedListContent
        if (cached != null) {
            listContent = cached
        } else {
            val listResult = dropboxService.downloadList()
            if (listResult is DropboxResult.Success) {
                listContent = listResult.value
                app.cachedListContent = listResult.value
            }
        }
        when (val result = detailsDeferred.await()) {
            is TmdbResult.Success -> {
                val t = result.value
                _uiState.value = PreviewUiState.Loaded(
                    title = t,
                    rating = listContent?.let { SearchViewModel.parseRating(it, t.title) },
                    isStarred = isStarredDeferred.await()
                )
                loadWikidataMetadata(t)
            }
            is TmdbResult.Failure -> _uiState.value = PreviewUiState.Error("Failed to load title")
        }
    }

    fun hasPrevious(): Boolean = when (source) {
        "rate" -> app.rateQueueIndex > 0
        "recommend" -> app.recommendQueueIndex > 0
        else -> false
    }

    fun navigateBack() {
        viewModelScope.launch {
            when (source) {
                "rate" -> {
                    val idx = app.rateQueueIndex - 1
                    if (idx < 0) return@launch
                    app.rateQueueIndex = idx
                    _autoAdvance.emit(app.rateQueue.getOrNull(idx))
                }
                "recommend" -> {
                    val idx = app.recommendQueueIndex - 1
                    if (idx < 0) return@launch
                    app.recommendQueueIndex = idx
                    _autoAdvance.emit(app.recommendQueue.getOrNull(idx))
                }
            }
        }
    }

    fun onDoubleTap() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        viewModelScope.launch {
            if (loaded.isStarred) {
                app.localStorageService.removeStar(tmdbId)
                val current = _uiState.value as? PreviewUiState.Loaded ?: return@launch
                _uiState.value = current.copy(isStarred = false)
                ToastManager.show("Removed from wishlist.")
            } else {
                app.localStorageService.addStar(tmdbId, loaded.title.mediaType.name)
                val current = _uiState.value as? PreviewUiState.Loaded ?: return@launch
                _uiState.value = current.copy(isStarred = true)
                ToastManager.show("Added to wishlist.")
            }
        }
    }

    fun toggleInList() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        if (loaded.rating == null) saveRating(loaded, 0) else deleteFromList(loaded)
    }

    fun setNotSeen() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        if (loaded.rating == 0) deleteFromList(loaded) else saveRating(loaded, 0)
    }

    fun onSkipOrNotSeen() = when (source) {
        "recommend" -> skip()
        "rate" -> setNotSeen()
        else -> Unit
    }

    fun skip() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val t = loaded.title
        app.recommendSkippedTitles.add("${t.title} (${t.year})")
        viewModelScope.launch { advanceRecommendQueue() }
    }

    private suspend fun advanceRecommendQueue() {
        val nextIndex = app.recommendQueueIndex + 1
        app.recommendQueueIndex = nextIndex
        _autoAdvance.emit(app.recommendQueue.getOrNull(nextIndex))
    }

    fun setRating(stars: Int) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        saveRating(loaded, stars)
    }

    private fun saveRating(loaded: PreviewUiState.Loaded, newRating: Int) {
        val t = loaded.title
        val updated = updateListRating(listContent ?: "", t.title, t.year, newRating)
        listContent = updated
        app.cachedListContent = updated
        when (source) {
            "rate" -> {
                _uiState.value = loaded.copy(rating = newRating, isUploading = true, uploadError = false)
                viewModelScope.launch {
                    val uploadResult = app.dropboxService.uploadList(updated)
                    val failed = uploadResult is DropboxResult.Failure
                    if (failed) Log.e("Dropbox", "Upload failed in rate flow: ${(uploadResult as DropboxResult.Failure).error}")
                    val current = _uiState.value as? PreviewUiState.Loaded
                    if (current != null) _uiState.value = current.copy(isUploading = false, uploadError = failed)
                    val nextIndex = app.rateQueueIndex + 1
                    app.rateQueueIndex = nextIndex
                    _autoAdvance.emit(app.rateQueue.getOrNull(nextIndex))
                }
            }
            "recommend" -> {
                _uiState.value = loaded.copy(rating = newRating, isUploading = true, uploadError = false)
                viewModelScope.launch {
                    val uploadResult = app.dropboxService.uploadList(updated)
                    val failed = uploadResult is DropboxResult.Failure
                    if (failed) Log.e("Dropbox", "Upload failed in recommend flow: ${(uploadResult as DropboxResult.Failure).error}")
                    val current = _uiState.value as? PreviewUiState.Loaded
                    if (current != null) _uiState.value = current.copy(isUploading = false, uploadError = failed)
                    advanceRecommendQueue()
                }
            }
            else -> {
                _uiState.value = loaded.copy(rating = newRating, isUploading = true, uploadError = false)
                viewModelScope.launch {
                    val result = dropboxService.uploadList(updated)
                    if (result is DropboxResult.Failure) Log.e("Dropbox", "Upload failed: ${result.error}")
                    val current = _uiState.value as? PreviewUiState.Loaded ?: return@launch
                    _uiState.value = current.copy(isUploading = false, uploadError = result is DropboxResult.Failure)
                }
            }
        }
    }

    private fun deleteFromList(loaded: PreviewUiState.Loaded) {
        val t = loaded.title
        val updated = removeEntry(listContent ?: "", t.title, t.year)
        listContent = updated
        app.cachedListContent = updated
        when (source) {
            "rate" -> {
                _uiState.value = loaded.copy(rating = null, isUploading = false, uploadError = false)
            }
            "recommend" -> {
                _uiState.value = loaded.copy(rating = null, isUploading = true, uploadError = false)
                viewModelScope.launch {
                    val result = dropboxService.uploadList(updated)
                    if (result is DropboxResult.Failure) Log.e("Dropbox", "Upload failed: ${result.error}")
                    val current = _uiState.value as? PreviewUiState.Loaded ?: return@launch
                    _uiState.value = current.copy(isUploading = false, uploadError = result is DropboxResult.Failure)
                    advanceRecommendQueue()
                }
            }
            else -> {
                _uiState.value = loaded.copy(rating = null, isUploading = true, uploadError = false)
                viewModelScope.launch {
                    val result = dropboxService.uploadList(updated)
                    if (result is DropboxResult.Failure) Log.e("Dropbox", "Upload failed: ${result.error}")
                    val current = _uiState.value as? PreviewUiState.Loaded ?: return@launch
                    _uiState.value = current.copy(isUploading = false, uploadError = result is DropboxResult.Failure)
                }
            }
        }
    }

    companion object {
        fun updateListRating(content: String, title: String, year: Int, newRating: Int): String {
            val entry = "- $title ($year)"
            val header = "RATING: $newRating"
            val lines = content.lines().filter { it.trim() != entry }.toMutableList()
            val headerIdx = lines.indexOfFirst { it.trim().startsWith(header) }
            if (headerIdx >= 0) {
                // Insert at end of this section (just before the next blank line or RATING header)
                var insertIdx = headerIdx + 1
                while (insertIdx < lines.size &&
                    lines[insertIdx].isNotBlank() &&
                    !lines[insertIdx].trim().startsWith("RATING:")
                ) {
                    insertIdx++
                }
                lines.add(insertIdx, entry)
            } else {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add(header)
                lines.add(entry)
            }
            return lines.joinToString("\n")
        }

        fun removeEntry(content: String, title: String, year: Int): String {
            val entry = "- $title ($year)"
            return content.lines().filter { it.trim() != entry }.joinToString("\n")
        }
    }
}

class PreviewViewModelFactory(
    private val tmdbId: Int,
    private val mediaTypeStr: String,
    private val source: String = "search"
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PreviewViewModel(tmdbId, mediaTypeStr, source) as T
}
