package site.whitezaak.wearpod.offline

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import site.whitezaak.wearpod.data.OpmlParser
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** 起真实 socket，用 HttpURLConnection 走一遍页面与 API。 */
class OfflineWebServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val servers = mutableListOf<OfflineWebServer>()
    private val changes = mutableListOf<PageChange>()
    private lateinit var store: LocalSubscriptionStore

    private val opmlBytes = """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0"><head><title>t</title></head><body>
        <outline type="rss" text="A" title="A" xmlUrl="https://example.com/a.xml"/>
        <outline type="rss" text="B" title="B" xmlUrl="https://example.com/b.xml"/>
        </body></opml>
    """.trimIndent().toByteArray()

    private val fakeAssets = WebAssets { path ->
        when (path) {
            "index.html" -> "<h1>WearPod 本地上传</h1>"
            "style.css" -> "body{background:#f5f5f7}"
            "app.js" -> "console.log('ok')"
            else -> null
        }
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    private fun startServer(
        maxImportBytes: Int = 1024 * 1024,
        languageTag: String = "zh-CN",
    ): Int {
        store = LocalSubscriptionStore(
            dir = tempFolder.root,
            newParser = { OpmlParser { KXmlParser() } },
        )
        val api = OfflineApi(
            store = store,
            assets = fakeAssets,
            maxImportBytes = maxImportBytes,
            languageTag = languageTag,
            onChanged = { changes += it },
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = OfflineWebServer(
            bindAddress = "127.0.0.1",
            port = port,
            api = api,
            tempDir = File(tempFolder.root, "http-temp"),
        )
        assertTrue("server should start on port $port", server.startServer())
        servers += server
        return port
    }

    private fun request(
        port: Int,
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Pair<Int, String> {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            if (body != null) {
                doOutput = true
                contentType?.let { setRequestProperty("Content-Type", it) }
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            body?.let { payload -> connection.outputStream.use { it.write(payload) } }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `serves the upload page and static assets`() {
        val port = startServer()

        val (indexStatus, indexBody) = request(port, "GET", "/")
        assertEquals(200, indexStatus)
        assertTrue(indexBody.contains("WearPod"))

        assertEquals(200, request(port, "GET", "/style.css").first)
        assertEquals(404, request(port, "GET", "/nope").first)
    }

    @Test
    fun `script carries the app language`() {
        val zhPort = startServer(languageTag = "zh-CN")
        val zhScript = request(zhPort, "GET", "/app.js").second
        assertTrue(zhScript.contains("window.WEARPOD_LANG = \"zh\""))
        assertTrue(zhScript.contains("console.log"))

        servers.forEach { it.stop() }
        servers.clear()

        val enPort = startServer(languageTag = "en")
        val enScript = request(enPort, "GET", "/app.js").second
        assertTrue(enScript.contains("window.WEARPOD_LANG = \"en\""))
    }

    @Test
    fun `feeds start empty and api responses are not cached`() {
        val port = startServer()

        val connection = (URL("http://127.0.0.1:$port/api/feeds").openConnection() as HttpURLConnection)
        try {
            assertEquals(200, connection.responseCode)
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, JSONObject(body).getJSONArray("podcasts").length())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `raw body import adds feeds and reports change`() {
        val port = startServer()

        val (status, body) = request(port, "POST", "/api/import", opmlBytes, "application/xml")

        assertEquals(200, status)
        val json = JSONObject(body)
        assertEquals(2, json.getInt("added"))
        assertEquals(0, json.getInt("skipped"))
        assertEquals(2, json.getInt("total"))
        assertEquals(listOf<PageChange>(PageChange.Imported(2, 0, 2)), changes)

        val feeds = request(port, "GET", "/api/feeds").second
        assertTrue(feeds.contains("https://example.com/a.xml"))
        assertTrue(feeds.contains("https://example.com/b.xml"))
    }

    @Test
    fun `import without body is rejected`() {
        val port = startServer()

        val (status, body) = request(port, "POST", "/api/import", null, "application/xml")

        assertEquals(400, status)
        assertEquals("no_file", JSONObject(body).getString("error"))
    }

    @Test
    fun `import of garbage is rejected as invalid opml`() {
        val port = startServer()

        val (status, body) = request(port, "POST", "/api/import", "definitely not opml".toByteArray(), "application/xml")

        assertEquals(400, status)
        assertEquals("invalid_opml", JSONObject(body).getString("error"))
        assertEquals(0, changes.size)
    }

    @Test
    fun `import over the limit is rejected with 413`() {
        val port = startServer(maxImportBytes = 16)

        val (status, body) = request(port, "POST", "/api/import", ByteArray(64) { 'x'.code.toByte() }, "application/xml")

        assertEquals(413, status)
        assertEquals("too_large", JSONObject(body).getString("error"))
    }

    @Test
    fun `delete removes a feed and reports how many were deleted`() {
        val port = startServer()
        request(port, "POST", "/api/import", opmlBytes, "application/xml")
        changes.clear()

        val payload = """{"feedUrls":["https://example.com/a.xml"]}""".toByteArray()
        val (status, body) = request(port, "POST", "/api/delete", payload, "application/json")

        assertEquals(200, status)
        val json = JSONObject(body)
        assertEquals(1, json.getInt("deleted"))
        assertEquals(1, json.getInt("total"))
        assertEquals(listOf<PageChange>(PageChange.Deleted(1, 1)), changes)

        val feeds = request(port, "GET", "/api/feeds").second
        assertFalse(feeds.contains("example.com/a.xml"))
        assertTrue(feeds.contains("example.com/b.xml"))
    }

    @Test
    fun `deleting an unknown feed reports zero deleted and no change event`() {
        val port = startServer()
        request(port, "POST", "/api/import", opmlBytes, "application/xml")
        changes.clear()

        val payload = """{"feedUrls":["https://example.com/missing.xml"]}""".toByteArray()
        val (status, body) = request(port, "POST", "/api/delete", payload, "application/json")

        assertEquals(200, status)
        assertEquals(0, JSONObject(body).getInt("deleted"))
        assertEquals(2, JSONObject(body).getInt("total"))
        assertEquals(0, changes.size)
    }

    @Test
    fun `delete with a broken payload is rejected`() {
        val port = startServer()

        val (status, body) = request(port, "POST", "/api/delete", "not json".toByteArray(), "application/json")

        assertEquals(400, status)
        assertEquals("bad_request", JSONObject(body).getString("error"))
    }
}
