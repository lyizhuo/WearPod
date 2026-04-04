package site.whitezaak.wearpod.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

object PubDateNormalizer {
    private val canonicalFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    private val canonicalParser = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    private val dateTimeParsers = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    )
    private val legacyPatterns = arrayOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss z",
    )

    fun toCanonicalDate(raw: String): String? {
        val date = toLocalDate(raw) ?: return null
        return date.format(canonicalFormatter)
    }

    fun toLocalDate(raw: String): LocalDate? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return null
        }

        runCatching {
            LocalDate.parse(trimmed, canonicalParser)
        }.getOrNull()?.let { return it }

        parseInstant(trimmed)?.let { instant ->
            return instant.atZone(ZoneOffset.UTC).toLocalDate()
        }

        return null
    }

    private fun parseInstant(raw: String): Instant? {
        for (candidate in dateCandidates(raw)) {
            for (parser in dateTimeParsers) {
                try {
                    val temporal = parser.parseBest(candidate, ZonedDateTime::from, OffsetDateTime::from)
                    when (temporal) {
                        is ZonedDateTime -> return temporal.toInstant()
                        is OffsetDateTime -> return temporal.toInstant()
                    }
                } catch (_: Exception) {
                    // Try next parser.
                }
            }

            for (pattern in legacyPatterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = true }
                    val parsed: Date = sdf.parse(candidate) ?: continue
                    return Instant.ofEpochMilli(parsed.time)
                } catch (_: Exception) {
                    // Try next legacy parser.
                }
            }
        }
        return null
    }

    private fun dateCandidates(raw: String): List<String> {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        val withoutTrailingZoneLabel = normalized.replace(Regex("\\s*\\([^)]*\\)$"), "").trim()
        val utcNormalized = withoutTrailingZoneLabel
            .replace(" UTC", " +0000", ignoreCase = true)
            .replace(" GMT", " +0000", ignoreCase = true)

        return listOf(normalized, withoutTrailingZoneLabel, utcNormalized)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}