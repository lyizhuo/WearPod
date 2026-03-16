package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
// 仅增加这两个必要的导入用于修复闪退
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
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
    // 定义状态
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        // 绑定状态并禁用导致闪退的 hapticFeedback
        state = listState,
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
            scrollableState = listState,
            hapticFeedbackEnabled = false
        )
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

        if (podcasts.isEmpty()) {
            item {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).size(32.dp)
                )
            }
        } else {
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
}