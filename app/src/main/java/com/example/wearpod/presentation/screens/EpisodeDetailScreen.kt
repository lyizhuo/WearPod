package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import coil.compose.AsyncImage
import com.example.wearpod.domain.Episode
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.foundation.basicMarquee

@Composable
fun EpisodeDetailScreen(
    episode: Episode,
    onPlayClick: () -> Unit,
    onQueueClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top: Artwork and Title
        val displayImageUrl = episode.imageUrl.ifEmpty { episode.podcastImageUrl }
        if (displayImageUrl.isNotEmpty()) {
            AsyncImage(
                model = displayImageUrl,
                contentDescription = "Podcast Cover",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            text = episode.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = episode.podcastTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.basicMarquee()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Middle: Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onDownloadClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(imageVector = Icons.Default.Download, contentDescription = "Download", tint = Color.White)
            }

            Button(
                onClick = onPlayClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(32.dp))
            }

            Button(
                onClick = onQueueClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = "Add to Queue", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Metadata
        Text(
            text = "${episode.pubDate} • ${episode.duration}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Show Notes
        // Simple HTML stripping
        val cleanDescription = episode.description
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<li>(.*?)</li>", RegexOption.IGNORE_CASE), "• $1\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

        if (cleanDescription.isNotEmpty()) {
            Text(
                text = cleanDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                textAlign = TextAlign.Start
            )
        }
    }
}
