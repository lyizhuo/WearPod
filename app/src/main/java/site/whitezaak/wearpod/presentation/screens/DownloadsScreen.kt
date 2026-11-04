package site.whitezaak.wearpod.presentation.screens

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import kotlin.math.roundToInt

@Composable
fun DownloadsScreen(
    downloads: List<Episode>,
    downloading: List<Episode>,
    progressMap: State<Map<String, Float>>,
    currentPlayingEpisode: Episode?,
    onEpisodeClick: (Episode) -> Unit,
    onRemoveDownload: (Episode) -> Unit,
    onCancelDownload: (Episode) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0, initialCenterItemScrollOffset = 0)

    ScreenListScaffold(
        title = stringResource(R.string.nav_downloads),
        modifier = Modifier.fillMaxSize(),
        listState = listState,
    ) {

        if (downloads.isEmpty() && downloading.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.downloads_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }
            return@ScreenListScaffold
        }

        if (downloading.isNotEmpty()) {
            items(downloading, key = { it.audioUrl }) { episode ->
                // 只在本行读取进度 State：进度更新不会再让整页（含标题）重组。
                val progress = progressMap.value[episode.audioUrl] ?: 0f
                val metaText = remember(episode.duration) {
                    EpisodeTextFormatter.formatEpisodeMeta(context, "", episode.duration)
                }

                SwipeRevealActionRow(
                    actionContentDescription = stringResource(R.string.cd_cancel_download),
                    onAction = { onCancelDownload(episode) },
                ) { isOpen, close ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (isOpen) close() },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = {
                            Text(
                                text = episode.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        secondaryLabel = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (metaText.isNotEmpty()) {
                                        stringResource(R.string.inbox_episode_meta, episode.podcastTitle, metaText)
                                    } else {
                                        episode.podcastTitle
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        items(downloads, key = { it.audioUrl }) { episode ->
            SwipeRevealActionRow(
                actionContentDescription = stringResource(R.string.cd_delete_download),
                onAction = { onRemoveDownload(episode) },
            ) { isOpen, close ->
                EpisodeCard(
                    episode = episode,
                    onClick = {
                        if (isOpen) close() else onEpisodeClick(episode)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    playbackState = if (episode.audioUrl == currentPlayingEpisode?.audioUrl) {
                        EpisodePlaybackState.CURRENTLY_PLAYING
                    } else {
                        EpisodePlaybackState.DEFAULT
                    }
                )
            }
        }
    }
}

/**
 * 左滑露出右侧红色垃圾桶，再点垃圾桶才执行动作。
 * 第一段手势只负责“露出”，不会直接取消/删除，避免误触。
 */
@Composable
private fun SwipeRevealActionRow(
    actionContentDescription: String,
    onAction: () -> Unit,
    content: @Composable (isOpen: Boolean, close: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { SWIPE_ACTION_WIDTH.toPx() }
    val openThresholdPx = revealPx / 2f
    val backGestureGuardPx = remember(density) {
        with(density) {
            if (Build.VERSION.SDK_INT >= 34) 42.dp.toPx() else 28.dp.toPx()
        }
    }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val allowSwipe = remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // 红色动作块只在滑动/展开时组合，闭合状态不额外创建 IconButton，减少首帧负担。
        if (offsetX.value < -0.5f) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(SWIPE_ACTION_WIDTH)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = onAction,
                        modifier = Modifier.size(SWIPE_ACTION_WIDTH),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = actionContentDescription,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { down ->
                            // 左边缘留给系统返回手势。
                            allowSwipe.value = down.x > backGestureGuardPx
                        },
                        onDragEnd = {
                            if (!allowSwipe.value) {
                                allowSwipe.value = false
                                scope.launch { offsetX.animateTo(0f, tween(SWIPE_ANIMATION_MS)) }
                                return@detectHorizontalDragGestures
                            }
                            val target = if (offsetX.value <= -openThresholdPx) -revealPx else 0f
                            scope.launch { offsetX.animateTo(target, tween(SWIPE_ANIMATION_MS)) }
                            allowSwipe.value = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!allowSwipe.value) return@detectHorizontalDragGestures
                            // 关着时只允许左滑；打开后允许右滑收回。
                            if (dragAmount > 0f && offsetX.value >= 0f) {
                                return@detectHorizontalDragGestures
                            }
                            change.consume()
                            scope.launch {
                                offsetX.snapTo(
                                    (offsetX.value + dragAmount).coerceIn(-revealPx, 0f)
                                )
                            }
                        },
                    )
                },
        ) {
            content(
                offsetX.value < -1f,
                {
                    scope.launch { offsetX.animateTo(0f, tween(SWIPE_ANIMATION_MS)) }
                },
            )
        }
    }
}

private val SWIPE_ACTION_WIDTH = 72.dp
private const val SWIPE_ANIMATION_MS = 200
