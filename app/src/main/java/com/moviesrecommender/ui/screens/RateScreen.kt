package com.moviesrecommender.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.moviesrecommender.navigation.Screen

@Composable
fun RateScreen(navController: NavHostController) {
    val viewModel = viewModel<RateViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    // Navigate to Preview for each title in the batch
    LaunchedEffect("preview") {
        viewModel.navigateToPreview.collect { (tmdbId, mediaType) ->
            navController.navigate(Screen.Preview.createRoute(tmdbId, mediaType, "rate"))
        }
    }

    // Back-pressed handler uploads whatever we have and returns to Actions
    LaunchedEffect("actions") {
        viewModel.navigateToActions.collect {
            navController.popBackStack(Screen.Actions.route, inclusive = false)
        }
    }

    // Intercept system back — upload partial ratings and go to Actions
    BackHandler { viewModel.onBackPressed() }

    // Detect resume from PreviewScreen to advance to the next title
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumedFromPreview()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val state = uiState) {
            is RateUiState.InProgress -> { /* navigating between titles — nothing to show */ }
            is RateUiState.Loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Finding titles to rate…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            is RateUiState.Uploading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Saving your ratings…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            is RateUiState.Error -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    if (state.debugInfo != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.debugInfo,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Start
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { viewModel.startBatch() }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}
