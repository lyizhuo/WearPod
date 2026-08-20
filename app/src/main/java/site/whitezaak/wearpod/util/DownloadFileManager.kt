package site.whitezaak.wearpod.util

import android.content.Context
import java.io.File
import java.security.MessageDigest

object DownloadFileManager {
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "mp4", "aac", "ogg", "opus", "wav", "flac", "webm", "m4b", "m4v"
    )

    /**
     * Generate the local filename for a downloaded episode based on its audio URL.
     * 使用 SHA-1 内容寻址，避免 String.hashCode 碰撞覆盖文件；保留原始扩展名便于识别与 MIME 推断。
     */
    fun filenameForAudioUrl(audioUrl: String): String {
        return "episode_${sha1Hex(audioUrl)}$extensionFor(audioUrl)"
    }

    /**
     * Get the local file for a downloaded episode.
     * 优先新命名（SHA-1），回退兼容旧版本（hashCode）的已下载文件，避免升级后已下载节目丢失。
     */
    fun fileForAudioUrl(context: Context, audioUrl: String): File {
        val newFile = File(context.filesDir, filenameForAudioUrl(audioUrl))
        if (newFile.exists()) {
            return newFile
        }
        val legacyFile = File(context.filesDir, "episode_${audioUrl.hashCode()}.mp3")
        return if (legacyFile.exists()) legacyFile else newFile
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            sb.append("0123456789abcdef"[((byte.toInt() shr 4) and 0x0F)])
            sb.append("0123456789abcdef"[(byte.toInt() and 0x0F)])
        }
        return sb.toString()
    }

    private fun extensionFor(url: String): String {
        val path = url.substringBefore('?')
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in AUDIO_EXTENSIONS) ".$ext" else ".mp3"
    }
}
