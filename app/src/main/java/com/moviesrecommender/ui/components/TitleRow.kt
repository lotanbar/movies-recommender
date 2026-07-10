package com.moviesrecommender.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.moviesrecommender.data.local.ListEntryParser
import com.moviesrecommender.data.remote.tmdb.Title
import com.moviesrecommender.ui.theme.RatingBadge

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TitleRow(
    title: Title,
    rating: Int?,
    onClick: () -> Unit,
    showAbsentBadge: Boolean = true,
    assessedTier: Int? = null,
    isAssessing: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    val pulseAlpha = rememberAssessPulseAlpha(isAssessing)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = title.posterUrl(width = 92),
            contentDescription = null,
            modifier = Modifier
                .width(52.dp)
                .height(78.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2
            )
            Text(
                text = "(${title.year})",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            rating != null -> RatingBadge(rating)
            assessedTier != null -> AssessBadge(assessedTier)
            showAbsentBadge -> RatingBadge(null)
        }
    }
}

@Composable
private fun RatingBadge(rating: Int?) {
    val badgeColor = when {
        rating == null -> MaterialTheme.colorScheme.surfaceVariant
        rating == 0 -> MaterialTheme.colorScheme.surfaceVariant
        else -> RatingBadge
    }
    val textColor = when {
        rating == null -> MaterialTheme.colorScheme.onSurfaceVariant
        rating == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(badgeColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rating?.let { ListEntryParser.tierLabel(it) } ?: "✕",
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}

/** Border-only circle marking a transient Assess-mode result — distinct from an actual saved [RatingBadge]. */
@Composable
private fun AssessBadge(tier: Int) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(2.dp, RatingBadge), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = ListEntryParser.tierLabel(tier),
            style = MaterialTheme.typography.labelLarge,
            color = RatingBadge
        )
    }
}
