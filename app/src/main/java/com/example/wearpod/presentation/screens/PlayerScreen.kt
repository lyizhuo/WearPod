package com.example.wearpod.presentation.screens

import android.os.SystemClock
import java.util.Locale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.example.wearpod.R
import com.example.wearpod.domain.Episode

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.style.TextAlign

@Composable
@Suppress("DEPRECATION")
fun PlayerScreen(
    episode: Episode?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPositionFlow: kotlinx.coroutines.flow.StateFlow<Long>,
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
            .size(240)
            .build()
    }
    
    // Explicitly observe the StateFlow within PlayerScreen to guarantee local recomposition
    val observedPosition by currentPositionFlow.collectAsState()
    
    // State to hold temporary scrub value while user is dragging
    var scrubbingPosition by remember { mutableStateOf<Long?>(null) }
    val scrubStartXRatio = 0.55f
    val scrubDragThresholdPx = 16f
    
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
            .pointerInput(currentDuration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (currentDuration <= 0L || size.height <= 0f) {
                        return@awaitEachGesture
                    }
                    // Keep swipe-to-dismiss area on the left side untouched.
                    if (down.position.x <= size.width * scrubStartXRatio) {
                        return@awaitEachGesture
                    }

                    fun mapYToPosition(y: Float): Long {
                        val percentage = (y / size.height).coerceIn(0f, 1f)
                        return (percentage * currentDuration).toLong()
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
                            scrubbingPosition = mapYToPosition(change.position.y)
                            lastLiveSeekPosition = scrubbingPosition ?: -1L
                            lastLiveSeekAt = SystemClock.elapsedRealtime()
                            change.consume()
                            continue
                        }

                        val targetPosition = mapYToPosition(change.position.y)
                        scrubbingPosition = targetPosition

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLiveSeekAt >= 80L && kotlin.math.abs(targetPosition - lastLiveSeekPosition) >= 500L) {
                            onSeekTo(targetPosition)
                            lastLiveSeekAt = now
                            lastLiveSeekPosition = targetPosition
                        }
                        change.consume()
                    }

                    if (isScrubbing) {
                        scrubbingPosition?.let(onSeekTo)
                    }
                    scrubbingPosition = null
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
                    .blur(radius = 30.dp)
            )
        }
        
        // Semi-transparent overlay to ensure text contrast
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        
        // Progress Indicator 
        CircularProgressIndicator(
            progress = {
                val current = scrubbingPosition ?: observedPosition
                if (currentDuration > 0) {
                    (current.toFloat() / currentDuration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier.fillMaxSize().padding(2.dp),
            strokeWidth = 3.dp
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee()
                    .clickable { onTitleClick() }
                    .padding(vertical = 4.dp)
                ,
                textAlign = TextAlign.Center
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee()
                    .clickable { onPodcastTitleClick() },
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        scrubbingPosition = null
                        onSkipBackward()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RotateLeft,
                        contentDescription = stringResource(R.string.cd_rewind_15_seconds),
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
                
                Button(
                    onClick = {
                        scrubbingPosition = null
                        onPlayPause()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(64.dp)
                ) {
                    if (scrubbingPosition != null) {
                        Text(
                            text = formatTime(scrubbingPosition!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            textAlign = TextAlign.Center
                        )
                    } else if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
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
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Button(
                    onClick = {
                        scrubbingPosition = null
                        onSkipForward()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RotateRight,
                        contentDescription = stringResource(R.string.cd_forward_15_seconds),
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Button(
                    onClick = {
                        scrubbingPosition = null
                        onPlaylistClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp).offset(y = (-8).dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = stringResource(R.string.playlist_title),
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        scrubbingPosition = null
                        onVolumeClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp).offset(y = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = stringResource(R.string.cd_volume),
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        scrubbingPosition = null
                        onSleepTimerClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp).offset(y = (-8).dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = stringResource(R.string.sleep_timer_title),
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}
