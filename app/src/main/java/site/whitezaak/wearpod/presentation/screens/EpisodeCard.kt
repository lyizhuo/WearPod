package site.whitezaak.wearpod.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.presentation.EpisodeTextFormatter

enum class EpisodePlaybackState {
    DEFAULT, PLAYED, CURRENTLY_PLAYING
}

@Composable
fun EpisodeCard(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playbackState: EpisodePlaybackState = EpisodePlaybackState.DEFAULT
) {
    val context = LocalContext.current
    val metaText = remember(episode.duration) {
        EpisodeTextFormatter.formatEpisodeMeta(context, "", episode.duration)
    }

    val textColor = when (playbackState) {
        EpisodePlaybackState.PLAYED -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        EpisodePlaybackState.CURRENTLY_PLAYING -> Color(0xFF1B1B1B)
        EpisodePlaybackState.DEFAULT -> MaterialTheme.colorScheme.onBackground
    }

    val buttonColors = if (playbackState == EpisodePlaybackState.CURRENTLY_PLAYING) {
        ButtonDefaults.filledTonalButtonColors().copy(
            containerColor = Color(0xFFE0E0E0),
            contentColor = Color(0xFF1B1B1B)
        )
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }

    Button(
        modifier = modifier,
        onClick = onClick,
        colors = buttonColors,
        label = {
            Text(
                text = episode.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = textColor
            )
        },
        secondaryLabel = {
            Text(
                text = if (metaText.isNotEmpty()) {
                    context.getString(R.string.inbox_episode_meta, episode.podcastTitle, metaText)
                } else {
                    episode.podcastTitle
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = textColor
            )
        }
    )
}
