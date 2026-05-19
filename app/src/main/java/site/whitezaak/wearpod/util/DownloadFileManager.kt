package site.whitezaak.wearpod.util

import android.content.Context
import java.io.File

object DownloadFileManager {
    /**
     * Generate the local filename for a downloaded episode based on its audio URL.
     */
    fun filenameForAudioUrl(audioUrl: String): String {
        return "episode_${audioUrl.hashCode()}.mp3"
    }

    /**
     * Get the local file for a downloaded episode.
     */
    fun fileForAudioUrl(context: Context, audioUrl: String): File {
        return File(context.filesDir, filenameForAudioUrl(audioUrl))
    }
}