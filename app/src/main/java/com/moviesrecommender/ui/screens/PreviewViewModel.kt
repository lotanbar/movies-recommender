package com.moviesrecommender.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.local.ListEntryParser
import com.moviesrecommender.data.local.ShowSegment
import com.moviesrecommender.data.remote.dropbox.DropboxError
import com.moviesrecommender.data.remote.dropbox.DropboxResult
import com.moviesrecommender.data.remote.tmdb.MediaType
import com.moviesrecommender.data.remote.tmdb.Title
import com.moviesrecommender.data.remote.tmdb.TmdbResult
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
        /** Non-null only when there is exactly one whole-series segment — the common case, unrated shows also included. */
        val rating: Int?,
        val segments: List<ShowSegment> = emptyList(),
        val isStarred: Boolean = false,
        val isUploading: Boolean = false,
        val uploadError: Boolean = false,
        /** (position, total) within the current recommend batch queue — null outside recommend flow. */
        val queuePosition: Pair<Int, Int>? = null,
        val isPickingRange: Boolean = false,
        val pickerFrom: Int? = null,
        val pickerTo: Int? = null,
        /** Tier currently selected in the open picker — only committed when Submit is tapped. */
        val pickerTier: Int? = null,
        /** Non-null when the picker is editing an existing structured segment rather than adding a new one. */
        val editingSegment: ShowSegment? = null
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
    private val mediaType = if (mediaTypeStr == "TV") MediaType.TV else MediaType.MOVIE

    private var listContent: String? = null
    /** Unsaved picker changes — never touches Dropbox or [app.cachedListContent] until [finalizeSeasonPicker]. */
    private var draftListContent: String? = null
    private var pendingRating: PendingRating? = null

    private data class PendingRating(val tier: Int, val newLine: String, val replacingRawLines: List<String>)

    private fun currentQueuePosition(): Pair<Int, Int>? =
        if (source == "recommend" && app.recommendQueue.isNotEmpty())
            Pair(app.recommendQueueIndex + 1, app.recommendQueue.size)
        else null

    private val _uiState = MutableStateFlow<PreviewUiState>(PreviewUiState.Loading)
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    // Emitted after rating/skip in Recommend mode:
    // - non-null: navigate directly to next recommended title
    // - null: recommend batch done
    private val _autoAdvance = MutableSharedFlow<Pair<Int, String>?>(extraBufferCapacity = 1)
    val autoAdvance: SharedFlow<Pair<Int, String>?> = _autoAdvance.asSharedFlow()

    // Emitted when advancing past the last title in the batch would trigger a new (paid) Claude fetch.
    private val _confirmFetchMore = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val confirmFetchMore: SharedFlow<Unit> = _confirmFetchMore.asSharedFlow()

    // Emitted when the current pick would overlap one or more existing segments for this show.
    private val _confirmOverlapReplace = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val confirmOverlapReplace: SharedFlow<Unit> = _confirmOverlapReplace.asSharedFlow()

    init {
        val preloaded = app.cachedTitles[tmdbId]
        val cachedList = app.cachedListContent
        if (preloaded != null && cachedList != null) {
            // Data pre-fetched by Recommend flow — go straight to Loaded, no network needed.
            listContent = cachedList
            _uiState.value = loadedState(preloaded, cachedList)
            viewModelScope.launch { loadStarStatus() }
        } else {
            viewModelScope.launch { load() }
        }
    }

    private fun loadedState(title: Title, content: String): PreviewUiState.Loaded {
        val segments = ListEntryParser.parseSegments(content, title.title)
        val rating = segments.singleOrNull()?.takeIf { it.seasonStart == null }?.tier
        return PreviewUiState.Loaded(
            title = title,
            rating = rating,
            segments = segments,
            queuePosition = currentQueuePosition()
        )
    }

    private suspend fun loadStarStatus() {
        val starred = app.localStorageService.isStarred(tmdbId)
        val current = _uiState.value as? PreviewUiState.Loaded ?: return
        _uiState.value = current.copy(isStarred = starred)
    }

    private suspend fun load() = coroutineScope {
        val detailsDeferred = async { tmdbService.fetchDetails(tmdbId, mediaType) }
        val isStarredDeferred = async { app.localStorageService.isStarred(tmdbId) }
        // Use cached list from Recommend flow to avoid redundant download.
        val cached = app.cachedListContent
        if (cached != null) {
            listContent = cached
        } else {
            when (val listResult = dropboxService.downloadList()) {
                is DropboxResult.Success -> {
                    listContent = listResult.value
                    app.cachedListContent = listResult.value
                }
                is DropboxResult.Failure -> {
                    detailsDeferred.cancel()
                    isStarredDeferred.cancel()
                    _uiState.value = PreviewUiState.Error(listResult.error.toMessage())
                    return@coroutineScope
                }
            }
        }
        when (val result = detailsDeferred.await()) {
            is TmdbResult.Success -> {
                val t = result.value
                _uiState.value = loadedState(t, listContent ?: "").copy(isStarred = isStarredDeferred.await())
            }
            is TmdbResult.Failure -> _uiState.value = PreviewUiState.Error("Failed to load title")
        }
    }

    fun hasPrevious(): Boolean = source == "recommend" && app.recommendQueueIndex > 0

    fun navigateBack() {
        viewModelScope.launch {
            if (source == "recommend") {
                val idx = app.recommendQueueIndex - 1
                if (idx < 0) return@launch
                app.recommendQueueIndex = idx
                _autoAdvance.emit(app.recommendQueue.getOrNull(idx))
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

    fun onSkip() {
        if (source == "recommend") skip()
    }

    fun skip() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val t = loaded.title
        app.recommendSkippedTitles.add("${t.title} (${t.year})")
        viewModelScope.launch { advanceRecommendQueue() }
    }

    private suspend fun advanceRecommendQueue() {
        val nextIndex = app.recommendQueueIndex + 1
        if (nextIndex >= app.recommendQueue.size) {
            _confirmFetchMore.emit(Unit)
            return
        }
        app.recommendQueueIndex = nextIndex
        _autoAdvance.emit(app.recommendQueue.getOrNull(nextIndex))
    }

    /** User confirmed the "fetch more from Claude" prompt — proceed to end the batch. */
    fun proceedFetchMore() {
        viewModelScope.launch {
            app.recommendQueueIndex = app.recommendQueue.size
            _autoAdvance.emit(null)
        }
    }

    // --- Season-range picker ---

    fun openSeasonPicker(editing: ShowSegment? = null, presetTier: Int? = null) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        draftListContent = listContent
        val seasonNumbers = loaded.title.seasons.map { it.seasonNumber }
        val defaultFrom = editing?.seasonStart ?: seasonNumbers.firstOrNull() ?: 1
        val defaultTo = editing?.seasonEnd ?: seasonNumbers.lastOrNull() ?: defaultFrom
        _uiState.value = loaded.copy(
            isPickingRange = true,
            pickerFrom = defaultFrom,
            pickerTo = defaultTo,
            pickerTier = editing?.tier ?: presetTier,
            editingSegment = editing
        )
    }

    /** Discards any staged (unsubmitted) picker changes and closes the picker. */
    fun closeSeasonPicker() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        draftListContent = null
        _uiState.value = loadedState(loaded.title, listContent ?: "").copy(isStarred = loaded.isStarred)
    }

    /** Applies every staged picker change in one Dropbox upload, then closes the picker. */
    fun finalizeSeasonPicker() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val draft = draftListContent
        draftListContent = null
        if (draft == null || draft == listContent) {
            _uiState.value = loaded.copy(
                isPickingRange = false,
                pickerFrom = null,
                pickerTo = null,
                pickerTier = null,
                editingSegment = null
            )
            return
        }
        listContent = draft
        app.cachedListContent = draft
        _uiState.value = loadedState(loaded.title, draft).copy(isStarred = loaded.isStarred, isUploading = true)
        uploadAndAdvance()
    }

    fun setPickerRange(from: Int, to: Int) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val seasonNumbers = loaded.title.seasons.map { it.seasonNumber }
        val minSeason = seasonNumbers.minOrNull() ?: 1
        val maxSeason = seasonNumbers.maxOrNull() ?: 1
        val clampedFrom = from.coerceIn(minSeason, maxSeason)
        val clampedTo = to.coerceIn(clampedFrom, maxSeason)
        _uiState.value = loaded.copy(pickerFrom = clampedFrom, pickerTo = clampedTo)
    }

    /** Tapping a tier in the picker stages that range immediately (local only, no upload). */
    fun selectTierForCurrentRange(tier: Int) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        stageRating(tier, loaded.pickerFrom, loaded.pickerTo, loaded.editingSegment)
    }

    // --- Rating (whole-show tap, or a season range from the picker) ---

    /** Whole-show one-tap rating — used by the persistent bottom bar. Commits and uploads immediately. */
    fun setRating(stars: Int) = applyRating(stars, seasonStart = null, seasonEnd = null, editing = null)

    fun clearRating() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val wholeSeriesSegment = loaded.segments.singleOrNull { it.seasonStart == null } ?: return
        deleteSegment(wholeSeriesSegment)
    }

    fun deleteSegment(segment: ShowSegment) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val updated = ListEntryParser.removeLine(listContent ?: "", segment.rawLine)
        listContent = updated
        app.cachedListContent = updated
        _uiState.value = loadedState(loaded.title, updated).copy(isStarred = loaded.isStarred, isUploading = true)
        uploadAndAdvance()
    }

    private fun applyRating(tier: Int, seasonStart: Int?, seasonEnd: Int?, editing: ShowSegment?) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val t = loaded.title
        val yearStart = seasonStart?.let { s -> t.seasons.firstOrNull { it.seasonNumber == s }?.year } ?: t.year
        val yearEnd = seasonEnd?.let { e -> t.seasons.firstOrNull { it.seasonNumber == e }?.year }
        val newLine = ListEntryParser.formatSegmentEntry(t.title, seasonStart, seasonEnd, yearStart, yearEnd)

        // A whole-show pick (seasonStart == null) supersedes every existing segment for this show.
        val overlapping = loaded.segments.filter { segment ->
            segment.rawLine != editing?.rawLine &&
                (seasonStart == null || seasonEnd == null || ListEntryParser.overlaps(segment, seasonStart, seasonEnd))
        }
        val replacingRawLines = (overlapping.map { it.rawLine } + listOfNotNull(editing?.rawLine)).distinct()

        if (overlapping.isNotEmpty()) {
            pendingRating = PendingRating(tier, newLine, replacingRawLines)
            viewModelScope.launch { _confirmOverlapReplace.emit(Unit) }
        } else {
            commitRating(tier, newLine, replacingRawLines)
        }
    }

    private fun commitRating(tier: Int, newLine: String, replacingRawLines: List<String>) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val updated = ListEntryParser.upsertSegment(listContent ?: "", tier, newLine, replacingRawLines)
        listContent = updated
        app.cachedListContent = updated
        _uiState.value = loadedState(loaded.title, updated).copy(isStarred = loaded.isStarred, isUploading = true)
        uploadAndAdvance()
    }

    // --- Draft staging within an open season picker (no upload, no persistence until finalizeSeasonPicker) ---

    private fun stageRating(tier: Int, seasonStart: Int?, seasonEnd: Int?, editing: ShowSegment?) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val t = loaded.title
        val base = draftListContent ?: listContent ?: ""
        val draftSegments = ListEntryParser.parseSegments(base, t.title)
        val yearStart = seasonStart?.let { s -> t.seasons.firstOrNull { it.seasonNumber == s }?.year } ?: t.year
        val yearEnd = seasonEnd?.let { e -> t.seasons.firstOrNull { it.seasonNumber == e }?.year }
        val newLine = ListEntryParser.formatSegmentEntry(t.title, seasonStart, seasonEnd, yearStart, yearEnd)

        val overlapping = draftSegments.filter { segment ->
            segment.rawLine != editing?.rawLine &&
                (seasonStart == null || seasonEnd == null || ListEntryParser.overlaps(segment, seasonStart, seasonEnd))
        }
        val replacingRawLines = (overlapping.map { it.rawLine } + listOfNotNull(editing?.rawLine)).distinct()

        if (overlapping.isNotEmpty()) {
            pendingRating = PendingRating(tier, newLine, replacingRawLines)
            viewModelScope.launch { _confirmOverlapReplace.emit(Unit) }
        } else {
            commitDraft(tier, newLine, replacingRawLines, base)
        }
    }

    private fun commitDraft(tier: Int, newLine: String, replacingRawLines: List<String>, base: String) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val updated = ListEntryParser.upsertSegment(base, tier, newLine, replacingRawLines)
        draftListContent = updated
        val draftSegments = ListEntryParser.parseSegments(updated, loaded.title.title)
        val rating = draftSegments.singleOrNull()?.takeIf { it.seasonStart == null }?.tier
        _uiState.value = loaded.copy(
            segments = draftSegments,
            rating = rating,
            pickerTier = tier,
            editingSegment = null
        )
    }

    /** User confirmed replacing the overlapping segment(s) shown in the warning dialog. */
    fun confirmOverlapReplace() {
        val pending = pendingRating ?: return
        pendingRating = null
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        if (loaded.isPickingRange) {
            commitDraft(pending.tier, pending.newLine, pending.replacingRawLines, draftListContent ?: listContent ?: "")
        } else {
            commitRating(pending.tier, pending.newLine, pending.replacingRawLines)
        }
    }

    fun cancelOverlapReplace() {
        pendingRating = null
    }

    private fun uploadAndAdvance() {
        viewModelScope.launch {
            val result = dropboxService.uploadList(listContent ?: "")
            val failed = result is DropboxResult.Failure
            val current = _uiState.value as? PreviewUiState.Loaded
            if (current != null) _uiState.value = current.copy(isUploading = false, uploadError = failed)
            if (source == "recommend") advanceRecommendQueue()
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

private fun DropboxError.toMessage(): String = when (this) {
    DropboxError.NoInternet -> "Download failed: No internet connection."
    DropboxError.TokenExpired -> "Dropbox session expired - please re-authenticate."
    DropboxError.FileNotFound -> "List file not found. Please update the path in Setup."
    DropboxError.StorageFull -> "Dropbox storage is full."
    DropboxError.RateLimit -> "Too many requests. Try again shortly."
    is DropboxError.Unknown -> "Download failed: $message"
}
