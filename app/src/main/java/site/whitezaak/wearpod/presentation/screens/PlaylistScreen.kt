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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlaylistScreen(
    playlist: List<Episode>,
    currentPlayingEpisode: Episode?,
    recentlyPlayedEpisodes: List<Episode>,
    onEpisodeClick: (Episode) -> Unit,
    onRemoveEpisode: (Episode) -> Unit
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0, initialCenterItemScrollOffset = 0)

    val currentUrl = currentPlayingEpisode?.audioUrl
    val playlistUrls = playlist.map { it.audioUrl }.toSet()
    val recentUrls = recentlyPlayedEpisodes.map { it.audioUrl }.toSet()
    val seen = mutableSetOf<String>()
    val displayItems = buildList {
        // Current episode outside the queue (e.g. playing from detail screen)
        currentPlayingEpisode?.takeIf { it.audioUrl !in playlistUrls && seen.add(it.audioUrl) }
            ?.let { add(it to EpisodePlaybackState.CURRENTLY_PLAYING) }
        // Queue in original order, with playback state overlaid
        for (ep in playlist) {
            if (seen.add(ep.audioUrl)) {
                val state = when (ep.audioUrl) {
                    currentUrl -> EpisodePlaybackState.CURRENTLY_PLAYING
                    in recentUrls -> EpisodePlaybackState.PLAYED
                    else -> EpisodePlaybackState.DEFAULT
                }
                add(ep to state)
            }
        }
        // Recently played (100% completed): only those no longer in the queue and not currently playing
        for (ep in recentlyPlayedEpisodes) {
            if (ep.audioUrl != currentUrl && ep.audioUrl !in playlistUrls && seen.add(ep.audioUrl)) {
                add(ep to EpisodePlaybackState.PLAYED)
            }
        }
    }

    ScreenListScaffold(
        title = stringResource(R.string.playlist_up_next),
        modifier = Modifier.fillMaxSize(),
        listState = listState,
    ) {
        if (displayItems.isEmpty()) {
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
            items(displayItems, key = { it.first.audioUrl }) { (episode, playbackState) ->
                val offsetX = remember { Animatable(0f) }
                val scope = rememberCoroutineScope()
                val isCurrentlyPlaying = playbackState == EpisodePlaybackState.CURRENTLY_PLAYING

                if (isCurrentlyPlaying) {
                    EpisodeCard(
                        episode = episode,
                        onClick = { onEpisodeClick(episode) },
                        modifier = Modifier.fillMaxWidth(),
                        playbackState = playbackState
                    )
                } else {
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
                        EpisodeCard(
                            episode = episode,
                            onClick = { onEpisodeClick(episode) },
                            modifier = Modifier.fillMaxWidth(),
                            playbackState = playbackState
                        )
                    }
                }
            }
        }
    }
}
