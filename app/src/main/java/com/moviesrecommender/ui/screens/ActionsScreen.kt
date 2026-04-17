package com.moviesrecommender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.navigation.Screen
import com.moviesrecommender.util.ToastManager

@Composable
fun ActionsScreen(navController: NavHostController) {
    val app = MoviesRecommenderApp.instance
    var easyMode by remember { mutableStateOf(app.recommendEasy) }

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
                .align(Alignment.BottomEnd)
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
            Button(
                onClick = { guardedNavigate(Screen.Search.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Search", style = MaterialTheme.typography.titleLarge)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { guardedNavigate(Screen.Recommend.route) },
                    modifier = Modifier.weight(0.8f)
                ) {
                    Text(text = "Recommend", style = MaterialTheme.typography.titleLarge)
                }
                if (easyMode) {
                    Button(
                        onClick = {
                            easyMode = false
                            app.recommendEasy = false
                        },
                        modifier = Modifier.weight(0.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text(text = "E", style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            easyMode = true
                            app.recommendEasy = true
                        },
                        modifier = Modifier.weight(0.2f)
                    ) {
                        Text(text = "E", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Button(
                onClick = { guardedNavigate(Screen.Wishlist.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Wishlist", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
