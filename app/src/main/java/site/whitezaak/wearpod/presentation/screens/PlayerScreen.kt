package site.whitezaak.wearpod.presentation.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode

@Composable
@Suppress("DEPRECATION")
fun PlayerScreen(
    episode: Episode?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPositionFlow: StateFlow<Long>,
    currentDuration: Long,
    onTitleClick: () -> Unit,
    onPodcastTitleClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlaylistClick: () -> Unit,
    onVolumeClick: () -> Unit,
    onSleepTimerClick: () -> Unit
) {
    val primaryImageUrl = remember(episode?.imageUrl) {
        val raw = episode?.imageUrl.orEmpty()
        when {
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            else -> raw
        }
    }
    val fallbackImageUrl = remember(episode?.podcastImageUrl) {
        val raw = episode?.podcastImageUrl.orEmpty()
        when {
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            else -> raw
        }
    }
    var activeImageUrl by remember(primaryImageUrl, fallbackImageUrl) {
        mutableStateOf(primaryImageUrl.ifBlank { fallbackImageUrl })
    }
    val context = LocalContext.current
    val title = episode?.title ?: stringResource(R.string.unknown_title)
    val artist = episode?.podcastTitle ?: stringResource(R.string.unknown_podcast)
    val imageRequest = remember(activeImageUrl) {
        ImageRequest.Builder(context)
            .data(activeImageUrl)
            .crossfade(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .precision(Precision.INEXACT)
            .size(192)
            .build()
    }

            val topSecondaryButtonContainer = Color(0xFFD2D3D6)
            val centerButtonContainer = Color(0xFFE2E3E6)
            val topButtonIconColor = Color(0xFF40434A)
            val centerButtonIconColor = Color(0xFF3A3D44)
            val bottomButtonContainer = Color(0xFF686B70).copy(alpha = 0.58f)
            val bottomButtonIconColor = Color(0xFFF3F3F3)
    
    // State to hold temporary scrub value while user is dragging
    val scrubbingPositionState = remember { mutableStateOf<Long?>(null) }
    val scrubStartXRatio = 0.55f
    val scrubDragThresholdPx = 16f
    val latestCurrentDuration by rememberUpdatedState(currentDuration)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    
    // Formatting function for mm:ss
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(episode?.audioUrl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val durationMs = latestCurrentDuration
                    if (durationMs <= 0L || size.height <= 0f) {
                        return@awaitEachGesture
                    }
                    // Keep swipe-to-dismiss area on the left side untouched.
                    if (down.position.x <= size.width * scrubStartXRatio) {
                        return@awaitEachGesture
                    }

                    fun mapYToPosition(y: Float): Long {
                        val percentage = (y / size.height).coerceIn(0f, 1f)
                        return (percentage * durationMs).toLong()
                    }

                    var activePointerId = down.id
                    var isScrubbing = false
                    var lastLiveSeekAt = 0L
                    var lastLiveSeekPosition = -1L

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == activePointerId }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) {
                            break
                        }

                        activePointerId = change.id
                        if (!isScrubbing) {
                            val dragDistance = kotlin.math.abs(change.position.y - down.position.y)
                            if (dragDistance < scrubDragThresholdPx) {
                                continue
                            }
                            isScrubbing = true
                            scrubbingPositionState.value = mapYToPosition(change.position.y)
                            lastLiveSeekPosition = scrubbingPositionState.value ?: -1L
                            lastLiveSeekAt = SystemClock.elapsedRealtime()
                            change.consume()
                            continue
                        }

                        val targetPosition = mapYToPosition(change.position.y)
                        scrubbingPositionState.value = targetPosition

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLiveSeekAt >= 80L && kotlin.math.abs(targetPosition - lastLiveSeekPosition) >= 500L) {
                            latestOnSeekTo(targetPosition)
                            lastLiveSeekAt = now
                            lastLiveSeekPosition = targetPosition
                        }
                        change.consume()
                    }

                    if (isScrubbing) {
                        scrubbingPositionState.value?.let(latestOnSeekTo)
                    }
                    scrubbingPositionState.value = null
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Keep a lightweight backdrop image to avoid expensive full-screen blur on watch.
        if (activeImageUrl.isNotEmpty()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                onError = {
                    if (fallbackImageUrl.isNotBlank() && activeImageUrl != fallbackImageUrl) {
                        activeImageUrl = fallbackImageUrl
                    }
                },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = 10.dp)
            )
        }
        
        // Semi-transparent overlay to ensure text contrast
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .basicMarquee()
                    .clickable { onTitleClick() }
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .basicMarquee()
                    .clickable { onPodcastTitleClick() },
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalIconButton(
                    onClick = {
                        scrubbingPositionState.value = null
                        onSkipBackward()
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = topSecondaryButtonContainer,
                        contentColor = topButtonIconColor,
                    ),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RotateLeft,
                        contentDescription = stringResource(R.string.cd_rewind_15_seconds),
                        modifier = Modifier.size(28.dp),
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(76.dp)
                ) {
                    val observedPosition by currentPositionFlow.collectAsState()
                    CircularProgressIndicator(
                        progress = {
                            val current = scrubbingPositionState.value ?: observedPosition
                            if (currentDuration > 0) {
                                (current.toFloat() / currentDuration.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 4.dp,
                        colors = ProgressIndicatorDefaults.colors(
                            indicatorColor = Color(0xFFC3C7CF),
                            trackColor = Color(0xFF43474E)
                        )
                    )

                    FilledIconButton(
                        onClick = {
                            scrubbingPositionState.value = null
                            onPlayPause()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = centerButtonContainer,
                            contentColor = centerButtonIconColor,
                        ),
                        modifier = Modifier.size(62.dp)
                    ) {
                        if (scrubbingPositionState.value != null) {
                            Text(
                                text = formatTime(scrubbingPositionState.value!!),
                                style = MaterialTheme.typography.bodySmall,
                                color = centerButtonIconColor,
                                textAlign = TextAlign.Center
                            )
                        } else if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                colors = ProgressIndicatorDefaults.colors(
                                    indicatorColor = centerButtonIconColor
                                )
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                                contentDescription = if (isPlaying) {
                                    stringResource(R.string.action_pause)
                                } else {
                                    stringResource(R.string.action_play)
                                },
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }

                FilledTonalIconButton(
                    onClick = {
                        scrubbingPositionState.value = null
                        onSkipForward()
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = topSecondaryButtonContainer,
                        contentColor = topButtonIconColor,
                    ),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RotateRight,
                        contentDescription = stringResource(R.string.cd_forward_15_seconds),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 38.dp)
                        .offset(y = (-12).dp)
                        .clip(CircleShape)
                        .background(bottomButtonContainer)
                        .clickable { scrubbingPositionState.value = null; onPlaylistClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.QueueMusic,
                        contentDescription = stringResource(R.string.playlist_title),
                        modifier = Modifier.size(24.dp),
                        tint = bottomButtonIconColor
                    )
                }

                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 38.dp)
                        .offset(y = 6.dp)
                        .clip(CircleShape)
                        .background(bottomButtonContainer)
                        .clickable { scrubbingPositionState.value = null; onVolumeClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.cd_volume),
                        modifier = Modifier.size(24.dp),
                        tint = bottomButtonIconColor
                    )
                }

                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 38.dp)
                        .offset(y = (-12).dp)
                        .clip(CircleShape)
                        .background(bottomButtonContainer)
                        .clickable { scrubbingPositionState.value = null; onSleepTimerClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = stringResource(R.string.sleep_timer_title),
                        modifier = Modifier.size(24.dp),
                        tint = bottomButtonIconColor
                    )
                }
            }
        }
    }
}
