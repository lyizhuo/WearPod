package com.example.wearpod.presentation.screens

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
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage
import com.example.wearpod.domain.Episode

@Composable
fun PlayerScreen(
    episode: Episode?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onTitleClick: () -> Unit,
    onPodcastTitleClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onVolumeClick: () -> Unit,
    onPlaylistClick: () -> Unit
) {
    val title = episode?.title ?: "Unknown"
    val artist = episode?.podcastTitle ?: "Unknown"
    val displayImageUrl = episode?.imageUrl?.ifEmpty { episode.podcastImageUrl } ?: ""

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Dynamic Blurred Background
        if (displayImageUrl.isNotEmpty()) {
            AsyncImage(
                model = displayImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = 30.dp)
            )
        }
        
        // Semi-transparent overlay to ensure text contrast
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        
        // Circular ring hugging the screen bounds (visually aesthetic)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
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
                    .basicMarquee()
                    .clickable { onTitleClick() }
                    .padding(vertical = 4.dp)
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                modifier = Modifier.basicMarquee().clickable { onPodcastTitleClick() }
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
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp)
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onPlaylistClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(imageVector = Icons.Default.QueueMusic, contentDescription = "Playlist", modifier = Modifier.size(24.dp), tint = Color.White)
                }
                Spacer(modifier = Modifier.width(20.dp))
                Button(
                    onClick = onVolumeClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "Volume", modifier = Modifier.size(24.dp), tint = Color.White)
                }
            }
        }
    }
}
