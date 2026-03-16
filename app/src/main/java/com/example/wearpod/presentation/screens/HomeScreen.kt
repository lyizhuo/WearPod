package com.example.wearpod.presentation.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.*
import coil.compose.AsyncImage
import com.example.wearpod.domain.Episode
import com.example.wearpod.domain.Podcast

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
    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        // 【核心修复 2】绑定状态
        state = listState,
        // 【核心修复 3】手动定义旋转行为，禁用导致闪退的震动反馈
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
            scrollableState = listState,
            hapticFeedbackEnabled = false
        )
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
                        AnimatedBars(modifier = Modifier.size(24.dp).padding(bottom = 4.dp))
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
        
        if (podcasts.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading...", style = MaterialTheme.typography.bodySmall)
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
                onClick = { onPodcastClick(0) },
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

@Composable
fun AnimatedBars(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center, modifier = modifier) {
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
}