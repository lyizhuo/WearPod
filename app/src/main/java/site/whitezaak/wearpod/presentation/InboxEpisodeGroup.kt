package site.whitezaak.wearpod.presentation

import site.whitezaak.wearpod.domain.Episode

data class InboxEpisodeGroup(
    val pubDate: String,
    val episodes: List<Episode>,
)
