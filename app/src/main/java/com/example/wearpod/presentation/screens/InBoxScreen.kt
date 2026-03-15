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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.example.wearpod.domain.Episode

@Composable
fun InBoxScreen(
    episodes: List<Episode>,
    onEpisodeClick: (String) -> Unit,
    onRefreshClick: () -> Unit
) {
    // Basic implementation: sorting done by ViewModel, just display
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            ListHeader {
                Text(text = "Home", textAlign = TextAlign.Center)
            }
        }
        item {
            Button(
                onClick = onRefreshClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Refresh, "Refresh", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pull to Refresh", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val groupedEpisodes = episodes.groupBy { it.pubDate }
        
        groupedEpisodes.forEach { (date, dailyEpisodes) ->
            item {
                ListHeader {
                    Text(text = date, textAlign = TextAlign.Center)
                }
            }
            items(dailyEpisodes) { episode ->
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
                            text = episode.duration, 
                            maxLines = 1
                        ) 
                    }
                )
            }
        }
        if (episodes.isEmpty()) {
             item {
                 Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                     CircularProgressIndicator()
                 }
             }
        }
    }
}
