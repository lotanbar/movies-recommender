package com.moviesrecommender.navigation

sealed class Screen(val route: String) {
    object Splash    : Screen("splash")
    object Setup     : Screen("setup/{showContinueAnyway}") {
        fun createRoute(showContinueAnyway: Boolean) = "setup/$showContinueAnyway"
    }
    object Actions   : Screen("actions")
    object Search    : Screen("search")
    object Recommend : Screen("recommend")
    object Wishlist  : Screen("wishlist")
    object Preview   : Screen("preview/{tmdbId}/{mediaType}/{source}") {
        fun createRoute(id: Int, mediaType: String, source: String = "search") =
            "preview/$id/$mediaType/$source"
    }
}
