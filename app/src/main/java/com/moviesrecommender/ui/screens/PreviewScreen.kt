package com.moviesrecommender.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.moviesrecommender.navigation.Screen
import coil.compose.AsyncImage
import com.moviesrecommender.R
import com.moviesrecommender.data.remote.tmdb.MediaType
import com.moviesrecommender.util.ToastManager

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

    // Rate mode: after the user taps a rating, navigate directly to next title or pop to RateScreen when batch done
    if (source == "rate") {
        LaunchedEffect(Unit) {
            viewModel.autoAdvance.collect { next ->
                if (next != null) {
                    // Replace current preview with the next title — no RateScreen flash
                    navController.navigate(Screen.Preview.createRoute(next.first, next.second, "rate")) {
                        popUpTo(Screen.Preview.createRoute(tmdbId, mediaType, "rate")) { inclusive = true }
                    }
                } else {
                    // Batch done — pop back to RateScreen to start the next batch
                    navController.popBackStack()
                }
            }
        }
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

    // Recommend / Rate: back gesture goes straight to the main Actions screen
    if (source == "recommend" || source == "rate") {
        BackHandler {
            navController.popBackStack(Screen.Actions.route, inclusive = false)
        }
    }

    Scaffold(
        bottomBar = {
            val loaded = uiState as? PreviewUiState.Loaded
            if (loaded != null) {
                RatingBottomBar(
                    rating = loaded.rating,
                    isUploading = loaded.isUploading,
                    uploadError = loaded.uploadError,
                    onSetRating = viewModel::setRating
                )
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
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is PreviewUiState.Error -> {
                    Text(
                        text = state.message,
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is PreviewUiState.Loaded -> {
                    LoadedContent(
                        state = state,
                        source = source,
                        hasPrevious = viewModel::hasPrevious,
                        onNavigateBack = viewModel::navigateBack,
                        onDoubleTap = viewModel::onDoubleTap,
                        onSkipOrNotSeen = viewModel::onSkipOrNotSeen,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LoadedContent(
    state: PreviewUiState.Loaded,
    source: String,
    hasPrevious: () -> Boolean,
    onNavigateBack: () -> Unit,
    onDoubleTap: () -> Unit,
    onSkipOrNotSeen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = state.title
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    fun openUrl(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    val allPosters = remember(title.id) { title.allPosterUrls(500) }
    var thumbnailIndex by remember(title.id) { mutableStateOf(0) }
    var showDetails by remember(title.id) { mutableStateOf(false) }
    var trailerScrolling by remember { mutableStateOf(false) }

    // recommend/rate: pages = [back(0) | poster(1) | skip(2)], start at 1
    // search: pages = [poster(0) | details(1)], start at 0
    val showBack = source == "recommend" || source == "rate"
    val pagerState = rememberPagerState(
        initialPage = if (showBack) 1 else 0,
        pageCount = { if (showBack) 3 else 2 }
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (!showBack) return@collect
            when (page) {
                0 -> if (hasPrevious()) onNavigateBack() else pagerState.animateScrollToPage(1)
                2 -> { onSkipOrNotSeen(); pagerState.animateScrollToPage(1) }
            }
        }
    }

    Column(modifier = modifier) {
        // Title fixed at top
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                    .clickable {
                        clipboardManager.setText(AnnotatedString(title.title))
                        ToastManager.show("Copied to clipboard")
                    }
            )
            title.runtime?.let { mins ->
                val h = mins / 60
                val m = mins % 60
                Text(
                    text = if (h > 0) "${h}h ${m}m" else "${m}m",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (title.mediaType == MediaType.TV) Icons.Filled.Tv else Icons.Filled.Movie,
                contentDescription = if (title.mediaType == MediaType.TV) "TV" else "Film",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        // recommend/rate layout: [back-trigger | poster | skip-trigger]
        // search layout:         [poster | details]
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = showBack && !trailerScrolling,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when {
                showBack && page == 0 -> BackTriggerPage(modifier = Modifier.fillMaxSize())
                showBack && page == 2 -> SkipTriggerPage(modifier = Modifier.fillMaxSize())
                !showBack && page == 1 -> DetailsPage(
                    state = state,
                    onOpenUrl = ::openUrl,
                    onTrailerScrollChange = { trailerScrolling = it },
                    modifier = Modifier.fillMaxSize()
                )
                else -> {
                    // Poster page (with optional details overlay)
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                onClick = {
                                    if (!showDetails && allPosters.size > 1)
                                        thumbnailIndex = (thumbnailIndex + 1) % allPosters.size
                                },
                                onDoubleClick = { showDetails = !showDetails },
                                onLongClick = onDoubleTap
                            )
                    ) {
                        if (showDetails) {
                            DetailsPage(
                                state = state,
                                onOpenUrl = ::openUrl,
                                onTrailerScrollChange = { trailerScrolling = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            AsyncImage(
                                model = allPosters.getOrNull(thumbnailIndex) ?: title.posterUrl(500),
                                contentDescription = title.title,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (allPosters.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    allPosters.indices.forEach { i ->
                                        Box(
                                            modifier = Modifier
                                                .size(if (i == thumbnailIndex) 8.dp else 6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Color.White.copy(alpha = if (i == thumbnailIndex) 0.9f else 0.4f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackTriggerPage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                "Previous title",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun SkipTriggerPage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Skip",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                "Not watched",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun DetailsPage(
    state: PreviewUiState.Loaded,
    onOpenUrl: (String) -> Unit,
    onTrailerScrollChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val title = state.title

    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // no inner padding — trailer fills flush to card edges
    ) {
        // Scrollable content
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .drawWithContent {
                    drawContent()
                    if (scrollState.maxValue > 0) {
                        val trackPad = 3.dp.toPx()
                        val thumbW = 3.dp.toPx()
                        val viewport = size.height
                        val total = viewport + scrollState.maxValue
                        val thumbH = (viewport / total) * viewport
                        val thumbY = (scrollState.value.toFloat() / scrollState.maxValue) * (viewport - thumbH)
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.35f),
                            topLeft = Offset(size.width - thumbW - trackPad, thumbY),
                            size = Size(thumbW, thumbH),
                            cornerRadius = CornerRadius(thumbW / 2)
                        )
                    }
                }
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Trailers
            if (title.trailerKeys.isEmpty()) {
                Text(
                    "No Trailer Found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            } else {
                val trailerState = rememberLazyListState()
                LaunchedEffect(trailerState.isScrollInProgress) {
                    onTrailerScrollChange(trailerState.isScrollInProgress)
                }
                // Consume all horizontal scroll/fling so it never leaks to the parent pager
                val consumeHorizontal = remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                            Offset(available.x, 0f)
                        override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
                            Velocity(available.x, 0f)
                    }
                }
                LazyRow(
                    state = trailerState,
                    flingBehavior = rememberSnapFlingBehavior(trailerState),
                    modifier = Modifier.fillMaxWidth().nestedScroll(consumeHorizontal)
                ) {
                    items(title.trailerKeys) { key ->
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black)
                                .clickable { onOpenUrl("https://www.youtube.com/watch?v=$key") },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = "https://img.youtube.com/vi/$key/hqdefault.jpg",
                                contentDescription = "Trailer",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play trailer",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Text content with horizontal padding
            Column(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {

            // Genres: max 3 tags AND max 3 total words, + Wikipedia icon
            val displayGenres = buildList {
                var wordCount = 0
                for (genre in title.genres) {
                    if (size >= 3) break
                    val words = genre.trim().split("\\s+".toRegex()).size
                    if (wordCount + words > 3) break
                    add(genre)
                    wordCount += words
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                displayGenres.forEach { genre ->
                    AssistChip(
                        onClick = {},
                        label = { Text(genre, style = MaterialTheme.typography.bodySmall) }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Wikipedia icon stuck to right
                if (!state.wikipediaReady) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (state.wikipediaUrl != null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wikipedia),
                        contentDescription = "Wikipedia",
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { onOpenUrl(state.wikipediaUrl) }
                    )
                }
            }

            // Overview (max 2 sentences)
            title.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                val sentences = overview.split(Regex("(?<=[.!?])\\s+"))
                val short = sentences.take(2).joinToString(" ")
                Text(short, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }

            // Awards
            when {
                !state.awardsReady -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp
                )
                state.awards.isEmpty() -> Text(
                    "No notable awards",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Won ${state.awards.size} Notable Award${if (state.awards.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    state.awards.forEach { award ->
                        val label = if (award.year != null) "· ${award.name} (${award.year})"
                                    else "· ${award.name}"
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                }
            }

            } // end padded text content Column

        }
    }
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
private fun RatingBottomBar(
    rating: Int?,
    isUploading: Boolean,
    uploadError: Boolean,
    onSetRating: (Int) -> Unit
) {
    Surface(tonalElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                (1..4).forEach { n ->
                    RatingCircleButton(
                        isActive = rating == n,
                        onClick = { onSetRating(if (rating == n) 0 else n) }
                    ) {
                        Text(text = "$n", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
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

@Composable
private fun RatingCircleButton(
    isActive: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            content()
        }
    }
}

private fun countryCodeToName(code: String): String =
    java.util.Locale("", code).displayCountry.takeIf { it.isNotBlank() } ?: code
