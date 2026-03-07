package com.moviesrecommender.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.data.remote.tmdb.Title
import com.moviesrecommender.data.remote.tmdb.TmdbResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class TitleWithRating(val title: Title, val rating: Int?)

private const val INITIAL_COUNT = 10
private const val LOAD_MORE_COUNT = 5

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {

    private val app = MoviesRecommenderApp.instance
    private val tmdbService = app.tmdbService
    private val dropboxService = app.dropboxService

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<TitleWithRating>>(emptyList())
    val results: StateFlow<List<TitleWithRating>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private var listContent: String? = null
    private val buffer = mutableListOf<TitleWithRating>()
    private var displayedCount = 0
    private var currentPage = 0
    private var totalPages = 1
    private var activeQuery = ""

    init {
        viewModelScope.launch { downloadList() }
        viewModelScope.launch {
            _query
                .debounce(300)
                .collectLatest { q ->
                    if (q.length >= 2) startSearch(q)
                    else resetResults()
                }
        }
    }

    fun onQueryChange(q: String) { _query.value = q }

    fun loadMore() {
        if (_isLoadingMore.value || _isSearching.value) return
        if (displayedCount >= buffer.size && currentPage >= totalPages) return
        viewModelScope.launch {
            if (displayedCount < buffer.size) {
                displayedCount = minOf(displayedCount + LOAD_MORE_COUNT, buffer.size)
                _results.value = buffer.take(displayedCount)
            } else {
                _isLoadingMore.value = true
                val nextPage = currentPage + 1
                when (val result = tmdbService.search(activeQuery, nextPage)) {
                    is TmdbResult.Success -> {
                        currentPage = nextPage
                        val newItems = result.value.titles.map { it.withRating() }
                        buffer.addAll(newItems)
                        displayedCount = minOf(displayedCount + LOAD_MORE_COUNT, buffer.size)
                        _results.value = buffer.take(displayedCount)
                    }
                    is TmdbResult.Failure -> Unit
                }
                _isLoadingMore.value = false
            }
        }
    }

    private suspend fun startSearch(query: String) {
        activeQuery = query
        buffer.clear()
        displayedCount = 0
        currentPage = 0
        totalPages = 1
        _isSearching.value = true
        when (val result = tmdbService.search(query, 1)) {
            is TmdbResult.Success -> {
                currentPage = 1
                totalPages = result.value.totalPages
                buffer.addAll(result.value.titles.map { it.withRating() })
                displayedCount = minOf(INITIAL_COUNT, buffer.size)
                _results.value = buffer.take(displayedCount)
            }
            is TmdbResult.Failure -> _results.value = emptyList()
        }
        _isSearching.value = false
    }

    private fun resetResults() {
        buffer.clear()
        displayedCount = 0
        _results.value = emptyList()
    }

    private suspend fun downloadList() {
        when (val result = dropboxService.downloadList()) {
            is DropboxResult.Success -> listContent = result.value
            is DropboxResult.Failure -> Unit
        }
    }

    private fun Title.withRating() =
        TitleWithRating(this, listContent?.let { parseRating(it, title) })

    companion object {
        fun parseRating(listContent: String, titleToFind: String): Int? {
            var currentRating: Int? = null
            val normalized = titleToFind.lowercase().trim()
            for (line in listContent.lines()) {
                val trimmed = line.trim()
                val ratingMatch = Regex("^RATING:\\s*(\\d+)").find(trimmed)
                if (ratingMatch != null) {
                    currentRating = ratingMatch.groupValues[1].toIntOrNull()
                    continue
                }
                if (trimmed.startsWith("- ")) {
                    val titlePart = trimmed.removePrefix("- ").substringBefore("(").trim().lowercase()
                    if (titlePart == normalized) return currentRating
                }
            }
            return null
        }
    }
}
