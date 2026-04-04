package site.whitezaak.wearpod.presentation.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.domain.Podcast
import site.whitezaak.wearpod.util.BlurTransformation
import android.os.Build

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
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val context = LocalContext.current

    ScreenListScaffold(
        title = stringResource(R.string.app_name),
        modifier = Modifier.fillMaxWidth(),
        listState = listState,
    ) {
        item {
            val primaryImageUrl = remember(currentPlayingEpisode?.imageUrl) {
                val raw = currentPlayingEpisode?.imageUrl.orEmpty()
                if (raw.startsWith("http://")) {
                    raw.replaceFirst("http://", "https://")
                } else {
                    raw
                }
            }
            val fallbackImageUrl = remember(currentPlayingEpisode?.podcastImageUrl) {
                val raw = currentPlayingEpisode?.podcastImageUrl.orEmpty()
                if (raw.startsWith("http://")) {
                    raw.replaceFirst("http://", "https://")
                } else {
                    raw
                }
            }
            var activeImageUrl by remember(primaryImageUrl, fallbackImageUrl) {
                mutableStateOf(primaryImageUrl.ifBlank { fallbackImageUrl })
            }
            val imageRequest = remember(activeImageUrl, context) {
                val builder = ImageRequest.Builder(context)
                    .data(activeImageUrl)
                    .crossfade(false)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .precision(Precision.INEXACT)
                    .size(240)
                    
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    builder.transformations(BlurTransformation(context, radius = 15f))
                }
                
                builder.build()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onPlayerClick() }
            ) {
                if (activeImageUrl.isNotEmpty()) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        onError = {
                            if (fallbackImageUrl.isNotBlank() && activeImageUrl != fallbackImageUrl) {
                                activeImageUrl = fallbackImageUrl
                            }
                        },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(15.dp)
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) // Darken overlay
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                ) {
                    if (isPlaying) {
                        AnimatedBars(modifier = Modifier.size(24.dp).padding(bottom = 4.dp))
                    } else {
                        Icon(
                            if (currentPlayingEpisode != null) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.cd_playback_status),
                            modifier = Modifier.size(24.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.home_now_playing),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Text(
                            text = currentPlayingEpisode?.title?.ifEmpty { stringResource(R.string.home_not_playing) }
                                ?: stringResource(R.string.home_not_playing),
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
                    Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onHomeClick,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.nav_home), maxLines = 1) },
                icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPodcastClick(0) },
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.nav_library), maxLines = 1) },
                icon = { Icon(Icons.Default.Favorite, contentDescription = stringResource(R.string.nav_library)) }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDownloadsClick,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.nav_downloads), maxLines = 1) },
                icon = { Icon(Icons.Default.Download, contentDescription = stringResource(R.string.nav_downloads)) }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSettingsClick,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.nav_settings), maxLines = 1) },
                icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) }
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