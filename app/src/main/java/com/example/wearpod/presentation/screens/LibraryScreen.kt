package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.example.wearpod.domain.Podcast
import java.text.Collator
import java.util.Locale

@Composable
fun LibraryScreen(
    podcasts: List<Podcast>,
    onPodcastClick: (Int) -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            ListHeader {
                Text("Library", textAlign = TextAlign.Center)
            }
        }
        
        val collator = Collator.getInstance(Locale.CHINA)
        val sortedPodcasts = podcasts.withIndex().sortedWith(Comparator { a, b ->
            collator.compare(a.value.title, b.value.title)
        })

        items(sortedPodcasts) { (originalIndex, podcast) ->
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPodcastClick(originalIndex) },
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(text = podcast.title, maxLines = 1) }
            )
        }
    }
}
