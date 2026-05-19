package site.whitezaak.wearpod.data

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.domain.Podcast
import site.whitezaak.wearpod.settings.OpmlLinks
import site.whitezaak.wearpod.util.EpisodeJson
import site.whitezaak.wearpod.util.NetworkConfig
import java.net.HttpURLConnection
import java.net.URL

class FeedRepository(private val application: Application) {
    private val activeConnections = mutableMapOf<String, MutableSet<HttpURLConnection>>()

    companion object {
        const val MAX_CONCURRENT_INBOX_FETCH = 5
        const val MAX_INBOX_ITEMS_PER_FEED = 20
        const val MAX_TOTAL_INBOX_ITEMS = 250
        const val PREFS_INBOX_CACHE = "wearpod_inbox_cache"
        const val KEY_INBOX_CACHE_TIMESTAMP_SUFFIX = "_timestamp"
        private const val PREFS_HTTP_CACHE = "wearpod_http_cache"
    }

    private fun registerConnection(group: String, connection: HttpURLConnection) {
        synchronized(activeConnections) {
            activeConnections.getOrPut(group) { mutableSetOf() }.add(connection)
        }
    }

    private fun unregisterConnection(group: String, connection: HttpURLConnection) {
        synchronized(activeConnections) {
            activeConnections[group]?.remove(connection)
            if (activeConnections[group].isNullOrEmpty()) {
                activeConnections.remove(group)
            }
        }
    }

    fun cancelActiveConnections(group: String) {
        val connections = synchronized(activeConnections) {
            activeConnections[group]?.toList().orEmpty()
        }
        connections.forEach { connection ->
            runCatching { connection.disconnect() }
        }
    }

    private fun getHttpCacheHeaders(feedUrl: String): Pair<String?, String?> {
        val prefs = application.getSharedPreferences(PREFS_HTTP_CACHE, Context.MODE_PRIVATE)
        val etag = prefs.getString("${feedUrl}_etag", null)
        val lastModified = prefs.getString("${feedUrl}_last_modified", null)
        return Pair(etag, lastModified)
    }

    private fun saveHttpCacheHeaders(feedUrl: String, etag: String?, lastModified: String?) {
        val prefs = application.getSharedPreferences(PREFS_HTTP_CACHE, Context.MODE_PRIVATE)
        prefs.edit {
            if (etag != null) putString("${feedUrl}_etag", etag)
            if (lastModified != null) putString("${feedUrl}_last_modified", lastModified)
        }
    }

