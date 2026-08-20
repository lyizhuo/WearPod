package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.presentation.MainViewModel
import site.whitezaak.wearpod.domain.Podcast

@Composable
fun LibraryScreen(
    sortedPodcasts: List<Pair<Int, Podcast>>,
    onPodcastClick: (String) -> Unit
) {
    val viewModel: MainViewModel = viewModel()
    val isSubscriptionsLoading by viewModel.isSubscriptionsLoading.collectAsState()
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    ScreenListScaffold(
        title = stringResource(R.string.nav_library),
        modifier = Modifier.fillMaxWidth(),
        listState = listState,
    ) {
        if (sortedPodcasts.isEmpty()) {
            item {
                if (isSubscriptionsLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp).size(32.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.library_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                            .padding(bottom = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            items(
                items = sortedPodcasts,
                key = { it.first }
            ) { (originalIndex, podcast) ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPodcastClick(podcast.feedUrl) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(text = podcast.title, maxLines = 1) }
                )
            }
        }
    }
}
