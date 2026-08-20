package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import site.whitezaak.wearpod.presentation.MainViewModel
import site.whitezaak.wearpod.domain.Episode

@Composable
fun FeedScreen(
    feedUrl: String,
    onEpisodeClick: (String) -> Unit
) {
    val viewModel: MainViewModel = viewModel()
    val context = LocalContext.current
    val episodes by viewModel.episodes.collectAsState()
    val isLoading by viewModel.isLoadingFeed.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val podcasts by viewModel.podcasts.collectAsState()
    // 订阅列表可能尚未加载（如断网冷启动后恢复），按 feedUrl 查找标题。
    val podcastTitle = podcasts.firstOrNull { it.feedUrl == feedUrl }?.title.orEmpty()
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
            // 同一 feed 可能出现重复 audioUrl（重发/合集），先去重避免 LazyColumn key 冲突崩溃。
            val dedupedEpisodes = remember(episodes) { episodes.distinctBy { it.audioUrl } }
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
