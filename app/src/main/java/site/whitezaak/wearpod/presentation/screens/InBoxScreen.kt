package site.whitezaak.wearpod.presentation.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter
import site.whitezaak.wearpod.presentation.InboxEpisodeGroup

@Composable
fun InBoxScreen(
    episodeGroups: List<InboxEpisodeGroup>,
    hasEpisodes: Boolean,
    hasMoreEpisodes: Boolean,
    isRefreshing: Boolean,
    currentPlayingEpisode: Episode?,
    onEpisodeClick: (String) -> Unit,
    onLoadMoreClick: () -> Unit,
    onRefresh: () -> Unit = {},
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    val pullRefreshThresholdPx = 60f
    var pullOffset by remember { mutableFloatStateOf(0f) }

    // Green breathing light animation
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_alpha"
    )

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pullOffset > 0f && available.y < 0f) {
                    val consumed = available.y.coerceAtLeast(-pullOffset)
                    pullOffset += consumed
                    if (pullOffset <= 0f) pullOffset = 0f
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y > 0f) {
                    pullOffset = (pullOffset + available.y).coerceAtMost(pullRefreshThresholdPx * 1.5f)
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pullOffset >= pullRefreshThresholdPx && !isRefreshing) {
                    onRefresh()
                }
                pullOffset = 0f
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
    ) {
        ScreenListScaffold(
            title = stringResource(R.string.nav_home),
            modifier = Modifier.fillMaxWidth(),
            listState = listState,
            titleTrailing = {
                if (isRefreshing || pullOffset > 0f) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                Color.Green.copy(alpha = breathingAlpha),
                                CircleShape
                            )
                    )
                }
            },
        ) {
            episodeGroups.forEach { group ->
                item {
                    val date = remember(group.pubDate) { EpisodeTextFormatter.formatPubDate(group.pubDate) }
                    ListHeader {
                        Text(text = date, textAlign = TextAlign.Center)
                    }
                }
                items(
                    items = group.episodes,
                    key = { episode -> episode.audioUrl }
                ) { episode ->
                    EpisodeCard(
                        episode = episode,
                        onClick = { onEpisodeClick(episode.audioUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        playbackState = if (episode.audioUrl == currentPlayingEpisode?.audioUrl)
                            EpisodePlaybackState.CURRENTLY_PLAYING else EpisodePlaybackState.DEFAULT
                    )
                }
            }

            if (hasMoreEpisodes) {
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onLoadMoreClick,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = {
                            Text(
                                text = stringResource(R.string.load_more),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            if (!hasEpisodes && !isRefreshing) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_episodes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
