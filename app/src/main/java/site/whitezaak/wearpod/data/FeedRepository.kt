package site.whitezaak.wearpod.data

import android.app.Application
import android.content.Context
import android.net.Uri
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
import org.json.JSONArray
import org.json.JSONObject
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.domain.Podcast
import site.whitezaak.wearpod.settings.OpmlLinks
import site.whitezaak.wearpod.util.PubDateNormalizer
import java.net.HttpURLConnection
import java.net.URL

class FeedRepository(private val application: Application) {
    private val activeConnections = mutableMapOf<String, MutableSet<HttpURLConnection>>()

    companion object {
        const val MAX_CONCURRENT_INBOX_FETCH = 3
        const val MAX_INBOX_ITEMS_PER_FEED = 20
        const val MAX_TOTAL_INBOX_ITEMS = 250
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val PREFS_INBOX_CACHE = "wearpod_inbox_cache"
        const val KEY_INBOX_CACHE_TIMESTAMP_SUFFIX = "_timestamp"
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

    suspend fun <T> withUrlInputStream(
        url: String,
        group: String = "GENERAL",
        block: (java.io.InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
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

    private fun buildCustomOpmlUrl(code: String): String {
        return "${OpmlLinks.CUSTOM_OPML_CODE_URL_PREFIX}${Uri.encode(code.trim())}"
    }

    suspend fun loadSubscriptions(customId: String?): List<Podcast> = withContext(Dispatchers.IO) {
        try {
            if (customId != null) {
                val urlString = buildCustomOpmlUrl(customId)
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
        onBatchParsed: ((List<Episode>) -> Unit)? = null
    ): List<Episode> = withContext(Dispatchers.IO) {
        try {
            withUrlInputStream(feedUrl, group) { inputStream ->
                RssParser().parse(
                    inputStream = inputStream,
                    maxItems = MAX_TOTAL_INBOX_ITEMS,
                    onBatchParsed = onBatchParsed
                )
            }
        } catch (e: Exception) {
            Log.w("WearPod", "Failed to load feed episodes for $feedUrl", e)
            emptyList()
        }
    }

    suspend fun fetchInboxEpisodesConcurrently(
        podcasts: List<Podcast>,
        group: String,
        onBatchParsed: ((List<Episode>) -> Unit)? = null
    ): List<List<Episode>> = withContext(Dispatchers.IO) {
        val loadContext = currentCoroutineContext()
        val rssParser = RssParser()
        val limiter = Semaphore(MAX_CONCURRENT_INBOX_FETCH)

        coroutineScope {
            val fetchDeferreds = podcasts.map { podcast ->
                async {
                    limiter.withPermit {
                        loadContext.ensureActive()
                        try {
                            withUrlInputStream(podcast.feedUrl, group) { inputStream ->
                                rssParser.parse(
                                    inputStream = inputStream,
                                    maxItems = MAX_INBOX_ITEMS_PER_FEED,
                                    onBatchParsed = onBatchParsed
                                )
                            }
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
            putString(inboxCacheKey(ownerId), serializeEpisodes(episodes))
            putLong(inboxCacheTimestampKey(ownerId), System.currentTimeMillis())
        }
    }

    fun loadCachedInboxEpisodesState(ownerId: String?): List<Episode>? {
        val prefs = application.getSharedPreferences(PREFS_INBOX_CACHE, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(inboxCacheKey(ownerId), null)
        
        if (jsonStr.isNullOrEmpty()) return null

        return try {
            deserializeEpisodes(jsonStr)
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

    private fun serializeEpisodes(episodes: List<Episode>): String {
        val array = JSONArray()
        episodes.forEach { ep ->
            array.put(serializeEpisode(ep))
        }
        return array.toString()
    }

    private fun deserializeEpisodes(jsonStr: String): List<Episode> {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Episode>()
        for (i in 0 until array.length()) {
            list.add(deserializeEpisode(array.getJSONObject(i)))
        }
        return list
    }

    private fun serializeEpisode(ep: Episode): JSONObject {
        return JSONObject().apply {
            put("title", ep.title)
            put("description", ep.description)
            put("pubDate", ep.pubDate)
            put("audioUrl", ep.audioUrl)
            put("imageUrl", ep.imageUrl)
            put("podcastTitle", ep.podcastTitle)
            put("podcastImageUrl", ep.podcastImageUrl)
            put("duration", ep.duration)
        }
    }

    private fun deserializeEpisode(obj: JSONObject): Episode {
        val rawPubDate = obj.optString("pubDate")
        val normalizedPubDate = PubDateNormalizer.toCanonicalDate(rawPubDate) ?: rawPubDate
        return Episode(
            title = obj.getString("title"),
            description = obj.optString("description"),
            pubDate = normalizedPubDate,
            audioUrl = obj.getString("audioUrl"),
            imageUrl = obj.optString("imageUrl"),
            podcastTitle = obj.optString("podcastTitle"),
            podcastImageUrl = obj.optString("podcastImageUrl"),
            duration = obj.optString("duration"),
        )
    }
}
