package site.whitezaak.wearpod.presentation.screens
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
import site.whitezaak.wearpod.util.BlurTransformation
import android.os.Build

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
    val imageRequest = remember(activeImageUrl, context) {
        val builder = ImageRequest.Builder(context)
            .data(activeImageUrl)
            .crossfade(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .precision(Precision.INEXACT)
            .size(192)
            
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            builder.transformations(BlurTransformation(context, radius = 10f))
        }
        
        builder.build()
    }

            val topSecondaryButtonContainer = Color(0xFFD2D3D6)
            val centerButtonContainer = Color(0xFFE2E3E6)
            val topButtonIconColor = Color(0xFF40434A)
            val centerButtonIconColor = Color(0xFF3A3D44)
            val bottomButtonContainer = Color(0xFF686B70).copy(alpha = 0.58f)
            val bottomButtonIconColor = Color(0xFFF3F3F3)
    
    // State to hold temporary scrub value while user is dragging
    val scrubbingPositionState = remember { mutableStateOf<Long?>(null) }
    val optimisticSeekPositionState = remember { mutableStateOf<Long?>(null) }
    
    androidx.compose.runtime.LaunchedEffect(optimisticSeekPositionState.value) {
        if (optimisticSeekPositionState.value != null) {
            // Keep the optimistic UI for enough time so that the backing player flow can catch up.
            kotlinx.coroutines.delay(2000L)
            optimisticSeekPositionState.value = null
        }
    }
    
    val scrubStartXRatio = 0.55f
    val scrubDragThresholdPx = 16f
    val scrubEdgeReachPx = 26f
    val observedPosition by currentPositionFlow.collectAsState()
    val episodeDurationMs = remember(episode?.duration) {
        parseDurationToMs(episode?.duration)
    }
    fun displayPositionMs(): Long {
        return scrubbingPositionState.value ?: optimisticSeekPositionState.value ?: observedPosition
    }
    
    androidx.compose.runtime.LaunchedEffect(observedPosition) {
        val target = optimisticSeekPositionState.value
        if (target != null && kotlin.math.abs(observedPosition - target) < 1500L) {
            optimisticSeekPositionState.value = null
        }
    }
    val effectiveDurationMs = maxOf(currentDuration, episodeDurationMs)
    val latestEffectiveDurationMs by rememberUpdatedState(effectiveDurationMs)
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
                    if (size.height <= 0f) {
                        return@awaitEachGesture
                    }
                    // Keep swipe-to-dismiss area on the left side untouched.
                    if (down.position.x <= size.width * scrubStartXRatio) {
                        return@awaitEachGesture
                    }

                    val centerX = size.width / 2f
                    val centerY = size.height / 2f

                    fun getAngle(x: Float, y: Float): Double {
                        val dx = x - centerX
                        val dy = y - centerY
                        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
                    }

                    var activePointerId = down.id
                    var isScrubbing = false
                    val initialPositionMs = observedPosition
                    var accumulatedAngleDelta = 0.0
                    var previousAngle = getAngle(down.position.x, down.position.y)

                    fun mapAngleToPosition(angleDelta: Double): Long {
                        val durationMs = latestEffectiveDurationMs
                        if (durationMs <= 0L) return 0L
                        // 360 degrees = 100% of the track. Right half swipe (180 degrees) = 50%.
                        val progressDelta = angleDelta / 360.0
                        val newPosition = initialPositionMs + (progressDelta * durationMs).toLong()
                        return newPosition.coerceIn(0L, durationMs)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == activePointerId }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) {
                            break
                        }

                        activePointerId = change.id
                        val currentAngle = getAngle(change.position.x, change.position.y)
                        
                        var delta = currentAngle - previousAngle
                        if (delta > 180.0) delta -= 360.0
                        if (delta < -180.0) delta += 360.0
                        
                        if (!isScrubbing) {
                            val dragDistance = kotlin.math.abs(change.position.y - down.position.y)
                            if (dragDistance < scrubDragThresholdPx) {
                                continue
                            }
                            isScrubbing = true
                            accumulatedAngleDelta += delta
                            previousAngle = currentAngle
                            scrubbingPositionState.value = mapAngleToPosition(accumulatedAngleDelta)
                            change.consume()
                            continue
                        }

                        accumulatedAngleDelta += delta
                        previousAngle = currentAngle
                        scrubbingPositionState.value = mapAngleToPosition(accumulatedAngleDelta)
                        change.consume()
                    }

                    if (isScrubbing) {
                        val durationMs = latestEffectiveDurationMs
                        if (durationMs > 0L) {
                            scrubbingPositionState.value?.let {
                                optimisticSeekPositionState.value = it
                                latestOnSeekTo(it)
                            }
                        }
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
                    val currentDisplay = displayPositionMs()
                    val progressValue = if (effectiveDurationMs > 0L) {
                        (currentDisplay.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 4.dp.toPx()
                        val diameter = size.minDimension - stroke
                        val topLeft = androidx.compose.ui.geometry.Offset(
                            x = (size.width - diameter) / 2f,
                            y = (size.height - diameter) / 2f
                        )
                        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

                        // Draw track
                        drawArc(
                            color = Color(0xFF43474E),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                        )

                        // Draw indicator
                        if (progressValue > 0f) {
                            drawArc(
                                color = Color(0xFFC3C7CF),
                                startAngle = -90f,
                                sweepAngle = progressValue * 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = stroke,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            )
                        }
                    }

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
                        .clickable {
                            scrubbingPositionState.value = null
                            onPlaylistClick()
                        },
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
                        .clickable {
                            scrubbingPositionState.value = null
                            onVolumeClick()
                        },
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
                        .clickable {
                            scrubbingPositionState.value = null
                            onSleepTimerClick()
                        },
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

private fun parseDurationToMs(rawDuration: String?): Long {
    val parts = rawDuration.orEmpty().trim().split(":").mapNotNull { part: String -> part.toLongOrNull() }
    if (parts.isEmpty()) {
        return 0L
    }
    val totalSeconds = when (parts.size) {
        3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
        2 -> parts[0] * 60L + parts[1]
        1 -> parts[0]
        else -> return 0L
    }
    return (totalSeconds * 1000L).coerceAtLeast(0L)
}
