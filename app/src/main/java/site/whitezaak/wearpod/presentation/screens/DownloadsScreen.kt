package site.whitezaak.wearpod.presentation.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import android.os.Build
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.ListHeader
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DownloadsScreen(
    downloads: List<Episode>,
    downloading: List<Episode>,
    progressMap: Map<String, Float>,
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
            item {
                ListHeader {
                    Text(
                        text = stringResource(R.string.downloads_downloading_section),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            items(downloading, key = { it.audioUrl }) { episode ->
                val progress = progressMap[episode.audioUrl] ?: 0f
                val metaText = EpisodeTextFormatter.formatEpisodeMeta(context, "", episode.duration)
                val offsetX = remember { Animatable(0f) }
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                val backGestureGuardPx = remember(density) {
                    with(density) {
                        if (Build.VERSION.SDK_INT >= 34) 42.dp.toPx() else 28.dp.toPx()
                    }
                }
                val allowSwipeCancel = remember { androidx.compose.runtime.mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { down ->
                                    allowSwipeCancel.value = down.x > backGestureGuardPx
                                },
                                onDragEnd = {
                                    if (!allowSwipeCancel.value) {
                                        allowSwipeCancel.value = false
                                        scope.launch { offsetX.animateTo(0f, tween(200)) }
                                        return@detectHorizontalDragGestures
                                    }
                                    if (offsetX.value < -140f) {
                                        scope.launch {
                                            offsetX.animateTo(
                                                targetValue = -1000f,
                                                animationSpec = tween(200)
                                            )
                                            onCancelDownload(episode)
                                        }
                                    } else {
                                        scope.launch { offsetX.animateTo(0f, tween(200)) }
                                    }
                                    allowSwipeCancel.value = false
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    if (!allowSwipeCancel.value) {
                                        return@detectHorizontalDragGestures
                                    }
                                    if (dragAmount >= 0f) {
                                        return@detectHorizontalDragGestures
                                    }
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-260f, 0f))
                                    }
                                }
                            )
                        }
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                ) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {},
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
                                        context.getString(R.string.inbox_episode_meta, episode.podcastTitle, metaText)
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

        if (downloads.isNotEmpty()) {
            item {
                ListHeader {
                    Text(
                        text = stringResource(R.string.downloads_saved_section),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        items(downloads, key = { it.audioUrl }) { episode ->
            val offsetX = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val backGestureGuardPx = remember(density) {
                with(density) {
                    if (Build.VERSION.SDK_INT >= 34) 42.dp.toPx() else 28.dp.toPx()
                }
            }
            val allowSwipeDelete = remember { androidx.compose.runtime.mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { down ->
                                // Keep left edge free for system/back swipe gestures.
                                allowSwipeDelete.value = down.x > backGestureGuardPx
                            },
                            onDragEnd = {
                                if (!allowSwipeDelete.value) {
                                    allowSwipeDelete.value = false
                                    scope.launch { offsetX.animateTo(0f, tween(200)) }
                                    return@detectHorizontalDragGestures
                                }
                                if (offsetX.value < -140f) {
                                    scope.launch {
                                        offsetX.animateTo(
                                            targetValue = -1000f,
                                            animationSpec = tween(200)
                                        )
                                        onRemoveDownload(episode)
                                    }
                                } else {
                                    scope.launch { offsetX.animateTo(0f, tween(200)) }
                                }
                                allowSwipeDelete.value = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (!allowSwipeDelete.value) {
                                    return@detectHorizontalDragGestures
                                }
                                // Keep right-swipe path available for back navigation.
                                if (dragAmount >= 0f) {
                                    return@detectHorizontalDragGestures
                                }
                                change.consume()
                                scope.launch {
                                    offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-260f, 0f))
                                }
                            }
                        )
                    }
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            ) {
                val metaText = EpisodeTextFormatter.formatEpisodeMeta(context, "", episode.duration)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEpisodeClick(episode) },
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
}
}
