package site.whitezaak.wearpod.domain

import androidx.compose.runtime.Immutable

@Immutable
data class Podcast(
    val title: String,
    val feedUrl: String,
    val imageUrl: String = "",
    val description: String = ""
)

@Immutable
data class Episode(
    val title: String,
    val audioUrl: String,
    val duration: String = "",
    val pubDate: String = "",
    val imageUrl: String = "",
    val description: String = "",
    val podcastTitle: String = "",
    val podcastImageUrl: String = ""
)
