package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import site.whitezaak.wearpod.presentation.InboxEpisodeGroup

@Composable
fun InBoxScreen(
    episodeGroups: List<InboxEpisodeGroup>,
    hasEpisodes: Boolean,
    hasMoreEpisodes: Boolean,
    isRefreshing: Boolean,
    currentPlayingEpisode: Episode?,
    onEpisodeClick: (String) -> Unit,
    onLoadMoreClick: () -> Unit,
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val localeKey = LocalConfiguration.current.locales.toLanguageTags()

    ScreenListScaffold(
        title = stringResource(R.string.nav_home),
        modifier = Modifier.fillMaxWidth(),
        listState = listState,
    ) {

        episodeGroups.forEach { group ->
            val date = EpisodeTextFormatter.formatPubDate(group.pubDate)
            item {
                ListHeader {
                    Text(text = date, textAlign = TextAlign.Center)
                }
            }
            items(
                items = group.episodes,
                key = { episode -> episode.audioUrl }
            ) { episode ->
                EpisodeCard(
                    episode = episode,
                    onClick = { onEpisodeClick(episode.audioUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    playbackState = if (episode.audioUrl == currentPlayingEpisode?.audioUrl)
                        EpisodePlaybackState.CURRENTLY_PLAYING else EpisodePlaybackState.DEFAULT
                )
            }
        }

        if (hasMoreEpisodes) {
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onLoadMoreClick,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = {
                        Text(
                            text = stringResource(R.string.load_more),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        if (!hasEpisodes) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    } else {
                        Text(stringResource(R.string.no_episodes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}