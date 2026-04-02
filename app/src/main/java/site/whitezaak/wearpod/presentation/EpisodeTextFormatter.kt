package site.whitezaak.wearpod.presentation

import android.content.Context
import site.whitezaak.wearpod.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object EpisodeTextFormatter {
    private val parser = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    private val localizedPubDateCache = ConcurrentHashMap<String, String>()

    fun formatPubDate(pubDate: String, locale: Locale = Locale.getDefault()): String {
        if (pubDate.isBlank()) {
            return ""
        }

        val cacheKey = "$pubDate|${locale.toLanguageTag()}"
        localizedPubDateCache[cacheKey]?.let { return it }

        val formatted = try {
            LocalDate.parse(pubDate, parser)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
        } catch (_: Exception) {
            pubDate
        }

        if (localizedPubDateCache.size > 2048) {
            localizedPubDateCache.clear()
        }
        localizedPubDateCache[cacheKey] = formatted
        return formatted
    }

    fun formatEpisodeMeta(context: Context, pubDate: String, duration: String): String {
        val localizedDate = formatPubDate(pubDate)
        return when {
            localizedDate.isNotBlank() && duration.isNotBlank() -> {
                context.getString(R.string.episode_meta, localizedDate, duration)
            }
            localizedDate.isNotBlank() -> localizedDate
            duration.isNotBlank() -> duration
            else -> ""
        }
    }
}