package site.whitezaak.wearpod.presentation

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.data.OpmlParser
import site.whitezaak.wearpod.data.RssParser
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.domain.Podcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import java.util.Locale
import android.util.Log
import android.content.ComponentName
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import site.whitezaak.wearpod.settings.AppLanguageManager
import site.whitezaak.wearpod.settings.OpmlLinks
import site.whitezaak.wearpod.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import android.media.AudioManager
import android.content.Context
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private enum class RequestGroup {
        INBOX,
        FEED,
        GENERAL,
    }

    private data class FeedCacheEntry(
        val episodes: List<Episode>,
        val timestampMs: Long,
    )

    private data class LastPlaybackState(
        val episode: Episode,
        val positionMs: Long,
    )

    private fun buildCustomOpmlUrl(code: String): String {
        return "${OpmlLinks.CUSTOM_OPML_CODE_URL_PREFIX}${Uri.encode(code.trim())}"
    }

    private val isDebuggableApp: Boolean by lazy {
        val flags = getApplication<Application>().applicationInfo.flags
        (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun debugLog(message: String) {
        if (isDebuggableApp) {
            Log.d("WearPod", message)
        }
    }

    private val _podcasts = MutableStateFlow<List<Podcast>>(emptyList())
    val podcasts: StateFlow<List<Podcast>> = _podcasts.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _inboxEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val inboxEpisodes: StateFlow<List<Episode>> = _inboxEpisodes.asStateFlow()

    private val _visibleInboxEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val visibleInboxEpisodes: StateFlow<List<Episode>> = _visibleInboxEpisodes.asStateFlow()

    private val _visibleInboxEpisodeGroups = MutableStateFlow<List<InboxEpisodeGroup>>(emptyList())
    val visibleInboxEpisodeGroups: StateFlow<List<InboxEpisodeGroup>> = _visibleInboxEpisodeGroups.asStateFlow()

    val hasMoreInboxEpisodes = MutableStateFlow(false)

    private val _customOpmlId = MutableStateFlow<String?>(null)
    val customOpmlId: StateFlow<String?> = _customOpmlId.asStateFlow()

    private val _appLanguageTag = MutableStateFlow(AppLanguageManager.LANGUAGE_ENGLISH)
    val appLanguageTag: StateFlow<String> = _appLanguageTag.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    val mediaController = MutableStateFlow<MediaController?>(null)
    
    val isPlaying = MutableStateFlow(false)
    val isBuffering = MutableStateFlow(false)
    val currentPlayingEpisode = MutableStateFlow<Episode?>(null)

    private val _playlist = MutableStateFlow<List<Episode>>(emptyList())
    val playlist: StateFlow<List<Episode>> = _playlist.asStateFlow()

    private val _downloadedEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val downloadedEpisodes: StateFlow<List<Episode>> = _downloadedEpisodes.asStateFlow()

    private val _downloadingEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val downloadingEpisodes: StateFlow<List<Episode>> = _downloadingEpisodes.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    val isRefreshingInbox = MutableStateFlow(false)
    val currentSleepTimerMode = MutableStateFlow(SleepTimerMode.Off)
    val currentSleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    private var sleepTimerJob: Job? = null
    private var pauseOnEpisodeEnd = false
    private var inboxLoadJob: Job? = null
    private var feedLoadJob: Job? = null
    private var inboxVisibleLimit = 0
    private var currentFeedUrl: String? = null
    private var isInboxScreenVisible = false
    private var visibleFeedUrl: String? = null
    private val feedCache = mutableMapOf<String, FeedCacheEntry>()
    private val activeConnections = mutableMapOf<RequestGroup, MutableSet<HttpURLConnection>>()
    private var lastPlaybackState: LastPlaybackState? = null
    private var lastPlaybackPersistTimeMs = 0L
    private var pendingEpisodeToPlay: Episode? = null
    private var pendingSeekPositionMs: Long? = null
    private var isPlayerScreenVisible = false
    private var isAppInForeground = true
    private var lastInboxBatchPublishTimeMs = 0L
    private var lastFeedBatchPublishTimeMs = 0L
    private val episodeTimestampCache = ConcurrentHashMap<String, Long>()

    val isLoadingFeed = MutableStateFlow(false)

    private companion object {
        // WearOS device friendly limits: avoid bursting too many sockets/parsers at once.
        const val MAX_CONCURRENT_INBOX_FETCH = 3
        const val MAX_INBOX_ITEMS_PER_FEED = 20
        const val MAX_TOTAL_INBOX_ITEMS = 250
        const val INITIAL_VISIBLE_INBOX_ITEMS = 100
        const val INBOX_PAGE_SIZE = 50
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val PREFS_INBOX_CACHE = "wearpod_inbox_cache"
        const val PREFS_DOWNLOADS = "wearpod_downloads"
        const val PREFS_PLAYBACK = "wearpod_playback"
        const val KEY_LAST_EPISODE = "last_episode"
        const val KEY_LAST_POSITION = "last_position_ms"
        const val KEY_INBOX_CACHE_TIMESTAMP_SUFFIX = "_timestamp"
        const val FEED_CACHE_TTL_MS = 2 * 60 * 1000L
        const val INBOX_REFRESH_TTL_MS = 2 * 60 * 1000L
        const val BATCH_UI_PUBLISH_INTERVAL_MS = 250L
        const val SEEK_SETTLE_DELAY_MS = 120L
        const val PLAYBACK_PERSIST_INTERVAL_MS = 1_500L
        val PUB_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    }
    
    val currentPosition = MutableStateFlow(0L)
    val currentDuration = MutableStateFlow(0L)
    private var progressJob: Job? = null

    init {
        val prefs = getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
        val initialCustomId = prefs.getString("custom_opml_id", null)
        _customOpmlId.value = initialCustomId
        _appLanguageTag.value = AppLanguageManager.getSelectedLanguageTag(application)
        loadCachedInboxEpisodesState(initialCustomId)
        loadDownloadedEpisodesState()
        loadLastPlaybackState()
        initializeController()

        viewModelScope.launch {
            // Delay heavy initialization to ensure UI layout passes are smooth
            delay(500)
            loadSubscriptions()
        }
    }

    private fun loadSubscriptions() {
        viewModelScope.launch {
            val loadedPodcasts = withContext(Dispatchers.IO) {
                try {
                    val prefs = getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
                    val customId = prefs.getString("custom_opml_id", null)
                    _customOpmlId.value = customId
                    
                    if (customId != null) {
                        val urlString = buildCustomOpmlUrl(customId)
                        withUrlInputStream(urlString) { inputStream ->
                            OpmlParser().parse(inputStream)
                        }
                    } else {
                        val inputStream = getApplication<Application>().resources.openRawResource(R.raw.subscriptions)
                        OpmlParser().parse(inputStream)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList<Podcast>()
                }
            }
            if (loadedPodcasts.isNotEmpty()) {
                _podcasts.value = loadedPodcasts
                if (isInboxScreenVisible) {
                    loadInboxEpisodes(force = true)
                }
            } else if (isRefreshingInbox.value) {
                isRefreshingInbox.value = false
            }
        }
    }

    fun loadCustomOpml(id: String) {
        viewModelScope.launch {
            _customOpmlId.value = id
            loadCachedInboxEpisodesState(id, clearIfMissing = true)

            val loadedPodcasts = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
                        .edit { putString("custom_opml_id", id) }

                    val urlString = buildCustomOpmlUrl(id)
                    withUrlInputStream(urlString) { inputStream ->
                        OpmlParser().parse(inputStream)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList<Podcast>()
                }
            }
            if (loadedPodcasts.isNotEmpty()) {
                _podcasts.value = loadedPodcasts
                if (isInboxScreenVisible) {
                    loadInboxEpisodes(force = true)
                }
            } else if (isRefreshingInbox.value) {
                isRefreshingInbox.value = false
            }
        }
    }

    fun setAppLanguage(languageTag: String) {
        AppLanguageManager.updateLanguage(getApplication(), languageTag)
        _appLanguageTag.value = AppLanguageManager.getSelectedLanguageTag(getApplication())
    }

    fun onInboxScreenEntered(forceRefresh: Boolean = false) {
        isInboxScreenVisible = true
        if (_podcasts.value.isEmpty()) {
            return
        }
        if (forceRefresh || shouldRefreshInbox()) {
            loadInboxEpisodes(force = true)
        }
    }

    fun onInboxScreenExited() {
        isInboxScreenVisible = false
        cancelInboxLoading()
    }

    fun onFeedScreenEntered(feedUrl: String) {
        visibleFeedUrl = feedUrl
        loadEpisodes(feedUrl)
    }

    fun onFeedScreenExited(feedUrl: String) {
        if (visibleFeedUrl == feedUrl) {
            visibleFeedUrl = null
        }
        cancelFeedLoading()
    }

    fun suspendBrowsingDataLoads() {
        cancelInboxLoading()
        cancelFeedLoading()
    }

    fun loadEpisodes(feedUrl: String) {
        val now = System.currentTimeMillis()
        val cached = feedCache[feedUrl]
        val cachedEpisodes = cached?.episodes.orEmpty().take(MAX_TOTAL_INBOX_ITEMS)
        val hasCachedEpisodes = cachedEpisodes.isNotEmpty()
        val isCachedFeedFresh = cached != null && now - cached.timestampMs <= FEED_CACHE_TTL_MS

        if (isCachedFeedFresh) {
            _episodes.value = cachedEpisodes
            if (currentFeedUrl == feedUrl) {
                return
            }
        } else if (currentFeedUrl != feedUrl) {
            _episodes.value = if (hasCachedEpisodes) cachedEpisodes else emptyList()
        }

        if (feedLoadJob?.isActive == true && currentFeedUrl == feedUrl) {
            return
        }

        currentFeedUrl = feedUrl
        feedLoadJob?.cancel()
        isLoadingFeed.value = true
        lastFeedBatchPublishTimeMs = 0L

        feedLoadJob = viewModelScope.launch {
            val baselineEpisodes = cachedEpisodes
            try {
                val loadedEpisodes = withContext(Dispatchers.IO) {
                    val loadContext = currentCoroutineContext()
                    try {
                        val parsedEpisodes = LinkedHashMap<String, Episode>().apply {
                            mergeEpisodes(this, baselineEpisodes)
                        }
                        val result = withUrlInputStream(feedUrl, RequestGroup.FEED) { inputStream ->
                            RssParser().parse(
                                inputStream,
                                maxItems = MAX_TOTAL_INBOX_ITEMS,
                                onBatchParsed = { batch ->
                                    loadContext.ensureActive()
                                    synchronized(parsedEpisodes) {
                                        mergeEpisodes(parsedEpisodes, batch)
                                        if (shouldPublishFeedBatch()) {
                                            _episodes.value = sortEpisodesByDate(parsedEpisodes.values)
                                                .take(MAX_TOTAL_INBOX_ITEMS)
                                        }
                                    }
                                }
                            )
                        }

                        loadContext.ensureActive()
                        val merged = LinkedHashMap<String, Episode>().apply {
                            mergeEpisodes(this, parsedEpisodes.values.toList())
                            mergeEpisodes(this, result)
                        }
                        sortEpisodesByDate(merged.values).take(MAX_TOTAL_INBOX_ITEMS)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        baselineEpisodes
                    }
                }

                val resolvedEpisodes = if (loadedEpisodes.isNotEmpty()) loadedEpisodes else baselineEpisodes
                _episodes.value = resolvedEpisodes
                if (loadedEpisodes.isNotEmpty()) {
                    feedCache[feedUrl] = FeedCacheEntry(loadedEpisodes, System.currentTimeMillis())
                }
            } finally {
                isLoadingFeed.value = false
            }
        }
    }

    fun loadInboxEpisodes(force: Boolean = false) {
        if (!force && _inboxEpisodes.value.isNotEmpty()) return
        if (inboxLoadJob?.isActive == true) {
            if (!force) return
            cancelInboxLoading()
        }

        // Set refreshing state immediately
        isRefreshingInbox.value = true
        lastInboxBatchPublishTimeMs = 0L

        inboxLoadJob = viewModelScope.launch {
            val currentPodcasts = _podcasts.value
            val baselineEpisodes = _inboxEpisodes.value
            try {
                val freshEpisodes = LinkedHashMap<String, Episode>().apply {
                    mergeEpisodes(this, baselineEpisodes)
                }
                val mergeLock = Any()
                val allEpisodes = withContext(Dispatchers.IO) {
                    val loadContext = currentCoroutineContext()
                    val rssParser = RssParser()
                    val limiter = Semaphore(MAX_CONCURRENT_INBOX_FETCH)

                    coroutineScope {
                        val fetchDeferreds = currentPodcasts.map { podcast ->
                            async {
                                limiter.withPermit {
                                    loadContext.ensureActive()
                                    try {
                                        withUrlInputStream(podcast.feedUrl, RequestGroup.INBOX) { inputStream ->
                                            rssParser.parse(
                                                inputStream,
                                                maxItems = MAX_INBOX_ITEMS_PER_FEED,
                                                onBatchParsed = { batch ->
                                                    loadContext.ensureActive()
                                                    synchronized(mergeLock) {
                                                        mergeEpisodes(freshEpisodes, batch)
                                                        if (shouldPublishInboxBatch()) {
                                                            val snapshot = sortEpisodesByDate(freshEpisodes.values)
                                                                .take(MAX_TOTAL_INBOX_ITEMS)
                                                            _inboxEpisodes.value = snapshot
                                                            publishVisibleInboxEpisodes()
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.w("WearPod", "Failed to fetch feed: ${podcast.feedUrl}", e)
                                        emptyList<Episode>()
                                    }
                                }
                            }
                        }

                        loadContext.ensureActive()
                        val fetchedLists = fetchDeferreds.awaitAll()
                        synchronized(mergeLock) {
                            fetchedLists.forEach { mergeEpisodes(freshEpisodes, it) }
                            sortEpisodesByDate(freshEpisodes.values)
                                .take(MAX_TOTAL_INBOX_ITEMS)
                        }
                    }
                }

                _inboxEpisodes.value = allEpisodes
                if (allEpisodes.isNotEmpty()) {
                    saveInboxEpisodesState(allEpisodes)
                }
                publishVisibleInboxEpisodes()
                debugLog("Finished loading inbox with limit=${MAX_CONCURRENT_INBOX_FETCH}. Total: ${_inboxEpisodes.value.size}")
            } finally {
                isRefreshingInbox.value = false
            }
        }
    }

    private fun shouldRefreshInbox(ownerId: String? = _customOpmlId.value): Boolean {
        if (_inboxEpisodes.value.isEmpty()) {
            return true
        }
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_INBOX_CACHE, Context.MODE_PRIVATE)
        val cachedAt = prefs.getLong(inboxCacheTimestampKey(ownerId), 0L)
        if (cachedAt <= 0L) {
            return true
        }
        return (System.currentTimeMillis() - cachedAt) >= INBOX_REFRESH_TTL_MS
    }

    private fun cancelInboxLoading() {
        inboxLoadJob?.cancel()
        inboxLoadJob = null
        cancelActiveConnections(RequestGroup.INBOX)
        isRefreshingInbox.value = false
    }

    private fun cancelFeedLoading() {
        feedLoadJob?.cancel()
        feedLoadJob = null
        cancelActiveConnections(RequestGroup.FEED)
        isLoadingFeed.value = false
    }

    fun loadMoreInboxEpisodes() {
        if (!hasMoreInboxEpisodes.value) return
        inboxVisibleLimit = (inboxVisibleLimit + INBOX_PAGE_SIZE).coerceAtMost(_inboxEpisodes.value.size)
        publishVisibleInboxEpisodes()
    }

    private fun resetVisibleInboxEpisodes() {
        inboxVisibleLimit = INITIAL_VISIBLE_INBOX_ITEMS
        publishVisibleInboxEpisodes()
    }

    private fun publishVisibleInboxEpisodes() {
        if (inboxVisibleLimit == 0) {
            inboxVisibleLimit = INITIAL_VISIBLE_INBOX_ITEMS
        }
        val visibleEpisodes = _inboxEpisodes.value.take(inboxVisibleLimit)
        _visibleInboxEpisodes.value = visibleEpisodes
        _visibleInboxEpisodeGroups.value = groupEpisodesByPubDate(visibleEpisodes)
        hasMoreInboxEpisodes.value = _inboxEpisodes.value.size > visibleEpisodes.size
    }

    private fun groupEpisodesByPubDate(episodes: List<Episode>): List<InboxEpisodeGroup> {
        if (episodes.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<Episode>>()
        episodes.forEach { episode ->
            val key = episode.pubDate
            grouped.getOrPut(key) { mutableListOf() }.add(episode)
        }

        return grouped.map { (pubDate, groupedEpisodes) ->
            InboxEpisodeGroup(pubDate = pubDate, episodes = groupedEpisodes)
        }
    }

    private fun sortEpisodesByDate(episodes: Collection<Episode>): List<Episode> {
        return episodes.sortedByDescending { episodeTimestamp(it.pubDate) }
    }

    private fun shouldPublishInboxBatch(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (_inboxEpisodes.value.isEmpty() || now - lastInboxBatchPublishTimeMs >= BATCH_UI_PUBLISH_INTERVAL_MS) {
            lastInboxBatchPublishTimeMs = now
            return true
        }
        return false
    }

    private fun shouldPublishFeedBatch(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (_episodes.value.isEmpty() || now - lastFeedBatchPublishTimeMs >= BATCH_UI_PUBLISH_INTERVAL_MS) {
            lastFeedBatchPublishTimeMs = now
            return true
        }
        return false
    }

    private fun episodeTimestamp(pubDate: String): Long {
        if (pubDate.isBlank()) return 0L
        return episodeTimestampCache.computeIfAbsent(pubDate) {
            try {
                LocalDate.parse(it, PUB_DATE_FORMATTER)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    private fun mergeEpisodes(target: MutableMap<String, Episode>, incoming: List<Episode>) {
        incoming.forEach { episode ->
            target[episodeKey(episode)] = episode
        }
    }

    private fun episodeKey(episode: Episode): String {
        return episode.audioUrl.ifEmpty {
            "${episode.podcastTitle}|${episode.title}|${episode.pubDate}"
        }
    }

    private fun saveInboxEpisodesState(episodes: List<Episode>, ownerId: String? = _customOpmlId.value) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_INBOX_CACHE, Context.MODE_PRIVATE)
        prefs.edit {
            putString(inboxCacheKey(ownerId), serializeEpisodes(episodes))
            putLong(inboxCacheTimestampKey(ownerId), System.currentTimeMillis())
        }
    }

    private fun loadCachedInboxEpisodesState(ownerId: String? = _customOpmlId.value, clearIfMissing: Boolean = false): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_INBOX_CACHE, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(inboxCacheKey(ownerId), null)

        if (jsonStr.isNullOrEmpty()) {
            if (clearIfMissing) {
                _inboxEpisodes.value = emptyList()
                resetVisibleInboxEpisodes()
            }
            return false
        }

        return try {
            _inboxEpisodes.value = deserializeEpisodes(jsonStr)
            resetVisibleInboxEpisodes()
            true
        } catch (e: Exception) {
            Log.w("WearPod", "Failed to load cached inbox for owner=$ownerId", e)
            if (clearIfMissing) {
                _inboxEpisodes.value = emptyList()
                resetVisibleInboxEpisodes()
            }
            false
        }
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
        return Episode(
            title = obj.getString("title"),
            description = obj.optString("description"),
            pubDate = obj.optString("pubDate"),
            audioUrl = obj.getString("audioUrl"),
            imageUrl = obj.optString("imageUrl"),
            podcastTitle = obj.optString("podcastTitle"),
            podcastImageUrl = obj.optString("podcastImageUrl"),
            duration = obj.optString("duration"),
        )
    }

    private fun persistLastPlaybackState(positionMs: Long = currentPosition.value) {
        val episode = currentPlayingEpisode.value ?: lastPlaybackState?.episode ?: return
        val safePosition = positionMs.coerceAtLeast(0L)
        lastPlaybackState = LastPlaybackState(episode, safePosition)

        val prefs = getApplication<Application>().getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_LAST_EPISODE, serializeEpisode(episode).toString())
            putLong(KEY_LAST_POSITION, safePosition)
        }
    }

    private fun maybePersistPlaybackState() {
        val now = System.currentTimeMillis()
        if (now - lastPlaybackPersistTimeMs >= PLAYBACK_PERSIST_INTERVAL_MS) {
            persistLastPlaybackState()
            lastPlaybackPersistTimeMs = now
        }
    }

    private fun loadLastPlaybackState() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
        val episodeJson = prefs.getString(KEY_LAST_EPISODE, null) ?: return

        try {
            val episode = deserializeEpisode(JSONObject(episodeJson))
            val position = prefs.getLong(KEY_LAST_POSITION, 0L).coerceAtLeast(0L)
            lastPlaybackState = LastPlaybackState(episode, position)
            currentPlayingEpisode.value = episode
            currentPlayingUrl = episode.audioUrl
            currentPosition.value = position
        } catch (e: Exception) {
            Log.w("WearPod", "Failed to parse last playback state", e)
        }
    }

    private fun restoreLastPlaybackToController(controller: MediaController) {
        val state = lastPlaybackState ?: return
        val currentMediaKey = currentMediaKey(controller.currentMediaItem)

        if (currentMediaKey == null) {
            controller.setMediaItem(buildMediaItem(state.episode))
            controller.prepare()
        }

        if (state.positionMs > 0) {
            controller.seekTo(state.positionMs)
            currentPosition.value = state.positionMs
        }
    }

    private fun resolvePlayableUri(episode: Episode): String {
        val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
        val localFile = java.io.File(getApplication<Application>().filesDir, filename)
        return if (localFile.exists()) {
            Uri.fromFile(localFile).toString()
        } else {
            episode.audioUrl
        }
    }

    private fun buildMediaItem(episode: Episode): MediaItem {
        return MediaItem.Builder()
            .setMediaId(episode.audioUrl)
            .setUri(resolvePlayableUri(episode))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle)
                    .setArtworkUri(
                        episode.imageUrl.ifBlank { episode.podcastImageUrl }
                            .takeIf { it.isNotBlank() }
                            ?.let(Uri::parse)
                    )
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun currentMediaKey(mediaItem: MediaItem?): String? {
        return mediaItem?.mediaId?.takeIf { it.isNotBlank() }
            ?: mediaItem?.localConfiguration?.uri?.toString()
    }

    private fun findEpisodeForMediaItem(mediaItem: MediaItem?): Episode? {
        val mediaKey = currentMediaKey(mediaItem) ?: return null
        return resolveEpisodeByAudioUrl(mediaKey)
    }

    fun resolveEpisodeByAudioUrl(audioUrl: String): Episode? {
        if (audioUrl.isBlank()) return null
        return _episodes.value.find { it.audioUrl == audioUrl }
            ?: _inboxEpisodes.value.find { it.audioUrl == audioUrl }
            ?: _playlist.value.find { it.audioUrl == audioUrl }
            ?: _downloadedEpisodes.value.find { it.audioUrl == audioUrl }
            ?: currentPlayingEpisode.value?.takeIf { it.audioUrl == audioUrl }
            ?: lastPlaybackState?.episode?.takeIf { it.audioUrl == audioUrl }
    }

    private fun registerConnection(group: RequestGroup, connection: HttpURLConnection) {
        synchronized(activeConnections) {
            activeConnections.getOrPut(group) { mutableSetOf() }.add(connection)
        }
    }

    private fun unregisterConnection(group: RequestGroup, connection: HttpURLConnection) {
        synchronized(activeConnections) {
            activeConnections[group]?.remove(connection)
            if (activeConnections[group].isNullOrEmpty()) {
                activeConnections.remove(group)
            }
        }
    }

    private fun cancelActiveConnections(group: RequestGroup) {
        val connections = synchronized(activeConnections) {
            activeConnections[group]?.toList().orEmpty()
        }
        connections.forEach { connection ->
            runCatching { connection.disconnect() }
        }
    }

    private inline fun <T> withUrlInputStream(
        url: String,
        group: RequestGroup = RequestGroup.GENERAL,
        block: (java.io.InputStream) -> T,
    ): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        registerConnection(group, connection)

        return try {
            connection.inputStream.buffered().use(block)
        } finally {
            unregisterConnection(group, connection)
            connection.disconnect()
        }
    }

    @UnstableApi
    private fun initializeController() {
        val sessionToken = SessionToken(
            getApplication<Application>().applicationContext,
            ComponentName(getApplication<Application>().applicationContext, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication<Application>().applicationContext, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            mediaController.value = controller
            controller?.let {
                restoreLastPlaybackToController(it)
                pendingSeekPositionMs?.let { seekMs ->
                    it.seekTo(seekMs)
                    pendingSeekPositionMs = null
                }
                pendingEpisodeToPlay?.let { episode ->
                    pendingEpisodeToPlay = null
                    playEpisode(episode)
                }
            }
            
            // Re-hydrate state upon connection from background
            if (controller != null) {
                 isPlaying.value = controller.isPlaying
                 // Ensure progress tracking picks up immediately if it was already playing in the background
                 if (controller.isPlaying) {
                     startProgressTracking()
                 }
                 
                 val matchingEpisode = findEpisodeForMediaItem(controller.currentMediaItem)
                     ?: lastPlaybackState?.episode
                 if (matchingEpisode != null) {
                     currentPlayingEpisode.value = matchingEpisode
                     currentPlayingUrl = matchingEpisode.audioUrl
                 }
                 currentPosition.value = controller.currentPosition.coerceAtLeast(0L)
                 currentDuration.value = controller.duration.coerceAtLeast(0L)
            }

            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    debugLog("onIsPlayingChanged: $playing")
                    isPlaying.value = playing
                    if (playing) {
                        startProgressTracking()
                    } else {
                        stopProgressTracking()
                        persistLastPlaybackState()
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    debugLog("onPlaybackStateChanged: $playbackState")
                    isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                    
                    if (playbackState == Player.STATE_READY) {
                        val readyDuration = controller.duration.coerceAtLeast(0L)
                        if (readyDuration > 0L) {
                            currentDuration.value = readyDuration
                        }
                        currentPosition.value = controller.currentPosition
                        persistLastPlaybackState()
                    }
                    
                    if (playbackState == Player.STATE_ENDED) {
                        if (pauseOnEpisodeEnd) {
                            currentSleepTimerMode.value = SleepTimerMode.Off
                            currentSleepTimerRemainingMs.value = null
                            pauseOnEpisodeEnd = false
                            isPlaying.value = false
                        } else {
                            playNextInQueue()
                        }
                        persistLastPlaybackState()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("WearPod", "ExoPlayer Error: ${error.message}", error)
                    isPlaying.value = false
                    isBuffering.value = false
                    stopProgressTracking()
                    persistLastPlaybackState()
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun onPlayerScreenEntered() {
        isPlayerScreenVisible = true
        val controller = mediaController.value ?: return
        currentPosition.value = controller.currentPosition.coerceAtLeast(0L)
        currentDuration.value = currentDuration.value.coerceAtLeast(
            controller.duration.coerceAtLeast(0L)
        )
        // Always track while player page is visible so first-open duration/progress can hydrate.
        startProgressTracking()
    }

    fun onPlayerScreenExited() {
        isPlayerScreenVisible = false
        if (mediaController.value?.isPlaying != true) {
            stopProgressTracking()
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        if (isAppInForeground == inForeground) {
            return
        }
        isAppInForeground = inForeground

        if (shouldTrackProgress(mediaController.value)) {
            startProgressTracking()
        } else {
            stopProgressTracking()
        }
    }

    private fun shouldTrackProgress(controller: MediaController?): Boolean {
        val playing = controller?.isPlaying == true
        val needsInteractiveProgress = isPlayerScreenVisible && isAppInForeground
        return playing || needsInteractiveProgress
    }

    private fun progressPollIntervalMs(controller: MediaController?): Long {
        return when {
            controller?.isPlaying == true && isPlayerScreenVisible && isAppInForeground -> 120L
            controller?.isPlaying == true && isAppInForeground -> 800L
            controller?.isPlaying == true -> 2500L
            isPlayerScreenVisible && isAppInForeground -> 1200L
            else -> 2500L
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        if (!shouldTrackProgress(mediaController.value)) {
            return
        }
        progressJob = viewModelScope.launch {
            while (isActive) {
                val controller = mediaController.value
                if (!shouldTrackProgress(controller)) {
                    break
                }
                controller?.let {
                    if (!isSeeking) {
                        currentPosition.value = it.currentPosition
                    }
                    val liveDuration = it.duration.coerceAtLeast(0L)
                    if (liveDuration > 0L) {
                        currentDuration.value = liveDuration
                    }
                    maybePersistPlaybackState()
                }
                delay(progressPollIntervalMs(controller))
            }
            progressJob = null
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    private var currentPlayingUrl: String? = null

    fun playEpisode(episode: Episode) {
        val controller = mediaController.value
        if (controller == null) {
            pendingEpisodeToPlay = episode
            postUiMessage(R.string.message_preparing_player)
            Log.w("WearPod", "MediaController pending. Queueing play request.")
            return
        }
        
        // If it's the same episode, just play it
        if (currentPlayingUrl == episode.audioUrl) {
            controller.play()
            return
        }

        // New episode
        currentPlayingUrl = episode.audioUrl
        currentPlayingEpisode.value = episode
        currentPosition.value = 0L
        currentDuration.value = parseDurationToMs(episode.duration)
        lastPlaybackState = LastPlaybackState(episode, 0L)
        persistLastPlaybackState(0L)
        
        val mediaItem = buildMediaItem(episode)
        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
        
        // Optimistically set buffering if we are switching tracks
        isBuffering.value = true
        isPlaying.value = true
    }

    fun togglePlayPause() {
        val controller = mediaController.value ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    private var isSeeking = false

    fun seekTo(positionMs: Long) {
        val controller = mediaController.value
        if (controller == null) {
            pendingSeekPositionMs = positionMs
            currentPosition.value = positionMs
            return
        }
        isSeeking = true
        progressJob?.cancel()
        controller.seekTo(positionMs)
        currentPosition.value = positionMs
        persistLastPlaybackState(positionMs)
        
        // Restart tracking after a brief delay to let ExoPlayer settle
        viewModelScope.launch {
            delay(SEEK_SETTLE_DELAY_MS)
            isSeeking = false
            startProgressTracking()
        }
    }

    fun skipForward() {
        val controller = mediaController.value ?: return
        isSeeking = true
        progressJob?.cancel()
        val durationCap = controller.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val newPos = (controller.currentPosition + 15000L)
            .coerceAtMost(durationCap)
            .coerceAtLeast(0L)
        controller.seekTo(newPos)
        currentPosition.value = newPos
        persistLastPlaybackState(newPos)

        viewModelScope.launch {
            delay(SEEK_SETTLE_DELAY_MS)
            isSeeking = false
            startProgressTracking()
        }
    }

    fun skipBackward() {
        val controller = mediaController.value ?: return
        isSeeking = true
        progressJob?.cancel()
        val newPos = (controller.currentPosition - 15000L).coerceAtLeast(0L)
        controller.seekTo(newPos)
        currentPosition.value = newPos
        persistLastPlaybackState(newPos)

        viewModelScope.launch {
            delay(SEEK_SETTLE_DELAY_MS)
            isSeeking = false
            startProgressTracking()
        }
    }

    fun openVolumeControl() {
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Sending ADJUST_SAME with FLAG_SHOW_UI forces the WearOS native volume slider to appear
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun addToPlaylist(episode: Episode) {
        val current = _playlist.value.toMutableList()
        if (!current.contains(episode)) {
            current.add(episode)
            _playlist.value = current
            postUiMessage(R.string.message_added_to_playlist)
        } else {
            postUiMessage(R.string.message_already_in_playlist)
        }
    }

    fun removeFromPlaylist(episode: Episode) {
        val current = _playlist.value.toMutableList()
        if (current.remove(episode)) {
            _playlist.value = current
        }
    }

    private fun playNextInQueue() {
        val currentQueue = _playlist.value.toMutableList()
        if (currentQueue.isNotEmpty()) {
            val nextEpisode = currentQueue.removeAt(0)
            _playlist.value = currentQueue
            playEpisode(nextEpisode)
        } else {
            // Queue is empty, reset state
            isPlaying.value = false
            currentPlayingEpisode.value = null
            currentPlayingUrl = null
        }
    }

    private fun saveDownloadedEpisodesState(episodes: List<Episode>) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_DOWNLOADS, Context.MODE_PRIVATE)
        prefs.edit {
            putString("downloaded_list", serializeEpisodes(episodes))
        }
    }

    private fun loadDownloadedEpisodesState() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_DOWNLOADS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("downloaded_list", null)
        if (jsonStr != null) {
            try {
                val parsed = deserializeEpisodes(jsonStr)
                val existingFilesOnly = parsed.filter { downloadedFileForEpisode(it).exists() }
                _downloadedEpisodes.value = existingFilesOnly
                if (existingFilesOnly.size != parsed.size) {
                    saveDownloadedEpisodesState(existingFilesOnly)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun downloadEpisode(episode: Episode) {
        if (_downloadedEpisodes.value.any { it.audioUrl == episode.audioUrl }) {
            postUiMessage(R.string.message_already_downloaded)
            return
        }
        if (_downloadingEpisodes.value.any { it.audioUrl == episode.audioUrl }) {
            postUiMessage(R.string.message_already_downloading)
            return
        }

        _downloadingEpisodes.value = _downloadingEpisodes.value + episode
        updateDownloadProgress(episode.audioUrl, 0f)
        postUiMessage(R.string.message_downloading)
        
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
                val file = java.io.File(getApplication<Application>().filesDir, filename)
                try {
                    if (!file.exists()) {
                        val connection = (URL(episode.audioUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = CONNECT_TIMEOUT_MS
                            readTimeout = READ_TIMEOUT_MS
                            requestMethod = "GET"
                            instanceFollowRedirects = true
                        }

                        val totalBytes = connection.contentLengthLong
                        var downloadedBytes = 0L

                        connection.inputStream.use { input ->
                            file.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var read = input.read(buffer)
                                while (read >= 0) {
                                    if (read > 0) {
                                        output.write(buffer, 0, read)
                                        downloadedBytes += read
                                        if (totalBytes > 0) {
                                            updateDownloadProgress(
                                                episode.audioUrl,
                                                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                    read = input.read(buffer)
                                }
                            }
                        }
                        connection.disconnect()
                    }

                    if (!file.exists() || file.length() <= 0L) {
                        throw IllegalStateException("Downloaded file is missing or empty")
                    }

                    updateDownloadProgress(episode.audioUrl, 1f)
                    val updated = _downloadedEpisodes.value + episode
                    _downloadedEpisodes.value = updated
                    saveDownloadedEpisodesState(updated)
                    postUiMessage(R.string.message_downloaded)
                } catch (e: Exception) {
                    if (file.exists()) {
                        file.delete()
                    }
                    Log.e("WearPod", "Download failed for ${episode.audioUrl}", e)
                    postUiMessage(R.string.message_download_failed)
                } finally {
                    _downloadingEpisodes.value = _downloadingEpisodes.value.filter { it.audioUrl != episode.audioUrl }
                    removeDownloadProgress(episode.audioUrl)
                }
            }
        }
    }

    fun deleteDownloadedEpisode(episode: Episode) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
                val file = java.io.File(getApplication<Application>().filesDir, filename)
                var deleted = true
                if (file.exists()) {
                    deleted = file.delete()
                }
                val updated = _downloadedEpisodes.value.filter { it.audioUrl != episode.audioUrl }
                _downloadedEpisodes.value = updated
                saveDownloadedEpisodesState(updated)
                postUiMessage(if (deleted) R.string.message_deleted else R.string.message_delete_failed)
            }
        }
    }

    private fun postUiMessage(@StringRes resId: Int) {
        _uiMessages.tryEmit(getApplication<Application>().getString(resId))
    }

    private fun updateDownloadProgress(audioUrl: String, progress: Float) {
        val normalized = progress.coerceIn(0f, 1f)
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            put(audioUrl, normalized)
        }
    }

    private fun removeDownloadProgress(audioUrl: String) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            remove(audioUrl)
        }
    }

    private fun downloadedFileForEpisode(episode: Episode): java.io.File {
        val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
        return java.io.File(getApplication<Application>().filesDir, filename)
    }

    private fun parseDurationToMs(rawDuration: String): Long {
        val parts = rawDuration.trim().split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) {
            return 0L
        }

        val totalSeconds = when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            1 -> parts[0]
            else -> return 0L
        }
        return (totalSeconds * 1000L).coerceAtLeast(0L)
    }

    fun setSleepTimer(mode: SleepTimerMode) {
        currentSleepTimerMode.value = mode
        sleepTimerJob?.cancel()
        currentSleepTimerRemainingMs.value = null
        pauseOnEpisodeEnd = false
        
        if (mode.minutes > 0) {
            val endAtMs = System.currentTimeMillis() + mode.minutes * 60_000L
            sleepTimerJob = viewModelScope.launch {
                while (true) {
                    val remaining = (endAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    currentSleepTimerRemainingMs.value = remaining
                    if (remaining <= 0L) {
                        break
                    }
                    delay(1_000L)
                }
                val controller = mediaController.value
                if (controller?.isPlaying == true) {
                     controller.pause()
                }
                currentSleepTimerMode.value = SleepTimerMode.Off
                currentSleepTimerRemainingMs.value = null
            }
        } else if (mode == SleepTimerMode.EndOfEpisode) {
            pauseOnEpisodeEnd = true
        }
    }

    override fun onCleared() {
        persistLastPlaybackState()
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
