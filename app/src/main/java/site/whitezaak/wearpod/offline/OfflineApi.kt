package site.whitezaak.wearpod.offline

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI

/**
 * 离线页面的 HTTP 路由层。
 *
 * 只依赖 NanoHTTPD 的类型，不 import android.*，所以可以在 JVM 单测里起真实 socket 验证。
 * 上传走**原始请求体**（不调用 [IHTTPSession.parseBody]）——既避开 NanoHTTPD 的 multipart
 * 临时文件代码路径（CVE-2022-21230），也省掉一层解析。
 *
 * 页面语言随 App 语言：把 `window.WEARPOD_LANG` 注入 `/app.js` 的头部，由脚本选文案表。
 */
class OfflineApi(
    private val store: LocalSubscriptionStore,
    private val assets: WebAssets,
    private val maxImportBytes: Int = DEFAULT_MAX_IMPORT_BYTES,
    /** App 当前语言标签（如 "zh-CN" / "en"），只用来决定网页文案表。 */
    languageTag: String = "zh-CN",
    /** 成功导入/删除后回调，用于累计变更数量；此时会话仍保持 Running。 */
    private val onChanged: (PageChange) -> Unit = {},
    /** 手机网页点「完成」后回调；由服务端关闭 HTTP 服务并显示手表结束页。 */
    private val onComplete: () -> Unit = {},
) {

    private val pageLanguage: String = if (languageTag.lowercase().startsWith("en")) "en" else "zh"

    fun responseFor(session: IHTTPSession): Response {
        val uri = session.uri?.substringBefore('?').orEmpty()
        val method = session.method
        return when {
            method == Method.GET && (uri == "/" || uri == "/index.html") -> htmlPage()
            method == Method.GET && uri == "/style.css" -> asset("style.css", MIME_CSS)
            method == Method.GET && uri == "/app.js" -> script()
            method == Method.GET && uri == "/api/feeds" -> feedsResponse()
            // 变更口必须同源：本服务没有任何鉴权，否则手机浏览器里任意网页都能
            // 用 text/plain 的 no-cors POST 清空订阅（简单请求不触发预检）。
            method == Method.POST && !isSameOrigin(session) ->
                errorResponse(Response.Status.FORBIDDEN, "forbidden")
            method == Method.POST && uri == "/api/import" -> importResponse(session)
            method == Method.POST && uri == "/api/delete" -> deleteResponse(session)
            method == Method.POST && uri == "/api/complete" -> completeResponse()
            else -> errorResponse(Response.Status.NOT_FOUND, "not_found")
        }
    }

    /**
     * 浏览器跨源请求一定带 `Origin`；它必须与 `Host` 一致。
     * 不带 `Origin` 的（非浏览器客户端、同源导航）放行。
     */
    private fun isSameOrigin(session: IHTTPSession): Boolean {
        val headers = session.headers ?: return true
        val origin = headers["origin"] ?: return true
        val host = headers["host"] ?: return false
        val originHost = runCatching { URI(origin).rawAuthority }.getOrNull() ?: return false
        return originHost.equals(host, ignoreCase = true)
    }

    private fun htmlPage(): Response {
        val html = assets.read("index.html")
            ?: return errorResponse(Response.Status.INTERNAL_ERROR, "missing_assets")
        return text(Response.Status.OK, MIME_HTML, html)
    }

    private fun script(): Response {
        val js = assets.read("app.js")
            ?: return errorResponse(Response.Status.INTERNAL_ERROR, "missing_assets")
        return text(Response.Status.OK, MIME_JS, "window.WEARPOD_LANG = \"$pageLanguage\";\n$js")
    }

    private fun asset(path: String, mime: String): Response {
        val content = assets.read(path)
            ?: return errorResponse(Response.Status.INTERNAL_ERROR, "missing_assets")
        return text(Response.Status.OK, mime, content)
    }

    private fun feedsResponse(): Response {
        val array = JSONArray()
        for (podcast in store.load().orEmpty()) {
            array.put(
                JSONObject().apply {
                    put("title", podcast.title)
                    put("feedUrl", podcast.feedUrl)
                }
            )
        }
        return json(Response.Status.OK, JSONObject().apply {
            put("ok", true)
            put("podcasts", array)
        })
    }

    private fun importResponse(session: IHTTPSession): Response {
        val declaredLength = session.headers?.get("content-length")?.toLongOrNull()
        if (declaredLength == null || declaredLength <= 0L) {
            return errorResponse(Response.Status.BAD_REQUEST, "no_file")
        }
        if (declaredLength > maxImportBytes) {
            return errorResponse(Response.Status.PAYLOAD_TOO_LARGE, "too_large")
        }
        val body = try {
            readExactBody(session.inputStream, declaredLength.toInt())
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "no_file")
        }
        if (body.isEmpty()) return errorResponse(Response.Status.BAD_REQUEST, "no_file")

        val result = try {
            store.importOpml(ByteArrayInputStream(body))
        } catch (_: CorruptSubscriptionStoreException) {
            // 落盘文件坏了：宁可报错，也不能把用户的整库订阅覆盖成这一份 OPML。
            return errorResponse(Response.Status.INTERNAL_ERROR, "store_corrupt")
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_opml")
        }
        onChanged(PageChange.Imported(result.added, result.skipped, result.total))
        return json(Response.Status.OK, JSONObject().apply {
            put("ok", true)
            put("added", result.added)
            put("skipped", result.skipped)
            put("total", result.total)
        })
    }

    private fun deleteResponse(session: IHTTPSession): Response {
        val declaredLength = session.headers?.get("content-length")?.toLongOrNull()
        if (declaredLength == null || declaredLength <= 0L || declaredLength > MAX_DELETE_BYTES) {
            return errorResponse(Response.Status.BAD_REQUEST, "bad_request")
        }
        val body = try {
            String(readExactBody(session.inputStream, declaredLength.toInt()), Charsets.UTF_8)
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "bad_request")
        }
        val feedUrls = try {
            val array = JSONObject(body).optJSONArray("feedUrls")
                ?: return errorResponse(Response.Status.BAD_REQUEST, "bad_request")
            (0 until array.length()).mapNotNull { index -> array.optString(index, "").takeIf { it.isNotBlank() } }
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "bad_request")
        }

        val result = try {
            store.delete(feedUrls)
        } catch (_: CorruptSubscriptionStoreException) {
            return errorResponse(Response.Status.INTERNAL_ERROR, "store_corrupt")
        }
        if (result.removed > 0) onChanged(PageChange.Deleted(result.removed, result.remaining.size))
        return json(Response.Status.OK, JSONObject().apply {
            put("ok", true)
            put("deleted", result.removed)
            put("total", result.remaining.size)
        })
    }

    /**
     * 用户明确结束本地上传会话。
     *
     * 导入/删除本身不再切换到 Completed——否则用户删一个订阅就会看到结束页，但服务
     * 还开着，手表端和网页端的状态对不上。只有这个显式动作才结束会话。
     */
    private fun completeResponse(): Response {
        onComplete()
        return json(Response.Status.OK, JSONObject().apply { put("ok", true) })
    }

    /**
     * 按 Content-Length 精确读取。
     *
     * [IHTTPSession.getInputStream] 是裸 socket 流：keep-alive 下客户端不会关连接，
     * 读到 EOF 会一直阻塞（这正是 NanoHTTPD 自己的 parseBody 也按长度读的原因）。
     */
    private fun readExactBody(stream: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = stream.read(buffer, read, length - read)
            if (chunk <= 0) throw java.io.IOException("unexpected end of request body")
            read += chunk
        }
        return buffer
    }

    private fun json(status: Response.IStatus, payload: JSONObject): Response =
        text(status, MIME_JSON, payload.toString())

    private fun errorResponse(status: Response.IStatus, code: String): Response =
        json(status, JSONObject().apply {
            put("ok", false)
            put("error", code)
        })

    private fun text(status: Response.IStatus, mime: String, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, mime, body).apply {
            addHeader("Cache-Control", "no-store")
        }

    companion object {
        const val DEFAULT_MAX_IMPORT_BYTES = 5 * 1024 * 1024
        private const val MAX_DELETE_BYTES = 64 * 1024
        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val MIME_CSS = "text/css; charset=utf-8"
        private const val MIME_JS = "application/javascript; charset=utf-8"
    }
}
