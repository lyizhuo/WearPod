package site.whitezaak.wearpod.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlaylistScreen(
    playlist: List<Episode>,
    onEpisodeClick: (Episode) -> Unit,
    onRemoveEpisode: (Episode) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0, initialCenterItemScrollOffset = 0)

    ScreenListScaffold(
        title = stringResource(R.string.playlist_up_next),
        modifier = Modifier.fillMaxSize(),
        listState = listState,
    ) {
        if (playlist.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.playlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            items(playlist, key = { it.audioUrl }) { episode ->
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
                        .padding(horizontal = 16.dp, vertical = 4.dp)
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
                                            onRemoveEpisode(episode)
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
                            val metaText = EpisodeTextFormatter.formatEpisodeMeta(context, episode.pubDate, episode.duration)
                            if (metaText.isNotEmpty()) {
                                Text(
                                    text = metaText,
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
}