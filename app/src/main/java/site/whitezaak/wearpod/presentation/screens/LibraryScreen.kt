package site.whitezaak.wearpod.presentation.screens

import android.icu.text.Transliterator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
// 仅增加这两个必要的导入用于修复闪退
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Text
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Podcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class SortedPodcastItem(
    val originalIndex: Int,
    val podcast: Podcast,
    val alphaBucket: Int,
    val normalizedTitle: String,
)

private val hanToLatinTransliterator: Transliterator by lazy {
    // Chinese title -> Latin pinyin-ish form, then ASCII fold for stable A-Z sorting.
    Transliterator.getInstance("Han-Latin; Latin-Ascii")
}

@Composable
fun LibraryScreen(
    podcasts: List<Podcast>,
    onPodcastClick: (Int) -> Unit
) {
    // 定义状态
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val sortedPodcasts by produceState(initialValue = emptyList<SortedPodcastItem>(), key1 = podcasts) {
        value = withContext(Dispatchers.Default) {
            buildSortedPodcasts(podcasts)
        }
    }
    val isSorting = podcasts.isNotEmpty() && sortedPodcasts.isEmpty()

    ScreenListScaffold(
        title = stringResource(R.string.nav_library),
        modifier = Modifier.fillMaxWidth(),
        listState = listState,
    ) {

        if (podcasts.isEmpty() || isSorting) {
            item {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).size(32.dp)
                )
            }
        } else {
            items(
                items = sortedPodcasts,
                key = { it.originalIndex }
            ) { item ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPodcastClick(item.originalIndex) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(text = item.podcast.title, maxLines = 1) }
                )
            }
        }
    }
}

private fun buildSortedPodcasts(podcasts: List<Podcast>): List<SortedPodcastItem> {
    return podcasts.withIndex()
        .map { indexed ->
            val normalized = normalizeTitleForSort(indexed.value.title)
            SortedPodcastItem(
                originalIndex = indexed.index,
                podcast = indexed.value,
                alphaBucket = alphaBucket(normalized),
                normalizedTitle = normalized,
            )
        }
        .sortedWith(compareBy<SortedPodcastItem>({ it.alphaBucket }, { it.normalizedTitle }, { it.originalIndex }))
}

private fun normalizeTitleForSort(title: String): String {
    if (title.isBlank()) {
        return ""
    }
    val latin = hanToLatinTransliterator.transliterate(title)
    return latin
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9 ]"), "")
        .trim()
}

private fun alphaBucket(normalizedTitle: String): Int {
    val firstChar = normalizedTitle.firstOrNull { it.isLetterOrDigit() }
    return if (firstChar != null && firstChar in 'A'..'Z') {
        firstChar - 'A'
    } else {
        26
    }
}