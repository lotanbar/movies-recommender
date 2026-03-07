package com.moviesrecommender.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.moviesrecommender.navigation.Screen
import coil.compose.AsyncImage
import com.moviesrecommender.R
import com.moviesrecommender.data.remote.tmdb.MediaType

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
                    showSkip = source == "recommend" && loaded.rating == 0,
                    onSetNotSeen = viewModel::setNotSeen,
                    onSetRating = viewModel::setRating,
                    onSkip = viewModel::skip
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
                        onStarTap = viewModel::onStarTap,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadedContent(
    state: PreviewUiState.Loaded,
    onStarTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = state.title
    val context = LocalContext.current
    fun openUrl(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    val pagerState = rememberPagerState(pageCount = { 2 })

    Column(modifier = modifier) {
        // Title fixed at top
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = title.year.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MediaTypePill(title.mediaType)
        }

        // Pager fills all remaining space
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = title.posterUrl(500),
                        contentDescription = title.title,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Wishlist star — top-right of poster; double-tap to add/remove
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable { onStarTap() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isStarred) Icons.Filled.Bookmark
                                          else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Add to wishlist",
                            tint = if (state.starPendingConfirm) Color.Yellow else Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                else -> DetailsPage(
                    state = state,
                    onOpenUrl = ::openUrl,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsPage(
    state: PreviewUiState.Loaded,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val title = state.title
    val query = Uri.encode("${title.title} ${title.year}")

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Trailers
            if (title.trailerKeys.isEmpty()) {
                Text(
                    "No Trailer Found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            } else {
                val trailerState = rememberLazyListState()
                LazyRow(
                    state = trailerState,
                    flingBehavior = rememberSnapFlingBehavior(trailerState),
                    modifier = Modifier.fillMaxWidth()
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

            // Genres
            if (title.genres.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    title.genres.forEach { genre ->
                        AssistChip(
                            onClick = {},
                            label = { Text(genre, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }

            // Cast
            if (title.leadActors.isNotEmpty()) {
                Text(
                    "Starring: ${title.leadActors.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
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

            // Overview
            title.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(overview, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }

            // Crew & production
            val prodLine = listOfNotNull(
                title.productionCompany,
                title.country?.let { countryCodeToName(it) }
            ).joinToString(" · ")
            if (prodLine.isNotEmpty()) {
                Text(prodLine, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }

            title.director?.let {
                Text("Director: $it", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            title.writer?.let {
                Text("Writer: $it", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            if (title.producers.isNotEmpty()) {
                Text(
                    "Producer: ${title.producers.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Buttons pinned at bottom
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "YouTube",
                onClick = { onOpenUrl("https://www.youtube.com/results?search_query=$query") }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_youtube),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(28.dp)
                )
            }

            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Wikipedia",
                onClick = { state.wikipediaUrl?.let { onOpenUrl(it) } },
                enabled = state.wikipediaReady && state.wikipediaUrl != null
            ) {
                if (!state.wikipediaReady) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_wikipedia),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(28.dp)
                            .alpha(if (state.wikipediaUrl != null) 1f else 0.35f)
                    )
                }
            }

            ActionButton(
                modifier = Modifier.weight(1f),
                label = "IMDb",
                onClick = { title.imdbId?.let { onOpenUrl("https://www.imdb.com/title/$it") } },
                enabled = title.imdbId != null
            ) {
                Box(
                    modifier = Modifier.height(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "IMDb",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = Color(0xFFF5C518).copy(alpha = if (title.imdbId != null) 1f else 0.35f)
                    )
                }
            }
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
private fun MediaTypePill(mediaType: MediaType) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (mediaType == MediaType.TV) "TV" else "Film",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun RatingBottomBar(
    rating: Int?,
    isUploading: Boolean,
    uploadError: Boolean,
    showSkip: Boolean = false,
    onSetNotSeen: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSkip: () -> Unit = {}
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
                if (showSkip) {
                    RatingCircleButton(isActive = false, onClick = onSkip) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Skip",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    RatingCircleButton(
                        isActive = rating == 0,
                        onClick = onSetNotSeen
                    ) {
                        Icon(
                            imageVector = if (rating == 0) Icons.Filled.VisibilityOff
                                          else Icons.Outlined.VisibilityOff,
                            contentDescription = "Mark as not seen",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
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