    private suspend fun fetchFeedWithConditionalRequest(
        feedUrl: String,
        group: String,
        maxItems: Int,
        onBatchParsed: ((List<Episode>) -> Unit)? = null,
        connectTimeoutMs: Int = NetworkConfig.CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = NetworkConfig.READ_TIMEOUT_MS,
        useConditionalRequest: Boolean = true,
    ): List<Episode>? {
        val connection = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        if (useConditionalRequest) {
            val (cachedETag, cachedLastModified) = getHttpCacheHeaders(feedUrl)
            if (cachedETag != null) connection.setRequestProperty("If-None-Match", cachedETag)
            if (cachedLastModified != null) connection.setRequestProperty("If-Modified-Since", cachedLastModified)
        }

        registerConnection(group, connection)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return null
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w("WearPod", "HTTP ${connection.responseCode} for $feedUrl")
                return emptyList()
            }
            val newETag = connection.getHeaderField("ETag")
            val newLastModified = connection.getHeaderField("Last-Modified")
            if (newETag != null || newLastModified != null) {
                saveHttpCacheHeaders(feedUrl, newETag, newLastModified)
            }
            return connection.inputStream.buffered().use { inputStream ->
                RssParser().parse(inputStream, maxItems, onBatchParsed)
            }
        } finally {
            unregisterConnection(group, connection)
            connection.disconnect()
        }
    }

    suspend fun <T> withUrlInputStream(
        url: String,
        group: String = "GENERAL",
        block: (java.io.InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NetworkConfig.CONNECT_TIMEOUT_MS
            readTimeout = NetworkConfig.READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        registerConnection(group, connection)

        try {
            connection.inputStream.buffered().use(block)
        } finally {
            unregisterConnection(group, connection)
            connection.disconnect()
        }
    }

    suspend fun loadSubscriptions(customId: String?): List<Podcast> = withContext(Dispatchers.IO) {
        try {
            if (customId != null) {
                val urlString = OpmlLinks.buildCustomOpmlUrl(customId)
                withUrlInputStream(urlString) { inputStream ->
                    OpmlParser().parse(inputStream)
                }
            } else {
                val inputStream = application.resources.openRawResource(R.raw.subscriptions)
                OpmlParser().parse(inputStream)
            }
        } catch (e: Exception) {
            Log.e("WearPod", "Failed to load subscriptions", e)
            emptyList()
        }
    }

    suspend fun fetchFeedEpisodes(
        feedUrl: String,
        group: String,
        onBatchParsed: ((List<Episode>) -> Unit)? = null,
        connectTimeoutMs: Int = NetworkConfig.CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = NetworkConfig.READ_TIMEOUT_MS,
        useConditionalRequest: Boolean = true,
    ): List<Episode>? = withContext(Dispatchers.IO) {
        try {
            fetchFeedWithConditionalRequest(feedUrl, group, MAX_TOTAL_INBOX_ITEMS, onBatchParsed,
                connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs,
                useConditionalRequest = useConditionalRequest)
        } catch (e: Exception) {
            Log.w("WearPod", "Failed to load feed episodes for $feedUrl", e)
            null
        }
    }

    suspend fun fetchInboxEpisodesConcurrently(
        podcasts: List<Podcast>,
        group: String,
        onBatchParsed: ((List<Episode>) -> Unit)? = null
    ): List<List<Episode>> = withContext(Dispatchers.IO) {
        val loadContext = currentCoroutineContext()
        val limiter = Semaphore(MAX_CONCURRENT_INBOX_FETCH)

        coroutineScope {
            val fetchDeferreds = podcasts.map { podcast ->
                async {
                    limiter.withPermit {
                        loadContext.ensureActive()
                        try {
                            fetchFeedWithConditionalRequest(
                                podcast.feedUrl, group, MAX_INBOX_ITEMS_PER_FEED, onBatchParsed
                            ) ?: emptyList()
                        } catch (e: Exception) {
                            Log.w("WearPod", "Failed to fetch inbox feed: ${podcast.feedUrl}", e)
                            emptyList()
                        }
                    }
                }
            }
            fetchDeferreds.awaitAll()
        }
    }

    fun saveInboxEpisodesState(episodes: List<Episode>, ownerId: String?) {
        val prefs = application.getSharedPreferences(PREFS_INBOX_CACHE, Context.MODE_PRIVATE)
        prefs.edit {
            putString(inboxCacheKey(ownerId), EpisodeJson.serializeEpisodes(episodes))
            putLong(inboxCacheTimestampKey(ownerId), System.currentTimeMillis())
        }
    }

    fun loadCachedInboxEpisodesState(ownerId: String?): List<Episode>? {
        val prefs = application.getSharedPreferences(PREFS_INBOX_CACHE, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(inboxCacheKey(ownerId), null)
        
        if (jsonStr.isNullOrEmpty()) return null

        return try {
            EpisodeJson.deserializeEpisodes(jsonStr)
        } catch (e: Exception) {
            Log.w("WearPod", "Failed to load cached inbox for owner=$ownerId", e)
            null
        }
    }

    fun getInboxCacheTimestamp(ownerId: String?): Long {
        val prefs = application.getSharedPreferences(PREFS_INBOX_CACHE, Context.MODE_PRIVATE)
        return prefs.getLong(inboxCacheTimestampKey(ownerId), 0L)
    }

    private fun inboxCacheKey(ownerId: String?): String {
        return "inbox_list_${ownerId ?: "local_default"}"
    }

    private fun inboxCacheTimestampKey(ownerId: String?): String {
        return "${inboxCacheKey(ownerId)}$KEY_INBOX_CACHE_TIMESTAMP_SUFFIX"
    }
}
