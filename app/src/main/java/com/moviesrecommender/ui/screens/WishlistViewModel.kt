package com.moviesrecommender.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.data.remote.tmdb.MediaType
import com.moviesrecommender.data.remote.tmdb.Title
import com.moviesrecommender.data.remote.tmdb.TmdbResult
import com.moviesrecommender.util.ToastManager
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

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() = coroutineScope {
        _uiState.value = WishlistUiState.Loading

        // Download list (reuse cached copy if available — HLD: always re-download before a read operation)
        val listContent: String?
        val listResult = dropboxService.downloadList()
        listContent = if (listResult is DropboxResult.Success) {
            app.cachedListContent = listResult.value
            listResult.value
        } else {
            app.cachedListContent
        }

        val starredEntities = localStorage.getStarsWithType()
        if (starredEntities.isEmpty()) {
            _uiState.value = WishlistUiState.Loaded(emptyList(), emptyList())
            return@coroutineScope
        }

        // Fetch TMDB details for each starred title concurrently
        val items = starredEntities.map { entity ->
            async {
                val mediaType = if (entity.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                when (val result = tmdbService.fetchDetails(entity.tmdbId, mediaType)) {
                    is TmdbResult.Success -> {
                        val rating = listContent?.let {
                            SearchViewModel.parseRating(it, result.value.title)
                        }
                        WishlistItem(result.value, rating)
                    }
                    is TmdbResult.Failure -> null
                }
            }
        }.awaitAll().filterNotNull()

        val conflicts = items.filter { it.rating != null && it.rating in 1..4 }
        _uiState.value = WishlistUiState.Loaded(items, conflicts)
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
