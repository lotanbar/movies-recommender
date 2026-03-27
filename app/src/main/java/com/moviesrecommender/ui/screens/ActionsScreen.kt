package com.moviesrecommender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.navigation.Screen
import com.moviesrecommender.util.ToastManager

@Composable
fun ActionsScreen(navController: NavHostController) {
    val app = MoviesRecommenderApp.instance

    fun allConfigured(): Boolean =
        app.dropboxService.isAuthenticated() &&
        app.dropboxService.authManager.getListPath() != null &&
        app.tmdbService.isConfigured() &&
        app.anthropicService.isConfigured()

    fun guardedNavigate(route: String) {
        if (allConfigured()) navController.navigate(route)
        else ToastManager.show("Please complete the setup.")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        IconButton(
            onClick = { navController.navigate(Screen.Setup.createRoute(showContinueAnyway = false)) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Setup",
                modifier = Modifier.size(48.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            listOf(
                "Search"    to Screen.Search.route,
                "Recommend" to Screen.Recommend.route,
                "Wishlist"  to Screen.Wishlist.route
            ).forEach { (label, route) ->
                Button(
                    onClick = { route?.let { guardedNavigate(it) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = label, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
