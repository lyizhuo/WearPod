package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.domain.Podcast

@Composable
fun FeedScreen(
    podcast: Podcast,
    episodes: List<Episode>,
    isLoading: Boolean,
    onEpisodeClick: (String) -> Unit
) {
    val context = LocalContext.current
    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState()

    ScreenListScaffold(
        title = podcast.title,
        modifier = Modifier.fillMaxWidth(),
        listState = listState,
    ) {

        if (episodes.isEmpty() && isLoading) {
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
            items(items = episodes, key = { it.audioUrl }) { episode ->
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