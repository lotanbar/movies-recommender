package com.moviesrecommender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.navigation.Screen
import com.moviesrecommender.util.ToastManager

private val BUTTON_HEIGHT = 56.dp
private val CORNER = RoundedCornerShape(12.dp)

@Composable
fun ActionsScreen(navController: NavHostController) {
    val app = MoviesRecommenderApp.instance
    var specText by remember { mutableStateOf(app.recommendSpec) }
    var countInput by remember { mutableStateOf(app.anthropicService.authManager.getRecommendCount().toString()) }

    fun resolvedCount(): Int = countInput.toIntOrNull()?.coerceIn(1, 50)
        ?: app.anthropicService.authManager.getRecommendCount()

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
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { navController.navigate(Screen.Usage.route) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = "Usage stats",
                    modifier = Modifier.size(48.dp)
                )
            }

            IconButton(
                onClick = { navController.navigate(Screen.Setup.createRoute(showContinueAnyway = false)) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Setup",
                    modifier = Modifier.size(48.dp)
                )
            }
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
                modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
                shape = CORNER
            ) {
                Text(text = "Search", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            }

            Button(
                onClick = { guardedNavigate(Screen.Wishlist.route) },
                modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
                shape = CORNER
            ) {
                Text(text = "Wishlist", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            }

            HorizontalDivider()

            // Recommend spec: [titles about: input]
            OutlinedTextField(
                value = specText,
                onValueChange = { specText = it; app.recommendSpec = it },
                modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
                singleLine = true,
                shape = CORNER,
                placeholder = {
                    Text(
                        "titles about: ...",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            )

            // [Recommend] [number input] — kept at the bottom of the action list
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val parsed = countInput.toIntOrNull()
                        if (parsed != null && parsed in 1..50) {
                            app.anthropicService.authManager.setRecommendCount(parsed)
                        } else {
                            countInput = app.anthropicService.authManager.getRecommendCount().toString()
                        }
                        guardedNavigate(Screen.Recommend.route)
                    },
                    modifier = Modifier.weight(1f).height(BUTTON_HEIGHT),
                    shape = CORNER
                ) {
                    Text(text = "Recommend", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                }

                OutlinedTextField(
                    value = countInput,
                    onValueChange = { input ->
                        if (input.length <= 2 && input.all { it.isDigit() }) countInput = input
                    },
                    modifier = Modifier.width(72.dp).height(BUTTON_HEIGHT),
                    singleLine = true,
                    shape = CORNER,
                    textStyle = MaterialTheme.typography.titleLarge,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}


