package site.whitezaak.wearpod.settings

import android.net.Uri

object OpmlLinks {
    const val CUSTOM_OPML_CODE_URL_PREFIX = "https://pod-upload.whitezaak.site/share/select/?code="
    const val OPML_UPLOAD_QR_IMAGE_URL = "https://pod.whitezaak.site/upload_qr.png"

    fun buildCustomOpmlUrl(code: String): String {
        return "${CUSTOM_OPML_CODE_URL_PREFIX}${Uri.encode(code.trim())}"
    }
}