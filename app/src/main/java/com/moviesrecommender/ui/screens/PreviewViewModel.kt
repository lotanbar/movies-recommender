package com.moviesrecommender.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.local.ListEntryParser
import com.moviesrecommender.data.local.ShowSegment
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

    private data class PendingRating(
        val tier: Int,
        val newLine: String,
        val replacingRawLines: List<String>,
        val carveEntries: List<Pair<Int, String>>,
        val nextTier: Int?,
        val thenFinalize: Boolean
    )

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

    private val _isAssessing = MutableStateFlow(false)
    val isAssessing: StateFlow<Boolean> = _isAssessing.asStateFlow()

    private val _assessedTier = MutableStateFlow<Int?>(null)
    val assessedTier: StateFlow<Int?> = _assessedTier.asStateFlow()

    private var assessJob: Job? = null

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
        val segments = ListEntryParser.parseSegments(content, title.title, title.year)
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
        _uiState.value = loaded.copy(
            isPickingRange = true,
            // Blank slate ("X"/"X") unless editing an existing segment's known range.
            pickerFrom = editing?.seasonStart,
            pickerTo = editing?.seasonEnd,
            pickerTier = editing?.tier ?: presetTier,
            editingSegment = editing
        )
    }

    private fun seasonBounds(loaded: PreviewUiState.Loaded): Pair<Int, Int> {
        val seasonNumbers = loaded.title.seasons.map { it.seasonNumber }
        val minSeason = seasonNumbers.minOrNull() ?: 1
        val maxSeason = seasonNumbers.maxOrNull() ?: minSeason
        return minSeason to maxSeason
    }

    /**
     * Resolves the picker's From/To — each independently either a season number or "X" (null,
     * meaning unset) — into a concrete range. Both "X" means the user doesn't want to rate this
     * tier at all (null result). Exactly one "X" means a single-season pick using the other side.
     */
    private fun resolvePickerRange(from: Int?, to: Int?): Pair<Int, Int>? {
        if (from == null && to == null) return null
        val start = from ?: to!!
        val end = to ?: from!!
        return if (start <= end) start to end else end to start
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
        val targetTier = loaded.pickerTier
        val resolved = targetTier?.let { resolvePickerRange(loaded.pickerFrom, loaded.pickerTo) }
        if (targetTier != null && resolved != null) {
            // Commit whatever range is still pending on the current target before uploading.
            val (start, end) = resolved
            stageRating(targetTier, start, end, loaded.editingSegment, nextTier = null, thenFinalize = true)
        } else {
            if (targetTier != null) clearDraftForSkip(loaded, targetTier, loaded.editingSegment)
            finishDraft(_uiState.value as? PreviewUiState.Loaded ?: loaded)
        }
    }

    /**
     * "X"/"X" on a tier the user is leaving means "this tier should end up unrated" — so if it
     * (or, when editing, the specific segment being edited) already has something staged in the
     * draft, remove it rather than silently leaving the old rating in place.
     */
    private fun clearDraftForSkip(loaded: PreviewUiState.Loaded, tier: Int, editing: ShowSegment?) {
        val base = draftListContent ?: listContent ?: ""
        val draftSegments = ListEntryParser.parseSegments(base, loaded.title.title, loaded.title.year)
        val toRemove = if (editing != null) {
            draftSegments.filter { it.rawLine == editing.rawLine }
        } else {
            draftSegments.filter { it.tier == tier }
        }
        if (toRemove.isEmpty()) return
        var updated = base
        for (segment in toRemove) updated = ListEntryParser.removeLine(updated, segment.rawLine)
        draftListContent = updated
        val newSegments = ListEntryParser.parseSegments(updated, loaded.title.title, loaded.title.year)
        val rating = newSegments.singleOrNull()?.takeIf { it.seasonStart == null }?.tier
        _uiState.value = loaded.copy(segments = newSegments, rating = rating)
    }

    private fun finishDraft(loaded: PreviewUiState.Loaded) {
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

    /** Moves "From" — season numbers below the show's first season are the "X" (null) sentinel. */
    fun setPickerFrom(value: Int?) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val (minSeason, maxSeason) = seasonBounds(loaded)
        val clamped = value?.coerceIn(minSeason, maxSeason)
        val to = loaded.pickerTo
        val adjusted = if (clamped != null && to != null && clamped > to) to else clamped
        _uiState.value = loaded.copy(pickerFrom = adjusted)
    }

    /** Moves "To" — season numbers above the show's last season are the "X" (null) sentinel. */
    fun setPickerTo(value: Int?) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val (minSeason, maxSeason) = seasonBounds(loaded)
        val clamped = value?.coerceIn(minSeason, maxSeason)
        val from = loaded.pickerFrom
        val adjusted = if (clamped != null && from != null && clamped < from) from else clamped
        _uiState.value = loaded.copy(pickerTo = adjusted)
    }

    /**
     * Tapping a tier in the picker commits the pending range to the *previously* targeted tier
     * (the one long-pressed, or last tapped), then makes the tapped tier the new target with a
     * blank-slate range. If the previous target's From/To were left as "X"/"X", it's cleared —
     * see [clearDraftForSkip] — rather than left with whatever it had before. Local only — no
     * upload until [finalizeSeasonPicker].
     */
    fun selectTierForCurrentRange(newTier: Int) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val targetTier = loaded.pickerTier
        val resolved = targetTier?.let { resolvePickerRange(loaded.pickerFrom, loaded.pickerTo) }
        if (targetTier == null || resolved == null) {
            if (targetTier != null) clearDraftForSkip(loaded, targetTier, loaded.editingSegment)
            val current = _uiState.value as? PreviewUiState.Loaded ?: loaded
            _uiState.value = current.copy(pickerTier = newTier, pickerFrom = null, pickerTo = null, editingSegment = null)
            return
        }
        val (start, end) = resolved
        stageRating(targetTier, start, end, loaded.editingSegment, nextTier = newTier, thenFinalize = false)
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
            pendingRating = PendingRating(tier, newLine, replacingRawLines, carveEntries = emptyList(), nextTier = null, thenFinalize = false)
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

    /**
     * For each segment a new [newStart]-[newEnd] pick overlaps, keeps whatever seasons of that
     * segment fall *outside* the new pick as separate entries at the segment's own original
     * tier — so e.g. rerating season 3 out of an existing "Seasons 1-4" segment leaves that
     * tier with "Seasons 1-2" and "Season 4" instead of losing the whole thing.
     */
    private fun buildCarveEntries(t: Title, overlapping: List<ShowSegment>, newStart: Int, newEnd: Int): List<Pair<Int, String>> =
        overlapping.flatMap { segment ->
            ListEntryParser.carveRemainder(segment, newStart, newEnd).map { (runStart, runEnd) ->
                val yearStart = t.seasons.firstOrNull { it.seasonNumber == runStart }?.year ?: t.year
                val yearEnd = t.seasons.firstOrNull { it.seasonNumber == runEnd }?.year
                segment.tier to ListEntryParser.formatSegmentEntry(t.title, runStart, runEnd, yearStart, yearEnd)
            }
        }

    private fun stageRating(
        tier: Int,
        seasonStart: Int,
        seasonEnd: Int,
        editing: ShowSegment?,
        nextTier: Int?,
        thenFinalize: Boolean
    ) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val t = loaded.title
        val base = draftListContent ?: listContent ?: ""
        val draftSegments = ListEntryParser.parseSegments(base, t.title, t.year)
        val yearStart = t.seasons.firstOrNull { it.seasonNumber == seasonStart }?.year ?: t.year
        val yearEnd = t.seasons.firstOrNull { it.seasonNumber == seasonEnd }?.year
        val newLine = ListEntryParser.formatSegmentEntry(t.title, seasonStart, seasonEnd, yearStart, yearEnd)

        val overlapping = draftSegments.filter { segment ->
            segment.rawLine != editing?.rawLine && ListEntryParser.overlaps(segment, seasonStart, seasonEnd)
        }
        val replacingRawLines = (overlapping.map { it.rawLine } + listOfNotNull(editing?.rawLine)).distinct()
        val carveEntries = buildCarveEntries(t, overlapping, seasonStart, seasonEnd)

        if (overlapping.isNotEmpty()) {
            pendingRating = PendingRating(tier, newLine, replacingRawLines, carveEntries, nextTier, thenFinalize)
            viewModelScope.launch { _confirmOverlapReplace.emit(Unit) }
        } else {
            commitDraft(tier, newLine, replacingRawLines, carveEntries, base, nextTier, thenFinalize)
        }
    }

    private fun commitDraft(
        tier: Int,
        newLine: String,
        replacingRawLines: List<String>,
        carveEntries: List<Pair<Int, String>>,
        base: String,
        nextTier: Int?,
        thenFinalize: Boolean
    ) {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        val entries = listOf(tier to newLine) + carveEntries
        val updated = ListEntryParser.upsertSegments(base, entries, replacingRawLines)
        draftListContent = updated
        val draftSegments = ListEntryParser.parseSegments(updated, loaded.title.title, loaded.title.year)
        val rating = draftSegments.singleOrNull()?.takeIf { it.seasonStart == null }?.tier

        if (thenFinalize) {
            val finalLoaded = loaded.copy(segments = draftSegments, rating = rating, pickerTier = null, editingSegment = null)
            _uiState.value = finalLoaded
            finishDraft(finalLoaded)
        } else {
            _uiState.value = loaded.copy(
                segments = draftSegments,
                rating = rating,
                pickerTier = nextTier,
                pickerFrom = null,
                pickerTo = null,
                editingSegment = null
            )
        }
    }

    /** User confirmed replacing the overlapping segment(s) shown in the warning dialog. */
    fun confirmOverlapReplace() {
        val pending = pendingRating ?: return
        pendingRating = null
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        if (loaded.isPickingRange) {
            commitDraft(
                pending.tier,
                pending.newLine,
                pending.replacingRawLines,
                pending.carveEntries,
                draftListContent ?: listContent ?: "",
                pending.nextTier,
                pending.thenFinalize
            )
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

    fun onLongPressAssessPoster() {
        val loaded = _uiState.value as? PreviewUiState.Loaded ?: return
        if (loaded.segments.isNotEmpty()) {
            val ratedLabel = loaded.rating?.let { ListEntryParser.tierLabel(it) }
                ?: loaded.segments.first().let { ListEntryParser.tierLabel(it.tier) }
            ToastManager.show("Already rated as $ratedLabel — no need to assess.")
            return
        }
        if (_isAssessing.value) return
        _isAssessing.value = true
        val title = loaded.title
        assessJob = viewModelScope.launch {
            val content = listContent ?: app.cachedListContent ?: run {
                when (val r = dropboxService.downloadList()) {
                    is DropboxResult.Success -> r.value.also { listContent = it; app.cachedListContent = it }
                    is DropboxResult.Failure -> {
                        _isAssessing.value = false
                        ToastManager.show(r.error.toMessage())
                        return@launch
                    }
                }
            }
            val result = app.anthropicService.sendPrompt("${title.title} (${title.year}) assess", content)
            _isAssessing.value = false
            when (result) {
                is AnthropicResult.Success -> ListEntryParser.parseAssessTier(result.value)?.let {
                    _assessedTier.value = it
                }
                is AnthropicResult.Failure -> ToastManager.show(result.error.toMessage())
            }
        }
    }

    fun cancelAssess() {
        assessJob?.cancel()
        _isAssessing.value = false
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
