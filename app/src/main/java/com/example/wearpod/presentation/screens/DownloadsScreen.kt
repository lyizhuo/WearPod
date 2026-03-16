package com.example.wearpod.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.wearpod.domain.Episode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DownloadsScreen(
    downloads: List<Episode>,
    onEpisodeClick: (Episode) -> Unit,
    onRemoveDownload: (Episode) -> Unit
) {
    if (downloads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No Downloads",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    } else {
        // 【核心修复 1】定义列表状态，用于接管滚动控制
        val listState = rememberScalingLazyListState()

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 【核心修复 2】绑定状态
            state = listState,
            // 【核心修复 3】手动定义旋转行为，彻底关闭导致闪退的震动反馈 (Haptics)
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
                scrollableState = listState,
                hapticFeedbackEnabled = false
            )
        ) {
            item {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(downloads, key = { it.audioUrl }) { episode ->
                val offsetX = remember { Animatable(0f) }
                val scope = rememberCoroutineScope()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (offsetX.value > 150f || offsetX.value < -150f) {
                                        scope.launch {
                                            offsetX.animateTo(
                                                targetValue = if (offsetX.value > 0) 1000f else -1000f,
                                                animationSpec = tween(200)
                                            )
                                            onRemoveDownload(episode)
                                        }
                                    } else {
                                        scope.launch { offsetX.animateTo(0f, tween(200)) }
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo(offsetX.value + dragAmount)
                                    }
                                }
                            )
                        }
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                ) {
                    Button(
                        onClick = { onEpisodeClick(episode) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = episode.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "${episode.pubDate} • ${episode.duration}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}