package com.moviesrecommender.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.moviesrecommender.AppViewModel
import com.moviesrecommender.ui.screens.ActionsScreen
import com.moviesrecommender.ui.screens.PreviewScreen
import com.moviesrecommender.ui.screens.RecommendScreen
import com.moviesrecommender.ui.screens.SearchScreen
import com.moviesrecommender.ui.screens.SetupScreen
import com.moviesrecommender.ui.screens.SplashScreen
import com.moviesrecommender.ui.screens.WishlistScreen

@Composable
fun AppNavigation(navController: NavHostController, appViewModel: AppViewModel) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(navController)
        }
        composable(
            route = Screen.Setup.route,
            arguments = listOf(
                navArgument("showContinueAnyway") { type = NavType.BoolType }
            )
        ) { backStackEntry ->
            val showContinueAnyway = backStackEntry.arguments?.getBoolean("showContinueAnyway") ?: false
            SetupScreen(navController, appViewModel, showContinueAnyway = showContinueAnyway)
        }
        composable(Screen.Actions.route) {
            ActionsScreen(navController)
        }
        composable(Screen.Search.route) {
            SearchScreen(navController)
        }
        composable(Screen.Recommend.route) {
            RecommendScreen(navController)
        }
        composable(Screen.Wishlist.route) {
            WishlistScreen(navController)
        }
        composable(
            route = Screen.Preview.route,
            arguments = listOf(
                navArgument("tmdbId") { type = NavType.IntType },
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("source") { type = NavType.StringType; defaultValue = "search" }
            )
        ) { backStackEntry ->
            val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: 0
            val mediaType = backStackEntry.arguments?.getString("mediaType") ?: "MOVIE"
            val source = backStackEntry.arguments?.getString("source") ?: "search"
            PreviewScreen(navController, tmdbId, mediaType, source)
        }
    }
}
