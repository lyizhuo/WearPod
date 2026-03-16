package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

@Composable
fun InBoxScreen(
    episodes: List<Episode>,
    hasMoreEpisodes: Boolean,
    isRefreshing: Boolean,
    onEpisodeClick: (String) -> Unit,
    onLoadMoreClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState()
    val groupedEpisodes = remember(episodes) { episodes.groupBy { it.pubDate } }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            // 【核心修复 2】绑定状态
            state = listState,
            // 【核心修复 3】显式禁用导致闪退的震动反馈
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
                scrollableState = listState,
                hapticFeedbackEnabled = false
            )
        ) {
            item {
                ListHeader {
                    Text(text = "Home", textAlign = TextAlign.Center)
                }
            }

            groupedEpisodes.forEach { (date, dailyEpisodes) ->
                item {
                    ListHeader {
                        Text(text = date, textAlign = TextAlign.Center)
                    }
                }
                items(
                    items = dailyEpisodes,
                    key = { episode -> episode.audioUrl }
                ) { episode ->
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
                                text = "${episode.podcastTitle} · ${episode.duration}",
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
                                text = "Load More",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        } else {
                            Text("No Episodes", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // Refresh Icon overlay at top right
        Box(modifier = Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopEnd) {
            val infiniteTransition = rememberInfiniteTransition()
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )

            Button(
                onClick = onRefreshClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp).graphicsLayer {
                        rotationZ = if (isRefreshing) angle else 0f
                    },
                    tint = if (isRefreshing) MaterialTheme.colorScheme.primary else Color.White
                )
            }
        }
    }
}