package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter

@Composable
fun FeedScreen(
    podcastTitle: String,
    episodes: List<Episode>,
    isLoading: Boolean,
    isOnline: Boolean,
    onEpisodeClick: (String) -> Unit
) {
    val context = LocalContext.current
    // 去重必须 remember 在 composable 上下文：ScalingLazyListScope 的 content lambda 不是 @Composable
    val dedupedEpisodes = remember(episodes) { episodes.distinctBy { it.audioUrl } }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    ScreenListScaffold(
        title = podcastTitle,
        modifier = Modifier.fillMaxWidth(),
        listState = listState,
    ) {

        if (episodes.isEmpty() && isLoading && isOnline) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (episodes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.no_episodes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(items = dedupedEpisodes, key = { it.audioUrl }) { episode ->
                val metaText = EpisodeTextFormatter.formatEpisodeMeta(context, episode.pubDate, episode.duration)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEpisodeClick(episode.audioUrl) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = {
                        Text(
                            text = episode.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    secondaryLabel = {
                        if (metaText.isNotEmpty()) {
                            Text(text = metaText, maxLines = 1)
                        }
                    }
                )
            }
        }
    }
}
