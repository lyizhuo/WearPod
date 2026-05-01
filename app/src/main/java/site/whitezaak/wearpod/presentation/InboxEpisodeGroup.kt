package site.whitezaak.wearpod.presentation

import androidx.compose.runtime.Immutable
import site.whitezaak.wearpod.domain.Episode

@Immutable
data class InboxEpisodeGroup(
    val pubDate: String,
    val episodes: List<Episode>,
)
