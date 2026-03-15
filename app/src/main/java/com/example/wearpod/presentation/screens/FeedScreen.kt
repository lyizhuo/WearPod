package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.wearpod.domain.Episode
import com.example.wearpod.domain.Podcast

@Composable
fun FeedScreen(
    podcast: Podcast,
    episodes: List<Episode>,
    onEpisodeClick: (String) -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            ListHeader {
                Text(text = podcast.title, textAlign = TextAlign.Center)
            }
        }
        if (episodes.isEmpty()) {
             item {
                 Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                     CircularProgressIndicator()
                 }
             }
        } else {
            items(episodes) { episode ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEpisodeClick(episode.audioUrl) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { 
                        Text(
                            text = episode.title, 
                            maxLines = 2, 
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    secondaryLabel = { 
                        Text(
                            text = "${episode.pubDate} \u00B7 ${episode.duration}", 
                            maxLines = 1
                        ) 
                    }
                )
            }
        }
    }
}
