package com.moviesrecommender.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.moviesrecommender.navigation.Screen
import coil.compose.AsyncImage
import com.moviesrecommender.data.local.ListEntryParser
import com.moviesrecommender.data.local.ShowSegment
import com.moviesrecommender.data.remote.tmdb.MediaType
import com.moviesrecommender.ui.components.BusyOverlay
import com.moviesrecommender.ui.components.UsageStatsDialog
import com.moviesrecommender.ui.components.formatElapsed
import com.moviesrecommender.ui.components.rememberAssessPulseAlpha
import com.moviesrecommender.ui.components.rememberElapsedSeconds
import com.moviesrecommender.util.ToastManager

/** Matches the squared-off corner style of the main Actions screen buttons. */
private val APP_CORNER_SHAPE = RoundedCornerShape(12.dp)

@Composable
fun PreviewScreen(
    navController: NavHostController,
    tmdbId: Int,
    mediaType: String,
    source: String = "search"
) {
    val viewModel: PreviewViewModel = viewModel(
        factory = PreviewViewModelFactory(tmdbId, mediaType, source)
    )
    val uiState by viewModel.uiState.collectAsState()
    val isAssessing by viewModel.isAssessing.collectAsState()
    val assessedTier by viewModel.assessedTier.collectAsState()
    val statsPopup by viewModel.statsPopup.collectAsState()

    statsPopup?.let { stats ->
        UsageStatsDialog(stats = stats, onDismiss = { viewModel.dismissStatsPopup() })
    }

    // Recommend mode: after rating/skip, navigate to next item or pop back to RecommendScreen for new batch
    if (source == "recommend") {
        LaunchedEffect(Unit) {
            viewModel.autoAdvance.collect { next ->
                if (next != null) {
                    navController.navigate(Screen.Preview.createRoute(next.first, next.second, "recommend")) {
                        popUpTo(Screen.Preview.createRoute(tmdbId, mediaType, "recommend")) { inclusive = true }
                    }
                } else {
                    navController.popBackStack()
                }
            }
        }
    }

    // While assessing, back shows a cancel confirmation instead of navigating.
    // Otherwise, in Recommend mode, back gesture goes straight to the main Actions screen.
    var showAssessCancelConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = isAssessing || source == "recommend") {
        if (isAssessing) {
            showAssessCancelConfirm = true
        } else {
            navController.popBackStack(Screen.Actions.route, inclusive = false)
        }
    }
    if (showAssessCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showAssessCancelConfirm = false },
            title = { Text("Cancel assessment?") },
            text = { Text("This will stop the current assessment.") },
            confirmButton = {
                TextButton(onClick = {
                    showAssessCancelConfirm = false
                    viewModel.cancelAssess()
                }) { Text("Yes, cancel") }
            },
            dismissButton = {
                TextButton(onClick = { showAssessCancelConfirm = false }) { Text("Keep going") }
            }
        )
    }

    var showFetchMoreConfirm by remember { mutableStateOf(false) }
    if (source == "recommend") {
        LaunchedEffect(Unit) {
            viewModel.confirmFetchMore.collect { showFetchMoreConfirm = true }
        }
    }
    if (showFetchMoreConfirm) {
        AlertDialog(
            onDismissRequest = { showFetchMoreConfirm = false },
            title = { Text("Fetch more titles?") },
            text = { Text("This will fetch more titles from Claude, are you sure you want to proceed?") },
            confirmButton = {
                TextButton(onClick = {
                    showFetchMoreConfirm = false
                    viewModel.proceedFetchMore()
                }) { Text("Proceed") }
            },
            dismissButton = {
                TextButton(onClick = { showFetchMoreConfirm = false }) { Text("Cancel") }
            }
        )
    }

    var showOverlapConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.confirmOverlapReplace.collect { showOverlapConfirm = true }
    }
    if (showOverlapConfirm) {
        AlertDialog(
            onDismissRequest = {
                showOverlapConfirm = false
                viewModel.cancelOverlapReplace()
            },
            title = { Text("Replace overlapping seasons?") },
            text = { Text("This season range overlaps a rating you've already recorded for this show. Proceeding will move just the overlapping seasons here; any other seasons in that rating stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    showOverlapConfirm = false
                    viewModel.confirmOverlapReplace()
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverlapConfirm = false
                    viewModel.cancelOverlapReplace()
                }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            val loaded = uiState as? PreviewUiState.Loaded
            if (loaded != null) {
                Column {
                    if (loaded.isPickingRange) {
                        val seasonNumbers = loaded.title.seasons.map { it.seasonNumber }
                        val seasonRange = (seasonNumbers.minOrNull() ?: 1)..(seasonNumbers.maxOrNull() ?: 1)
                        SeasonPickerBar(
                            seasonRange = seasonRange,
                            from = loaded.pickerFrom,
                            to = loaded.pickerTo,
                            ratedTiers = loaded.segments.map { it.tier }.toSet(),
                            selectedTier = loaded.pickerTier,
                            segmentLabels = segmentLabelsByTier(loaded.segments),
                            onFromChange = viewModel::setPickerFrom,
                            onToChange = viewModel::setPickerTo,
                            onSelectTier = viewModel::selectTierForCurrentRange,
                            onSubmit = viewModel::finalizeSeasonPicker,
                            onCancel = viewModel::closeSeasonPicker
                        )
                    } else {
                        RatingBottomBar(
                            activeTiers = loaded.segments.map { it.tier }.toSet(),
                            segmentLabels = segmentLabelsByTier(loaded.segments),
                            isUploading = loaded.isUploading,
                            uploadError = loaded.uploadError,
                            assessedTier = assessedTier,
                            onSetRating = viewModel::setRating,
                            onClearRating = viewModel::clearRating,
                            onLongPressTier = { tier ->
                                if (loaded.title.mediaType == MediaType.TV) viewModel.openSeasonPicker(null, tier)
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is PreviewUiState.Loading -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 12.dp)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
                is PreviewUiState.Error -> {
                    Text(
                        text = state.message,
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is PreviewUiState.Loaded -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        state.queuePosition?.let { (position, total) ->
                            Text(
                                text = "$position / $total in this batch",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                        if (isAssessing) {
                            val elapsedSeconds = rememberElapsedSeconds(isAssessing)
                            Text(
                                text = formatElapsed(elapsedSeconds),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                            )
                        }
                        LoadedContent(
                            state = state,
                            source = source,
                            isAssessing = isAssessing,
                            hasPrevious = viewModel::hasPrevious,
                            onNavigateBack = viewModel::navigateBack,
                            onDoubleTap = viewModel::onDoubleTap,
                            onSkip = viewModel::onSkip,
                            onLongPressAssess = viewModel::onLongPressAssessPoster,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
    BusyOverlay(isBusy = isAssessing, message = "Assessment in Progress... Plz wait")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LoadedContent(
    state: PreviewUiState.Loaded,
    source: String,
    isAssessing: Boolean,
    hasPrevious: () -> Boolean,
    onNavigateBack: () -> Unit,
    onDoubleTap: () -> Unit,
    onSkip: () -> Unit,
    onLongPressAssess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = state.title
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    fun openUrl(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    // recommend: pages = [back(0) | poster(1) | skip(2)], start at 1
    // search: pages = [poster(0)], start at 0
    val showBack = source == "recommend"
    val pagerState = rememberPagerState(
        initialPage = if (showBack) 1 else 0,
        pageCount = { if (showBack) 3 else 1 }
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (!showBack) return@collect
            when (page) {
                0 -> if (hasPrevious()) onNavigateBack() else pagerState.animateScrollToPage(1)
                2 -> { onSkip(); pagerState.animateScrollToPage(1) }
            }
        }
    }

    Column(modifier = modifier) {
        // recommend layout: [back-trigger | poster | skip-trigger]
        // search layout:    [poster]
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = showBack,
            modifier = Modifier
                .fillMaxWidth()
                .weight(10f)
        ) { page ->
            when {
                showBack && page == 0 -> BackTriggerPage(modifier = Modifier.fillMaxSize())
                showBack && page == 2 -> SkipTriggerPage(modifier = Modifier.fillMaxSize())
                else -> {
                    // Poster page — single tap opens IMDB, long-press triggers Assess mode
                    val pulseAlpha = rememberAssessPulseAlpha(isAssessing)
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                onClick = { title.imdbId?.let { openUrl("https://www.imdb.com/title/$it/") } },
                                onLongClick = onLongPressAssess,
                                onDoubleClick = onDoubleTap
                            )
                    ) {
                        AsyncImage(
                            model = title.posterUrl(500),
                            contentDescription = title.title,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                        )
                    }
                }
            }
        }

        // Title banner, anchored just below the poster, away from the bottom rating bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .combinedClickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {},
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(title.title))
                        ToastManager.show("Copied to clipboard")
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = buildAnnotatedString {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(title.title)
                    pop()
                    append(" (${title.year})")
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
            )
            title.runtime?.let { mins ->
                val h = mins / 60
                val m = mins % 60
                Text(
                    text = if (h > 0) "${h}h ${m}m" else "${m}m",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            Icon(
                imageVector = if (title.mediaType == MediaType.TV) Icons.Filled.Tv else Icons.Filled.Movie,
                contentDescription = if (title.mediaType == MediaType.TV) "TV" else "Film",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.CenterVertically)
            )
        }
        }
    }
}

@Composable
private fun BackTriggerPage(modifier: Modifier = Modifier) {
    Box(modifier = modifier)
}

@Composable
private fun SkipTriggerPage(modifier: Modifier = Modifier) {
    Box(modifier = modifier)
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isActive: Boolean = false,
    content: @Composable () -> Unit
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surface
    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                       else if (!enabled) Color.White.copy(alpha = 0.35f)
                       else Color.White

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled || isActive, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun MediaTypePill(mediaType: MediaType, small: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = if (small) 8.dp else 8.dp, vertical = if (small) 6.dp else 3.dp)
    ) {
        Text(
            text = if (mediaType == MediaType.TV) "TV" else "Film",
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun TierSelector(
    activeTiers: Set<Int>,
    onSelect: (Int) -> Unit,
    onClear: (() -> Unit)? = null,
    onLongPress: ((Int) -> Unit)? = null,
    segmentLabels: Map<Int, String> = emptyMap(),
    assessedTier: Int? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListEntryParser.TIERS.forEach { n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (segmentLabels.isNotEmpty()) {
                    Text(
                        text = segmentLabels[n] ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                RatingCircleButton(
                    isActive = n in activeTiers,
                    isAssessed = n == assessedTier,
                    onClick = {
                        if (activeTiers == setOf(n) && onClear != null) onClear() else onSelect(n)
                    },
                    onLongClick = onLongPress?.let { press -> { press(n) } }
                ) {
                    Text(text = ListEntryParser.tierLabel(n), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** For each tier, a compact "x-y" (or "x") label summarizing the numeric season ranges rated at that tier. */
private fun segmentLabelsByTier(segments: List<ShowSegment>): Map<Int, String> =
    segments.filter { it.seasonStart != null }
        .groupBy { it.tier }
        .mapValues { (_, segs) ->
            segs.joinToString(", ") { s ->
                if (s.seasonStart == s.seasonEnd) "${s.seasonStart}" else "${s.seasonStart}-${s.seasonEnd}"
            }
        }

@Composable
private fun SeasonPickerBar(
    seasonRange: IntRange,
    from: Int?,
    to: Int?,
    ratedTiers: Set<Int>,
    selectedTier: Int?,
    segmentLabels: Map<Int, String>,
    onFromChange: (Int?) -> Unit,
    onToChange: (Int?) -> Unit,
    onSelectTier: (Int) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 9.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cancel",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onCancel)
                )
                Text("Pick seasons", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Submit",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onSubmit)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SeasonStepper(label = "From", value = from, range = seasonRange, sentinelBelow = true, onValueChange = onFromChange)
                SeasonStepper(label = "To", value = to, range = seasonRange, sentinelAbove = true, onValueChange = onToChange)
            }
            TierSelector(
                activeTiers = ratedTiers + setOfNotNull(selectedTier),
                onSelect = onSelectTier,
                segmentLabels = segmentLabels
            )
        }
    }
}

/**
 * A stepper whose value can also be "X" (null) at one end — below [range] for "From" (meaning
 * "no lower bound picked"), above [range] for "To". Both sides at "X" means this tier is being
 * skipped entirely; exactly one "X" means a single-season pick using the other side's value.
 */
@Composable
private fun SeasonStepper(
    label: String,
    value: Int?,
    range: IntRange,
    sentinelBelow: Boolean = false,
    sentinelAbove: Boolean = false,
    onValueChange: (Int?) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                when {
                    value == null && sentinelAbove -> onValueChange(range.last)
                    value != null && value > range.first -> onValueChange(value - 1)
                    value == range.first && sentinelBelow -> onValueChange(null)
                }
            }) {
                Text("–", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = value?.toString() ?: "X",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(28.dp)
            )
            IconButton(onClick = {
                when {
                    value == null && sentinelBelow -> onValueChange(range.first)
                    value != null && value < range.last -> onValueChange(value + 1)
                    value == range.last && sentinelAbove -> onValueChange(null)
                }
            }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun RatingBottomBar(
    activeTiers: Set<Int>,
    segmentLabels: Map<Int, String> = emptyMap(),
    isUploading: Boolean,
    uploadError: Boolean,
    assessedTier: Int? = null,
    onSetRating: (Int) -> Unit,
    onClearRating: () -> Unit,
    onLongPressTier: ((Int) -> Unit)? = null
) {
    Surface(tonalElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TierSelector(
                activeTiers = activeTiers,
                onSelect = onSetRating,
                onClear = onClearRating,
                onLongPress = onLongPressTier,
                segmentLabels = segmentLabels,
                assessedTier = assessedTier
            )
            when {
                isUploading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text(
                        "Saving…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uploadError -> Text(
                    "Upload failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RatingCircleButton(
    isActive: Boolean,
    isAssessed: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(APP_CORNER_SHAPE)
            .background(bg)
            .then(
                if (isAssessed) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), APP_CORNER_SHAPE)
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            content()
        }
    }
}

private fun countryCodeToName(code: String): String =
    java.util.Locale("", code).displayCountry.takeIf { it.isNotBlank() } ?: code
