package site.whitezaak.wearpod.presentation.navigation

import android.net.Uri
import android.util.Base64

private const val ROUTE_CODEC_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

private fun encodeRouteValue(raw: String): String {
    if (raw.isEmpty()) return "_"
    return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), ROUTE_CODEC_FLAGS)
}

private fun decodeRouteValue(encoded: String): String {
    if (encoded == "_") return ""
    return try {
        val decoded = String(Base64.decode(encoded, ROUTE_CODEC_FLAGS), Charsets.UTF_8)
        if (decoded.startsWith("http%3A", ignoreCase = true) || decoded.startsWith("https%3A", ignoreCase = true)) {
            Uri.decode(decoded)
        } else {
            decoded
        }
    } catch (_: IllegalArgumentException) {
        // Backward compatibility for previously encoded URI-style routes.
        Uri.decode(encoded)
    }
}

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Feed : Screen("feed/{podcastIndex}") {
        fun createRoute(podcastIndex: Int) = "feed/$podcastIndex"
    }
    object Player : Screen("player/{episodeUrl}") {
        fun createRoute(episodeUrl: String) = "player/${encodeRouteValue(episodeUrl)}"
        fun decodeRouteArg(encodedValue: String): String = decodeRouteValue(encodedValue)
    }
    object Library : Screen("library")
    object HomeFeed : Screen("home_feed")
    object Downloads : Screen("downloads")
    object Settings : Screen("settings")
    object SettingsImportOpml : Screen("settings_import_opml")
    object SettingsLanguage : Screen("settings_language")
    object SettingsAbout : Screen("settings_about")
    object Playlist : Screen("playlist")
    object EpisodeDetail : Screen("episode_detail/{episodeUrl}") {
        fun createRoute(episodeUrl: String) = "episode_detail/${encodeRouteValue(episodeUrl)}"
        fun decodeRouteArg(encodedValue: String): String = decodeRouteValue(encodedValue)
    }
    object SleepTimer : Screen("sleep_timer")
}
