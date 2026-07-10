package com.moviesrecommender.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.moviesrecommender.navigation.Screen
import com.moviesrecommender.ui.components.BusyOverlay
import com.moviesrecommender.ui.components.TitleRow

@Composable
fun WishlistScreen(navController: NavHostController) {
    val viewModel: WishlistViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val pendingRemovalId by viewModel.pendingRemovalId.collectAsState()
    val assessingId by viewModel.assessingId.collectAsState()
    val assessedTiers by viewModel.assessedTiers.collectAsState()
    val isAssessing = assessingId != null

    var showCancelAssessConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = isAssessing) { showCancelAssessConfirm = true }
    if (showCancelAssessConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelAssessConfirm = false },
            title = { Text("Cancel assessment?") },
            text = { Text("This will stop the current assessment.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelAssessConfirm = false
                    viewModel.cancelAssess()
                }) { Text("Yes, cancel") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAssessConfirm = false }) { Text("Keep going") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val state = uiState) {
            is WishlistUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is WishlistUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is WishlistUiState.Loaded -> {
                // Red conflict banner — shown when any starred title is already watched (rated 1–4)
                if (state.conflicts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onConflictBadgeTap() }
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = if (state.conflicts.size == 1)
                                "${state.conflicts[0].title.title} is already in your list"
                            else
                                "${state.conflicts.size} wishlist titles are already in your list",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (state.items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Your wishlist is empty",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.title.id }) { item ->
                            WishlistRow(
                                item = item,
                                isPendingRemoval = pendingRemovalId == item.title.id,
                                assessedTier = assessedTiers[item.title.id],
                                isAssessing = assessingId == item.title.id,
                                onRowClick = {
                                    navController.navigate(
                                        Screen.Preview.createRoute(
                                            item.title.id,
                                            item.title.mediaType.name,
                                            "wishlist"
                                        )
                                    )
                                },
                                onLongPress = {
                                    viewModel.onLongPressAssess(
                                        item.title.id,
                                        item.title.title,
                                        item.title.year,
                                        item.rating
                                    )
                                },
                                onStarTap = { viewModel.onStarTap(item.title.id) }
                            )
                        }
                    }
                }
            }
        }
    }
    BusyOverlay(isBusy = isAssessing, message = "Assessment in Progress... Plz wait")
    }
}

@Composable
private fun WishlistRow(
    item: WishlistItem,
    isPendingRemoval: Boolean,
    assessedTier: Int?,
    isAssessing: Boolean,
    onRowClick: () -> Unit,
    onLongPress: () -> Unit,
    onStarTap: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TitleRow(
                title = item.title,
                rating = item.rating,
                onClick = onRowClick,
                showAbsentBadge = false,
                assessedTier = assessedTier,
                isAssessing = isAssessing,
                onLongPress = onLongPress
            )
        }
        IconButton(
            onClick = onStarTap,
            modifier = Modifier.padding(end = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Remove from wishlist",
                tint = if (isPendingRemoval) Color.Red
                       else MaterialTheme.colorScheme.primary
            )
        }
    }
}

