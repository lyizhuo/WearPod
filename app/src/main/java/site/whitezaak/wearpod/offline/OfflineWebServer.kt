package site.whitezaak.wearpod.offline

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 本地 HTTP 服务。
 *
 * 关键设计（复审后修正）：
 * - **绑定到具体接口地址**而不是 0.0.0.0：这套 API 没有鉴权，若绑全地址，
 *   手表同时连着家里 Wi-Fi 时会把「读订阅读订阅列表 / 增删改」暴露在那条网络上。
 * - 临时目录指向应用私有 cache，规避 NanoHTTPD 的 CVE-2022-21230（无补丁版本）。
 */
class OfflineWebServer(
    bindAddress: String,
    port: Int,
    private val api: OfflineApi,
    tempDir: File,
) : NanoHTTPD(bindAddress, port) {

    init {
        val privateTempDir = tempDir
        setTempFileManagerFactory { PrivateTempFileManager(privateTempDir) }
    }

    override fun serve(session: IHTTPSession): Response = try {
        api.responseFor(session)
    } catch (_: Exception) {
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "text/plain; charset=utf-8",
            "internal_error",
        )
    }

    fun startServer(): Boolean = try {
        start(SOCKET_READ_TIMEOUT, false)
        true
    } catch (_: Exception) {
        false
    }

    private class PrivateTempFileManager(private val dir: File) : TempFileManager {

        private val opened = mutableListOf<TempFile>()

        override fun createTempFile(filenameHint: String?): TempFile {
            dir.mkdirs()
            val file = File.createTempFile("wearpod-offline-", "", dir)
            val tempFile = PrivateTempFile(file)
            opened += tempFile
            return tempFile
        }

        override fun clear() {
            for (tempFile in opened) {
                runCatching { tempFile.delete() }
            }
            opened.clear()
        }
    }

    private class PrivateTempFile(private val file: File) : TempFile {

        private val stream = FileOutputStream(file)

        override fun delete() {
            stream.close()
            if (!file.delete()) {
                file.deleteOnExit()
            }
        }

        override fun getName(): String = file.absolutePath

        override fun open(): OutputStream = stream
    }
}
