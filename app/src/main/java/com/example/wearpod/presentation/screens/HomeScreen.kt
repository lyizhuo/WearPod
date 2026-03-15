package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import coil.compose.AsyncImage
import com.example.wearpod.domain.Episode
import com.example.wearpod.domain.Podcast
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    podcasts: List<Podcast>,
    currentPlayingEpisode: Episode?,
    isPlaying: Boolean,
    onPodcastClick: (Int) -> Unit,
    onPlayerClick: () -> Unit,
    onHomeClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            ListHeader {
                Text(text = "WearPod", textAlign = TextAlign.Center)
            }
        }
        item {
            val displayImageUrl = currentPlayingEpisode?.imageUrl?.ifEmpty { currentPlayingEpisode.podcastImageUrl } ?: ""
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onPlayerClick() }
            ) {
                if (displayImageUrl.isNotEmpty()) {
                    AsyncImage(
                        model = displayImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(15.dp)
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) // Darken overlay
                }
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                ) {
                    if (isPlaying) {
                        // Animated bars
                        val infiniteTransition = rememberInfiniteTransition()
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center, modifier = Modifier.size(24.dp).padding(bottom = 4.dp)) {
                            listOf(0, 300, 600).forEach { delay ->
                                val height by infiniteTransition.animateFloat(
                                    initialValue = 0.3f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(500, delayMillis = delay, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    )
                                )
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .fillMaxHeight(height)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(2.dp))
                            }
                        }
                    } else {
                        Icon(if (currentPlayingEpisode != null) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Status", modifier = Modifier.size(24.dp), tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Now Playing", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text(
                            text = currentPlayingEpisode?.title?.ifEmpty { "Not Playing" } ?: "Not Playing",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onHomeClick,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Home", maxLines = 1) },
                icon = { Icon(Icons.Default.Home, contentDescription = "Home") }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPodcastClick(0) /* The index here triggers navigation to Library in WearPodApp */ },
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Library", maxLines = 1) },
                icon = { Icon(Icons.Default.Favorite, contentDescription = "Library") }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDownloadsClick,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Downloads", maxLines = 1) },
                icon = { Icon(Icons.Default.Download, contentDescription = "Downloads") }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSettingsClick,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Settings", maxLines = 1) },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            )
        }
    }
}
