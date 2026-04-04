package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import site.whitezaak.wearpod.presentation.InboxEpisodeGroup

@Composable
fun InBoxScreen(
    episodeGroups: List<InboxEpisodeGroup>,
    hasEpisodes: Boolean,
    hasMoreEpisodes: Boolean,
    isRefreshing: Boolean,
    onEpisodeClick: (String) -> Unit,
    onLoadMoreClick: () -> Unit,
) {
    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val context = LocalContext.current
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
                val metaText = EpisodeTextFormatter.formatEpisodeMeta(context, "", episode.duration)
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
                        Text(
                            text = if (metaText.isNotEmpty()) {
                                context.getString(R.string.inbox_episode_meta, episode.podcastTitle, metaText)
                            } else {
                                episode.podcastTitle
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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