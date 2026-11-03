package site.whitezaak.wearpod.settings

import android.net.Uri

object OpmlLinks {
    /** New primary Serverless endpoint (Cloudflare Pages / Vercel). */
    const val CUSTOM_OPML_CODE_URL_PREFIX = "https://pod.whitezaak.site/api/opml/"

    /** Legacy FileCodeBox endpoint — kept as fallback for backward compatibility. */
    const val LEGACY_OPML_CODE_URL_PREFIX = "https://pod-upload.whitezaak.site/share/select/?code="

    const val OPML_UPLOAD_QR_IMAGE_URL = "https://pod.whitezaak.site/upload_qr.png"

    /** Short share link shown in QR / web. */
    const val SHORT_SHARE_URL_PREFIX = "https://pod.whitezaak.site/s/"

    fun buildCustomOpmlUrl(code: String): String {
        return "${CUSTOM_OPML_CODE_URL_PREFIX}${Uri.encode(code.trim())}"
    }

    fun buildLegacyOpmlUrl(code: String): String {
        return "${LEGACY_OPML_CODE_URL_PREFIX}${Uri.encode(code.trim())}"
    }

    fun buildShortShareUrl(code: String): String {
        return "${SHORT_SHARE_URL_PREFIX}${Uri.encode(code.trim())}"
    }
}