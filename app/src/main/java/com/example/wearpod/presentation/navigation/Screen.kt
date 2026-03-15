package com.example.wearpod.presentation.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Feed : Screen("feed/{podcastIndex}") {
        fun createRoute(podcastIndex: Int) = "feed/$podcastIndex"
    }
    object Player : Screen("player/{episodeUrl}") {
        fun createRoute(episodeUrl: String) = "player/$episodeUrl" // Base64 encoding maybe needed for real URLs
    }
    object Library : Screen("library")
    object HomeFeed : Screen("home_feed")
    object Downloads : Screen("downloads")
    object Settings : Screen("settings")
    object Playlist : Screen("playlist")
    object EpisodeDetail : Screen("episode_detail/{episodeUrl}") {
        fun createRoute(episodeUrl: String) = "episode_detail/$episodeUrl"
    }
}
