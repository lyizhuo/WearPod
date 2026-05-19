package site.whitezaak.wearpod.util

object DurationUtils {
    fun parseDurationToMs(rawDuration: String?): Long {
        val parts = rawDuration.orEmpty().trim().split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) {
            return 0L
        }
        val totalSeconds = when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            1 -> parts[0]
            else -> return 0L
        }
        return (totalSeconds * 1000L).coerceAtLeast(0L)
    }
}