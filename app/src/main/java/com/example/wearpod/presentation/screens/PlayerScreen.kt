package com.example.wearpod.presentation.screens

import android.os.SystemClock

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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.example.wearpod.domain.Episode

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.style.TextAlign

@Composable
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
    val title = episode?.title ?: "Unknown"
    val artist = episode?.podcastTitle ?: "Unknown"
    val displayImageUrl = remember(episode?.imageUrl, episode?.podcastImageUrl) {
        episode?.imageUrl?.takeIf { it.isNotBlank() }
            ?: episode?.podcastImageUrl?.takeIf { it.isNotBlank() }
            ?: ""
    }
    val context = LocalContext.current
    val imageUrl = remember(displayImageUrl) {
        if (displayImageUrl.startsWith("http://")) {
            displayImageUrl.replaceFirst("http://", "https://")
        } else {
            displayImageUrl
        }
    }
    val imageRequest = remember(imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .size(360)
            .build()
    }
    
    // Explicitly observe the StateFlow within PlayerScreen to guarantee local recomposition
    val observedPosition by currentPositionFlow.collectAsState()
    
    // State to hold temporary scrub value while user is dragging
    var scrubbingPosition by remember { mutableStateOf<Long?>(null) }
    val scrubStartXRatio = 0.55f
    
    // Formatting function for mm:ss
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d", m, s)
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
                    scrubbingPosition = mapYToPosition(down.position.y)
                    down.consume()
                    var lastLiveSeekAt = 0L
                    var lastLiveSeekPosition = scrubbingPosition ?: -1L

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == activePointerId }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) {
                            break
                        }

                        activePointerId = change.id
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

                    scrubbingPosition?.let(onSeekTo)
                    scrubbingPosition = null
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Keep a lightweight backdrop image to avoid expensive full-screen blur on watch.
        if (imageUrl.isNotEmpty()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
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
                    onClick = onSkipBackward,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.RotateLeft, contentDescription = "Rewind 15s", modifier = Modifier.size(28.dp), tint = Color.White)
                }
                
                Button(
                    onClick = onPlayPause,
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
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Button(
                    onClick = onSkipForward,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.RotateRight, contentDescription = "Skip 15s", modifier = Modifier.size(28.dp), tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Button(
                    onClick = onPlaylistClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp).offset(y = (-8).dp)
                ) {
                    Icon(imageVector = Icons.Default.QueueMusic, contentDescription = "Playlist", modifier = Modifier.size(24.dp), tint = Color.White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onVolumeClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp).offset(y = 8.dp)
                ) {
                    Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "Volume", modifier = Modifier.size(24.dp), tint = Color.White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onSleepTimerClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp).offset(y = (-8).dp)
                ) {
                    Icon(imageVector = Icons.Default.Timer, contentDescription = "Sleep Timer", modifier = Modifier.size(24.dp), tint = Color.White)
                }
            }
        }
    }
}
