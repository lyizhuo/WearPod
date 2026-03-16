package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.*
import com.example.wearpod.domain.Episode
import com.example.wearpod.domain.Podcast

@Composable
fun FeedScreen(
    podcast: Podcast,
    episodes: List<Episode>,
    isLoading: Boolean,
    onEpisodeClick: (String) -> Unit
) {
    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        // 【核心修复 2】绑定状态
        state = listState,
        // 【核心修复 3】显式禁用导致闪退的震动反馈行为
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
            scrollableState = listState,
            hapticFeedbackEnabled = false
        )
    ) {
        item {
            ListHeader {
                Text(text = podcast.title, textAlign = TextAlign.Center)
            }
        }

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
                    Text("No Episodes", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(items = episodes, key = { it.audioUrl }) { episode ->
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
                            text = "${episode.pubDate} • ${episode.duration}",
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}