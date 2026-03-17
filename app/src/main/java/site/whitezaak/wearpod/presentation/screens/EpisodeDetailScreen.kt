package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.*
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@Suppress("DEPRECATION")
fun EpisodeDetailScreen(
    episode: Episode,
    onPlayClick: () -> Unit,
    onPodcastTitleClick: () -> Unit,
    onQueueClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState()
    val context = LocalContext.current

    val primaryArtworkUrl = remember(episode.imageUrl) {
        val raw = episode.imageUrl
        if (raw.startsWith("http://")) {
            raw.replaceFirst("http://", "https://")
        } else {
            raw
        }
    }

    val fallbackArtworkUrl = remember(episode.podcastImageUrl) {
        val raw = episode.podcastImageUrl
        if (raw.startsWith("http://")) {
            raw.replaceFirst("http://", "https://")
        } else {
            raw
        }
    }

    var activeArtworkUrl by remember(primaryArtworkUrl, fallbackArtworkUrl) {
        mutableStateOf(primaryArtworkUrl.ifBlank { fallbackArtworkUrl })
    }

    val artworkRequest = remember(activeArtworkUrl) {
        ImageRequest.Builder(context)
            .data(activeArtworkUrl)
            .crossfade(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .precision(Precision.INEXACT)
            .size(160)
            .build()
    }

    val cleanDescription by produceState(initialValue = "", key1 = episode.description) {
        value = withContext(Dispatchers.Default) {
            sanitizeShowNotes(episode.description)
        }
    }

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
            if (activeArtworkUrl.isNotEmpty()) {
                SubcomposeAsyncImage(
                    model = artworkRequest,
                    contentDescription = stringResource(R.string.cd_podcast_cover),
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (fallbackArtworkUrl.isNotBlank() && activeArtworkUrl != fallbackArtworkUrl) {
                            activeArtworkUrl = fallbackArtworkUrl
                        }
                    },
                    loading = {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    },
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
                    modifier = Modifier
                        .basicMarquee()
                        .clickable { onPodcastTitleClick() }
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
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.nav_downloads),
                        tint = Color.White
                    )
                }

                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.action_play),
                        modifier = Modifier.size(32.dp)
                    )
                }

                Button(
                    onClick = onQueueClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=0.8f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = stringResource(R.string.action_add_to_queue),
                        tint = Color.White
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 4. 元数据 (日期 & 时长)
        item {
            val metaText = EpisodeTextFormatter.formatEpisodeMeta(context, episode.pubDate, episode.duration)
            if (metaText.isNotEmpty()) {
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 5. 简介内容 (Show Notes)
        item {
            if (cleanDescription.isNotEmpty()) {
                Text(
                    text = cleanDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    textAlign = TextAlign.Start
                )
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

private fun sanitizeShowNotes(raw: String): String {
    return raw
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