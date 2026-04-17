package com.moviesrecommender.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.moviesrecommender.navigation.Screen
import coil.compose.AsyncImage
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

    // Recommend: back gesture goes straight to the main Actions screen.
    if (source == "recommend") {
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
                    onSetRating = viewModel::setRating,
                    onClearRating = viewModel::clearRating
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
                        onSkip = viewModel::onSkip,
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
    onSkip: () -> Unit,
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

        // recommend layout: [back-trigger | poster | skip-trigger]
        // search layout:    [poster]
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = showBack,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when {
                showBack && page == 0 -> BackTriggerPage(modifier = Modifier.fillMaxSize())
                showBack && page == 2 -> SkipTriggerPage(modifier = Modifier.fillMaxSize())
                else -> {
                    // Poster page — single tap opens IMDB
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                onClick = { title.imdbId?.let { openUrl("https://www.imdb.com/title/$it/") } },
                                onDoubleClick = onDoubleTap
                            )
                    ) {
                        AsyncImage(
                            model = title.posterUrl(500),
                            contentDescription = title.title,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
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
    onSetRating: (Int) -> Unit,
    onClearRating: () -> Unit
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
                        onClick = {
                            if (rating == n) onClearRating() else onSetRating(n)
                        }
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
