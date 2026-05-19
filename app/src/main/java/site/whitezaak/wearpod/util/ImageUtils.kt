package site.whitezaak.wearpod.util

object ImageUtils {
    /**
     * Normalize image URL by converting HTTP to HTTPS.
     */
    fun normalizeImageUrl(raw: String?): String {
        val url = raw.orEmpty()
        return if (url.startsWith("http://")) {
            url.replaceFirst("http://", "https://")
        } else {
            url
        }
    }
}