package com.example.wearpod.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.*
import coil.compose.AsyncImage
import com.example.wearpod.domain.Episode

@Composable
fun EpisodeDetailScreen(
    episode: Episode,
    onPlayClick: () -> Unit,
    onQueueClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 【核心修复 2】绑定状态
        state = listState,
        // 【核心修复 3】显式禁用导致闪退的震动反馈
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
            scrollableState = listState,
            hapticFeedbackEnabled = false
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)
    ) {
        // 1. 封面图
        item {
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
        }

        // 2. 标题与播客名
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 3. 操作按钮组
        item {
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
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 4. 元数据 (日期 & 时长)
        item {
            Text(
                text = "${episode.pubDate} • ${episode.duration}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 5. 简介内容 (Show Notes)
        item {
            val cleanDescription = remember(episode.description) {
                episode.description
                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
                    .replace(Regex("<li>(.*?)</li>", RegexOption.IGNORE_CASE), "• $1\n")
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .trim()
            }

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
}