package com.example.wearpod.presentation

import android.content.Context
import com.example.wearpod.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object EpisodeTextFormatter {
    private val parser = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

    fun formatPubDate(pubDate: String, locale: Locale = Locale.getDefault()): String {
        if (pubDate.isBlank()) {
            return ""
        }

        return try {
            LocalDate.parse(pubDate, parser)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
        } catch (_: Exception) {
            pubDate
        }
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