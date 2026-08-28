package site.whitezaak.wearpod.util

object DurationUtils {
    fun parseDurationToMs(rawDuration: String?): Long {
        // 任一分段非数字即整体无效（如 "12:ab"），避免把残缺分段误读成秒数
        val parts = rawDuration.orEmpty().trim().split(":").map { it.toLongOrNull() }
        if (parts.any { it == null }) {
            return 0L
        }
        val segments = parts.filterNotNull()
        val totalSeconds = when (segments.size) {
            3 -> segments[0] * 3600L + segments[1] * 60L + segments[2]
            2 -> segments[0] * 60L + segments[1]
            1 -> segments[0]
            else -> return 0L
        }
        return (totalSeconds * 1000L).coerceAtLeast(0L)
    }
}