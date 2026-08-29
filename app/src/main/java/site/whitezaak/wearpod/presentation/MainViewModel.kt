package site.whitezaak.wearpod.presentation

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.util.ConnectivityObserver
import site.whitezaak.wearpod.util.EpisodeJson
import site.whitezaak.wearpod.util.DownloadFileManager
import site.whitezaak.wearpod.util.DurationUtils
import site.whitezaak.wearpod.util.NetworkConfig
import site.whitezaak.wearpod.domain.Episode
import site.whitezaak.wearpod.domain.Podcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import java.util.Locale
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.util.UnstableApi
import site.whitezaak.wearpod.settings.AppLanguageManager
import site.whitezaak.wearpod.settings.OpmlLinks
import android.media.AudioManager
import android.content.Context
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import site.whitezaak.wearpod.data.FeedRepository
import site.whitezaak.wearpod.service.PlaybackController
import android.icu.text.Transliterator

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val feedRepository = FeedRepository(application)
    val playbackController = PlaybackController(application)
    private data class FeedCacheEntry(
        val episodes: List<Episode>,
        val timestampMs: Long,
    )

    private data class LastPlaybackState(
        val episode: Episode,
        val positionMs: Long,
    )

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

    private val _isSubscriptionsLoading = MutableStateFlow(false)
    val isSubscriptionsLoading: StateFlow<Boolean> = _isSubscriptionsLoading.asStateFlow()

    private val _sortedLibraryPodcasts = MutableStateFlow<List<Pair<Int, Podcast>>>(emptyList())
    val sortedLibraryPodcasts: StateFlow<List<Pair<Int, Podcast>>> = _sortedLibraryPodcasts.asStateFlow()

    private val hanToLatinTransliterator: Transliterator by lazy {
        Transliterator.getInstance("Han-Latin; Latin-Ascii")
    }

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

    val isPlaying = playbackController.isPlaying
    val isBuffering = playbackController.isBuffering
    val currentPlayingEpisode = playbackController.currentPlayingEpisode

    private val _playlist = MutableStateFlow<List<Episode>>(emptyList())
    val playlist: StateFlow<List<Episode>> = _playlist.asStateFlow()

    private val _recentlyPlayedEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val recentlyPlayedEpisodes: StateFlow<List<Episode>> = _recentlyPlayedEpisodes.asStateFlow()

    val isOnline: StateFlow<Boolean> = ConnectivityObserver.isOnline

    private val _downloadedEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val downloadedEpisodes: StateFlow<List<Episode>> = _downloadedEpisodes.asStateFlow()

    private val _isDownloadPlaylistMode = MutableStateFlow(false)
    val isDownloadPlaylistMode: StateFlow<Boolean> = _isDownloadPlaylistMode.asStateFlow()

    private val _downloadPlaylist = MutableStateFlow<List<Episode>>(emptyList())
    val downloadPlaylist: StateFlow<List<Episode>> = _downloadPlaylist.asStateFlow()

    private val _downloadRecentlyPlayed = MutableStateFlow<List<Episode>>(emptyList())
    val downloadRecentlyPlayed: StateFlow<List<Episode>> = _downloadRecentlyPlayed.asStateFlow()

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
    private var isSleepTimerScreenVisible = false
    private var subscriptionsLoaded = false
    private var inboxLoadJob: Job? = null
    private var feedLoadJob: Job? = null
    private var inboxVisibleLimit = 0
    private var currentFeedUrl: String? = null
    private var isInboxScreenVisible = false
    private var visibleFeedUrl: String? = null
    private val feedCache = mutableMapOf<String, FeedCacheEntry>()
    private var lastPlaybackState: LastPlaybackState? = null
    private var lastPlaybackPersistTimeMs = 0L
    private var pendingEpisodeToPlay: Episode? = null
    private var pendingSeekPositionMs: Long? = null
    private var lastInboxBatchPublishTimeMs = 0L
    private var lastFeedBatchPublishTimeMs = 0L
    private val episodeTimestampCache = ConcurrentHashMap<String, Long>()
    private val lastDownloadProgressPublishTimeMs = ConcurrentHashMap<String, Long>()
    private val lastDownloadProgressValue = ConcurrentHashMap<String, Float>()
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val activeDownloadConnections = ConcurrentHashMap<String, HttpURLConnection>()
    private val downloadSemaphore = Semaphore(2)
    private val cancellingUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var cachedSerializedEpisodeJson: String? = null
    private var cachedSerializedEpisodeUrl: String? = null

    val isLoadingFeed = MutableStateFlow(false)

    private companion object {
        // WearOS device friendly limits: avoid bursting too many sockets/parsers at once.
        const val INITIAL_VISIBLE_INBOX_ITEMS = 100
        const val INBOX_PAGE_SIZE = 50
        const val PREFS_INBOX_CACHE = "wearpod_inbox_cache"
        const val PREFS_DOWNLOADS = "wearpod_downloads"
        const val PREFS_PLAYLIST = "wearpod_playlist"
        const val PREFS_PLAYBACK = "wearpod_playback"
        const val PREFS_SETTINGS = "wearpod_settings"
        const val KEY_LAST_EPISODE = "last_episode"
        const val KEY_LAST_POSITION = "last_position_ms"
        const val KEY_DELETE_ON_COMPLETE = "delete_on_complete"
        const val KEY_INBOX_CACHE_TIMESTAMP_SUFFIX = "_timestamp"
        const val FEED_CACHE_TTL_MS = 2 * 60 * 1000L
        const val INBOX_REFRESH_TTL_MS = 30 * 60 * 1000L
        const val BATCH_UI_PUBLISH_INTERVAL_MS = 250L
        const val DOWNLOAD_PROGRESS_PUBLISH_INTERVAL_MS = 140L
        const val DOWNLOAD_PROGRESS_MIN_DELTA = 0.02f
        const val PLAYBACK_PERSIST_INTERVAL_MS = 1_500L
        val PUB_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    }
    
    val currentPosition = playbackController.currentPosition
    val currentDuration = playbackController.currentDuration

    init {
        val prefs = getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
        val initialCustomId = prefs.getString("custom_opml_id", null)
        _customOpmlId.value = initialCustomId
        _appLanguageTag.value = AppLanguageManager.getSelectedLanguageTag(application)

        viewModelScope.launch(Dispatchers.IO) {
            loadCachedInboxEpisodesState(initialCustomId)
            loadDownloadedEpisodesState()
            loadPlaylistState()
            loadLastPlaybackState()
        }
        initializeController()

        viewModelScope.launch {
            // 联网恢复时若订阅尚未成功加载（例如断网冷启动），自动重试。
            ConnectivityObserver.isOnline.collect { online ->
                if (online && !subscriptionsLoaded) {
                    loadSubscriptions()
                }
            }
        }

        viewModelScope.launch {
            // Delay heavy initialization to ensure UI layout passes are smooth
            delay(500)
            loadSubscriptions()
        }
    }

    private fun loadSubscriptions() {
        if (!ConnectivityObserver.isOnline.value) return
        // 已成功加载过则不再重复拉取（冷启动 collect 首发 + delay(500) 会连来两次）
        if (subscriptionsLoaded) return
        if (isSubscriptionsLoading.value) return
        _isSubscriptionsLoading.value = true
        viewModelScope.launch {
            val loadedPodcasts = feedRepository.loadSubscriptions(_customOpmlId.value)
            _isSubscriptionsLoading.value = false
            if (loadedPodcasts.isNotEmpty()) {
                subscriptionsLoaded = true
                _podcasts.value = loadedPodcasts
                updateSortedLibraryPodcasts(loadedPodcasts)
                if (isInboxScreenVisible) {
                    loadInboxEpisodes(force = true)
                }
            } else if (isRefreshingInbox.value) {
                isRefreshingInbox.value = false
            } else {
                postUiMessage(R.string.message_subscriptions_load_failed)
            }
        }
    }

    fun loadCustomOpml(id: String) {
        viewModelScope.launch {
            _customOpmlId.value = id
            loadCachedInboxEpisodesState(id, clearIfMissing = true)

            getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
                .edit { putString("custom_opml_id", id) }

            _isSubscriptionsLoading.value = true
            val loadedPodcasts = feedRepository.loadSubscriptions(id)
            _isSubscriptionsLoading.value = false
            
            if (loadedPodcasts.isNotEmpty()) {
                subscriptionsLoaded = true
                _podcasts.value = loadedPodcasts
                updateSortedLibraryPodcasts(loadedPodcasts)
                if (isInboxScreenVisible) {
                    loadInboxEpisodes(force = true)
                }
            } else if (isRefreshingInbox.value) {
                isRefreshingInbox.value = false
            } else {
                postUiMessage(R.string.message_subscriptions_load_failed)
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
        val cachedEpisodes = cached?.episodes.orEmpty().take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
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

        if (!ConnectivityObserver.isOnline.value) return

        currentFeedUrl = feedUrl
        feedLoadJob?.cancel()
        isLoadingFeed.value = true
        lastFeedBatchPublishTimeMs = 0L

        feedLoadJob = viewModelScope.launch {
            val baselineEpisodes = cachedEpisodes
            // No cached episodes → skip conditional request, otherwise 304 would leave us with nothing
            val useConditional = baselineEpisodes.isNotEmpty()
            try {
                val parsedEpisodes = LinkedHashMap<String, Episode>().apply {
                    mergeEpisodes(this, baselineEpisodes)
                }
                val fetchResult = withContext(Dispatchers.IO) {
                    feedRepository.fetchFeedEpisodes(
                        feedUrl, "FEED",
                        onBatchParsed = { batch ->
                            synchronized(parsedEpisodes) {
                                mergeEpisodes(parsedEpisodes, batch)
                                if (shouldPublishFeedBatch()) {
                                    _episodes.value = sortEpisodesByDate(parsedEpisodes.values)
                                        .take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
                                }
                            }
                        },
                        connectTimeoutMs = NetworkConfig.FEED_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = NetworkConfig.FEED_READ_TIMEOUT_MS,
                        useConditionalRequest = useConditional,
                    )
                }

                // null = 304 Not Modified or error, content unchanged
                if (fetchResult == null) {
                    if (baselineEpisodes.isNotEmpty()) {
                        _episodes.value = baselineEpisodes
                    }
                    return@launch
                }

                val loadedEpisodes = synchronized(parsedEpisodes) {
                    mergeEpisodes(parsedEpisodes, fetchResult)
                    sortEpisodesByDate(parsedEpisodes.values).take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
                }

                if (loadedEpisodes.isEmpty() && baselineEpisodes.isEmpty() && currentFeedUrl == feedUrl) {
                    debugLog("Feed load returned empty for $feedUrl, retrying once without cache")
                    val retryResult = withContext(Dispatchers.IO) {
                        feedRepository.fetchFeedEpisodes(
                            feedUrl, "FEED",
                            connectTimeoutMs = NetworkConfig.FEED_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = NetworkConfig.FEED_READ_TIMEOUT_MS,
                            useConditionalRequest = false,
                        )
                    }
                    if (retryResult != null && retryResult.isNotEmpty()) {
                        val sorted = sortEpisodesByDate(retryResult).take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
                        _episodes.value = sorted
                        feedCache[feedUrl] = FeedCacheEntry(sorted, System.currentTimeMillis())
                        return@launch
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

    fun loadInboxEpisodes(force: Boolean = false, notifyIfOffline: Boolean = false) {
        if (!ConnectivityObserver.isOnline.value) {
            if (notifyIfOffline) {
                postUiMessage(R.string.message_offline_refresh)
            }
            return
        }
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
                    val fetchedLists = feedRepository.fetchInboxEpisodesConcurrently(currentPodcasts, "INBOX") { batch ->
                        synchronized(mergeLock) {
                            mergeEpisodes(freshEpisodes, batch)
                            if (shouldPublishInboxBatch()) {
                                val snapshot = sortEpisodesByDate(freshEpisodes.values)
                                    .take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
                                _inboxEpisodes.value = snapshot
                                publishVisibleInboxEpisodes()
                            }
                        }
                    }

                    synchronized(mergeLock) {
                        fetchedLists.forEach { mergeEpisodes(freshEpisodes, it) }
                        sortEpisodesByDate(freshEpisodes.values)
                            .take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
                    }
                }.also { sorted ->
                    // 大字符串序列化 + SharedPreferences 写入放 IO 线程，避免主线程卡顿。
                    if (sorted.isNotEmpty()) {
                        withContext(Dispatchers.IO) { saveInboxEpisodesState(sorted) }
                    }
                }

                _inboxEpisodes.value = allEpisodes
                publishVisibleInboxEpisodes()
                debugLog("Finished loading inbox with limit=${FeedRepository.MAX_CONCURRENT_INBOX_FETCH}. Total: ${_inboxEpisodes.value.size}")
            } finally {
                isRefreshingInbox.value = false
            }
        }
    }

    private fun shouldRefreshInbox(ownerId: String? = _customOpmlId.value): Boolean {
        if (_inboxEpisodes.value.isEmpty()) {
            return true
        }
        val cachedAt = feedRepository.getInboxCacheTimestamp(ownerId)
        if (cachedAt <= 0L) {
            return true
        }
        return (System.currentTimeMillis() - cachedAt) >= INBOX_REFRESH_TTL_MS
    }

    private fun cancelInboxLoading() {
        inboxLoadJob?.cancel()
        inboxLoadJob = null
        feedRepository.cancelActiveConnections("INBOX")
        isRefreshingInbox.value = false
    }

    private fun cancelFeedLoading() {
        feedLoadJob?.cancel()
        feedLoadJob = null
        feedRepository.cancelActiveConnections("FEED")
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
        feedRepository.saveInboxEpisodesState(episodes, ownerId)
    }

    private fun loadCachedInboxEpisodesState(ownerId: String? = _customOpmlId.value, clearIfMissing: Boolean = false): Boolean {
        val cached = feedRepository.loadCachedInboxEpisodesState(ownerId)
        if (cached == null) {
            if (clearIfMissing) {
                _inboxEpisodes.value = emptyList()
                resetVisibleInboxEpisodes()
            }
            return false
        }
        _inboxEpisodes.value = cached
        resetVisibleInboxEpisodes()
        return true
    }

    private fun persistLastPlaybackState(positionMs: Long = currentPosition.value) {
        val episode = currentPlayingEpisode.value ?: lastPlaybackState?.episode ?: return
        val safePosition = positionMs.coerceAtLeast(0L)
        lastPlaybackState = LastPlaybackState(episode, safePosition)

        val epJson = if (episode.audioUrl == cachedSerializedEpisodeUrl && cachedSerializedEpisodeJson != null) {
            cachedSerializedEpisodeJson!!
        } else {
            EpisodeJson.serializeEpisode(episode).toString().also {
                cachedSerializedEpisodeJson = it
                cachedSerializedEpisodeUrl = episode.audioUrl
            }
        }

        val prefs = getApplication<Application>().getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_LAST_EPISODE, epJson)
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
            val episode = EpisodeJson.deserializeEpisode(org.json.JSONObject(episodeJson))
            val position = prefs.getLong(KEY_LAST_POSITION, 0L).coerceAtLeast(0L)
            lastPlaybackState = LastPlaybackState(episode, position)
            playbackController.hydrateState(episode, position)
            currentPlayingUrl = episode.audioUrl
        } catch (e: Exception) {
            Log.w("WearPod", "Failed to parse last playback state", e)
        }
    }

    private fun restoreLastPlaybackToController() {
        val state = lastPlaybackState ?: return
        val currentDuration = playbackController.getControllerDuration()

        if (currentDuration <= 0L) {
            playbackController.setMediaItem(state.episode, resolvePlayableUri(state.episode), state.positionMs)
        } else if (state.positionMs > 0) {
            playbackController.seekTo(state.positionMs)
        }
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

    @UnstableApi
    private fun initializeController() {
        playbackController.onPeriodicPositionUpdate = { _ ->
            maybePersistPlaybackState()
        }

        playbackController.onPlayerConnected = {
            restoreLastPlaybackToController()
            pendingSeekPositionMs?.let { seekMs ->
                playbackController.seekTo(seekMs)
                pendingSeekPositionMs = null
            }
            pendingEpisodeToPlay?.let { episode ->
                pendingEpisodeToPlay = null
                playEpisode(episode)
            }

            syncPlayerScreenPlaybackState()
        }
        
        playbackController.onPlaybackEnded = {
            val completedUrl = currentPlayingUrl

            if (pauseOnEpisodeEnd) {
                currentSleepTimerMode.value = SleepTimerMode.Off
                currentSleepTimerRemainingMs.value = null
                pauseOnEpisodeEnd = false
                playbackController.clearMediaItem()
                currentPlayingUrl = null
                persistLastPlaybackState()
            } else {
                val isDlMode = _isDownloadPlaylistMode.value
                if (isDlMode) {
                    // Download mode: remove completed from playlist, track recently played
                    if (completedUrl != null) {
                        val currentList = _downloadPlaylist.value.toMutableList()
                        if (currentList.removeAll { it.audioUrl == completedUrl }) {
                            _downloadPlaylist.value = currentList
                        }
                        resolveEpisodeByAudioUrl(completedUrl)?.let { ep ->
                            val recent = _downloadRecentlyPlayed.value.toMutableList()
                            recent.removeAll { it.audioUrl == completedUrl }
                            recent.add(ep)
                            if (recent.size > 2) recent.removeAt(0)
                            _downloadRecentlyPlayed.value = recent
                        }
                    }
                    val next = _downloadPlaylist.value.firstOrNull()
                    currentPlayingUrl = null
                    if (next != null) {
                        playEpisode(next, keepDownloadMode = true)
                    } else {
                        // 队列播完：暂停并保留在离线队列视图（不跳回在线队列）。
                        // 模式保持开启，由下次从其它入口开始播放时按既有约定重置。
                        playbackController.clearMediaItem()
                        persistLastPlaybackState()
                    }
                } else {
                    // Normal mode: remove completed from playlist, track recently played
                    if (completedUrl != null) {
                        val currentList = _playlist.value.toMutableList()
                        if (currentList.removeAll { it.audioUrl == completedUrl }) {
                            _playlist.value = currentList
                            savePlaylistState()
                        }
                        resolveEpisodeByAudioUrl(completedUrl)?.let { ep ->
                            val recent = _recentlyPlayedEpisodes.value.toMutableList()
                            recent.removeAll { it.audioUrl == completedUrl }
                            recent.add(ep)
                            if (recent.size > 2) recent.removeAt(0)
                            _recentlyPlayedEpisodes.value = recent
                        }
                        // If enabled in settings, auto-remove completed episode from downloads
                        val deleteOnComplete = getApplication<Application>()
                            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                            .getBoolean(KEY_DELETE_ON_COMPLETE, false)
                        if (deleteOnComplete) {
                            val downloads = _downloadedEpisodes.value.toMutableList()
                            if (downloads.removeAll { it.audioUrl == completedUrl }) {
                                _downloadedEpisodes.value = downloads
                                saveDownloadedEpisodesState(downloads)
                            }
                        }
                    }
                    val next = _playlist.value.firstOrNull()
                    currentPlayingUrl = null
                    if (next != null) {
                        playEpisode(next)
                    } else {
                        playbackController.clearMediaItem()
                        persistLastPlaybackState()
                    }
                }
            }
        }

        playbackController.onMediaItemTransition = { mediaId ->
            currentPlayingUrl = mediaId
            resolveEpisodeByAudioUrl(mediaId)?.let { episode ->
                if (lastPlaybackState?.episode?.audioUrl != mediaId) {
                    lastPlaybackState = LastPlaybackState(episode, 0L)
                }
            }
        }

        playbackController.onPlayerError = { error ->
            handlePlaybackError(error)
        }
    }

    private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
        val episode = currentPlayingEpisode.value ?: return
        val localFile = downloadedFileForEpisode(episode)
        if (localFile.exists()) {
            val currentPos = playbackController.getControllerPosition().coerceAtLeast(0L)
            Log.w("WearPod", "Playback error, falling back to local file at position $currentPos")
            playbackController.setMediaItem(episode, Uri.fromFile(localFile).toString(), currentPos)
            playbackController.play()
        } else {
            debugLog("Playback error and no local file available: ${error.message}")
        }
    }

    private fun resolvePlayableUri(episode: Episode): String {
        val localFile = DownloadFileManager.fileForAudioUrl(getApplication<Application>(), episode.audioUrl)
        return if (localFile.exists()) {
            Uri.fromFile(localFile).toString()
        } else {
            episode.audioUrl
        }
    }

    fun onPlayerScreenEntered() {
        playbackController.setPositionPollInterval(onPlayerScreenVisible = true)
        syncPlayerScreenPlaybackState()
    }

    fun onPlayerScreenExited() {
        playbackController.setPositionPollInterval(onPlayerScreenVisible = false)
    }

    private fun syncPlayerScreenPlaybackState() {
        val controllerPosition = playbackController.getControllerPosition()
            .takeIf { it > 0L } ?: lastPlaybackState?.positionMs?.coerceAtLeast(0L)
        
        val controllerDuration = playbackController.getControllerDuration()
        val episodeDuration = playbackController.currentPlayingEpisode.value?.duration
            ?.takeIf { it.isNotBlank() }
            ?.let(DurationUtils::parseDurationToMs)
            ?.coerceAtLeast(0L) ?: 0L
        val fallbackDuration = maxOf(playbackController.currentDuration.value, controllerDuration, episodeDuration)

        playbackController.updateProgressSnapshot(
            positionMs = controllerPosition ?: playbackController.currentPosition.value,
            durationMs = fallbackDuration
        )
    }

    private var currentPlayingUrl: String? = null

    fun playFromDownloads(episode: Episode) {
        _isDownloadPlaylistMode.value = true
        _downloadPlaylist.value = _downloadedEpisodes.value
        _downloadRecentlyPlayed.value = emptyList()
        playEpisode(episode, keepDownloadMode = true)
    }

    fun switchDownloadPlaylistEpisode(episode: Episode) {
        if (currentPlayingUrl == episode.audioUrl) {
            playbackController.play()
            return
        }
        if (!downloadedFileForEpisode(episode).exists()) return
        playEpisode(episode, keepDownloadMode = true)
    }

    fun removeFromDownloadPlaylist(episode: Episode) {
        val current = _downloadPlaylist.value.toMutableList()
        if (current.removeAll { it.audioUrl == episode.audioUrl }) {
            _downloadPlaylist.value = current
        }
    }

    fun playEpisode(episode: Episode, keepDownloadMode: Boolean = false) {
        if (!ConnectivityObserver.isOnline.value && !downloadedFileForEpisode(episode).exists()) {
            postUiMessage(R.string.message_offline_play_error)
            return
        }

        // 下载播放列表模式只应在"从下载页开始新队列"时进入，
        // 播完自动连播下一集 / 切换下载队列内节目时不能重置模式。
        if (!keepDownloadMode && _isDownloadPlaylistMode.value) {
            _isDownloadPlaylistMode.value = false
            _downloadPlaylist.value = emptyList()
            _downloadRecentlyPlayed.value = emptyList()
        }

        val controller = playbackController.mediaController
        if (controller == null) {
            playbackController.ensureConnected()
            pendingEpisodeToPlay = episode
            postUiMessage(R.string.message_preparing_player)
            Log.w("WearPod", "MediaController pending. Queueing play request.")
            return
        }

        if (currentPlayingUrl == episode.audioUrl) {
            playbackController.play()
            return
        }

        currentPlayingUrl = episode.audioUrl

        // Auto-add to playlist tail if not already present
        val currentPlaylist = _playlist.value.toMutableList()
        if (currentPlaylist.none { it.audioUrl == episode.audioUrl }) {
            currentPlaylist.add(episode)
            _playlist.value = currentPlaylist
            savePlaylistState()
        }

        lastPlaybackState = LastPlaybackState(episode, 0L)
        persistLastPlaybackState(0L)

        playbackController.setMediaItem(episode, resolvePlayableUri(episode))
        playbackController.play()
    }

    fun togglePlayPause() {
        if (playbackController.isPlaying.value) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = playbackController.mediaController
        if (controller == null) {
            playbackController.ensureConnected()
            playbackController.updateProgressSnapshot(positionMs = positionMs)
            persistLastPlaybackState(positionMs)
            pendingSeekPositionMs = positionMs
            return
        }
        playbackController.seekTo(positionMs)
        persistLastPlaybackState(positionMs)
    }

    fun skipForward() {
        val controller = playbackController.mediaController ?: return
        val durationCap = playbackController.getControllerDuration().takeIf { it > 0L } ?: Long.MAX_VALUE
        val newPos = (playbackController.getControllerPosition() + 15000L).coerceAtMost(durationCap).coerceAtLeast(0L)
        playbackController.seekTo(newPos)
        persistLastPlaybackState(newPos)
    }

    fun skipBackward() {
        val controller = playbackController.mediaController ?: return
        val newPos = (playbackController.getControllerPosition() - 15000L).coerceAtLeast(0L)
        playbackController.seekTo(newPos)
        persistLastPlaybackState(newPos)
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
        if (current.any { it.audioUrl == episode.audioUrl }) return
        current.add(episode)
        _playlist.value = current
        savePlaylistState()
        postUiMessage(R.string.message_added_to_playlist)
    }

    fun removeFromPlaylist(episode: Episode) {
        val current = _playlist.value.toMutableList()
        if (current.removeAll { it.audioUrl == episode.audioUrl }) {
            _playlist.value = current
            savePlaylistState()
        }
    }

    private fun savePlaylistState() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_PLAYLIST, Context.MODE_PRIVATE)
        prefs.edit {
            putString("playlist_items", EpisodeJson.serializeEpisodes(_playlist.value))
        }
    }

    private fun loadPlaylistState() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_PLAYLIST, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("playlist_items", null)
        if (jsonStr != null) {
            try {
                _playlist.value = EpisodeJson.deserializeEpisodes(jsonStr)
            } catch (e: Exception) {
                Log.w("WearPod", "Failed to parse playlist state", e)
            }
        }
    }

    private fun saveDownloadedEpisodesState(episodes: List<Episode>) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_DOWNLOADS, Context.MODE_PRIVATE)
        prefs.edit {
            putString("downloaded_list", EpisodeJson.serializeEpisodes(episodes))
        }
    }

    private fun loadDownloadedEpisodesState() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_DOWNLOADS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("downloaded_list", null)
        if (jsonStr != null) {
            try {
                val parsed = EpisodeJson.deserializeEpisodes(jsonStr)
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
        updateDownloadProgress(episode.audioUrl, 0f, force = true)
        postUiMessage(R.string.message_downloading)

        val job = viewModelScope.launch {
            downloadSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                val url = episode.audioUrl
                val file = DownloadFileManager.fileForAudioUrl(getApplication<Application>(), url)
                try {
                    // 网络错误保留部分文件用于断点续传；用户取消不重试。
                    var failure: Exception? = null
                    try {
                        downloadToFile(url, file)
                    } catch (e: Exception) {
                        // isActive 兜底：cancellingUrls 可能已被取消清理协程提前移除，
                        // 否则取消会被误判为真实失败而触发僵尸重试。
                        if (url in cancellingUrls || !isActive) throw e
                        failure = e
                    }
                    if (failure != null) {
                        downloadToFile(url, file)
                    }

                    if (!file.exists() || file.length() <= 0L) {
                        throw IllegalStateException("Downloaded file is missing or empty")
                    }

                    updateDownloadProgress(url, 1f, force = true)
                    val updated = _downloadedEpisodes.value + episode
                    _downloadedEpisodes.value = updated
                    saveDownloadedEpisodesState(updated)
                    postUiMessage(R.string.message_downloaded)

                } catch (e: Exception) {
                    // 仅删除空文件；非取消中断保留部分文件，下次下载自动续传
                    if (file.exists() && file.length() <= 0L) {
                        file.delete()
                    }
                    if (url !in cancellingUrls && isActive) {
                        Log.e("WearPod", "Download failed for $url", e)
                        postUiMessage(R.string.message_download_failed)
                    }
                } finally {
                    activeDownloadConnections.remove(url)
                    activeDownloadJobs.remove(url)
                    _downloadingEpisodes.value = _downloadingEpisodes.value.filter { it.audioUrl != url }
                    removeDownloadProgress(url)
                }
            }
            }
        }
        activeDownloadJobs[episode.audioUrl] = job
    }

    private suspend fun downloadToFile(url: String, file: java.io.File) {
        withContext(Dispatchers.IO) {
            val resumeOffset = if (file.exists()) file.length().coerceAtLeast(0L) else 0L
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = NetworkConfig.CONNECT_TIMEOUT_MS
                readTimeout = NetworkConfig.READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", downloadUserAgent)
                if (resumeOffset > 0L) {
                    setRequestProperty("Range", "bytes=$resumeOffset-")
                }
            }
            activeDownloadConnections[url] = connection
            try {
                val responseCode = connection.responseCode
                // 416 Not Satisfiable：HttpURLConnection 无此常量，416 = Range 超出（已下载完整），视为完成
                if (responseCode == 416 && resumeOffset > 0L) {
                    return@withContext
                }
                val isResume = responseCode == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0L
                if (responseCode != HttpURLConnection.HTTP_OK && !isResume) {
                    throw java.io.IOException("HTTP $responseCode for $url")
                }
                val baseOffset = if (isResume) resumeOffset else 0L
                val totalBytes = connection.contentLengthLong
                val totalSize = if (totalBytes > 0L) baseOffset + totalBytes else 0L
                var downloadedBytes = 0L

                connection.inputStream.use { input ->
                    val output = if (isResume) {
                        java.io.FileOutputStream(file, true)
                    } else {
                        java.io.FileOutputStream(file)
                    }
                    output.buffered().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            if (read > 0) {
                                out.write(buffer, 0, read)
                                downloadedBytes += read
                                if (totalSize > 0L) {
                                    updateDownloadProgress(
                                        url,
                                        ((baseOffset + downloadedBytes).toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                                    )
                                }
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            } finally {
                activeDownloadConnections.remove(url)
                connection.disconnect()
            }
        }
    }

    private val downloadUserAgent: String by lazy {
        try {
            val pkg = getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0)
            "WearPod/${pkg.versionName} (Android)"
        } catch (_: Exception) {
            "WearPod/1.0 (Android)"
        }
    }

    fun cancelDownload(episode: Episode) {
        val url = episode.audioUrl
        if (!_downloadingEpisodes.value.any { it.audioUrl == url }) return

        cancellingUrls.add(url)
        activeDownloadConnections[url]?.disconnect()
        activeDownloadConnections.remove(url)
        activeDownloadJobs[url]?.cancel()
        activeDownloadJobs.remove(url)

        _downloadingEpisodes.value = _downloadingEpisodes.value.filter { it.audioUrl != url }
        removeDownloadProgress(url)

        viewModelScope.launch(Dispatchers.IO) {
            val file = DownloadFileManager.fileForAudioUrl(getApplication<Application>(), url)
            if (file.exists()) file.delete()
            cancellingUrls.remove(url)
        }

        postUiMessage(R.string.message_download_cancelled)
    }

    fun deleteDownloadedEpisode(episode: Episode) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val file = DownloadFileManager.fileForAudioUrl(getApplication<Application>(), episode.audioUrl)
                var deleted = true
                if (file.exists()) {
                    deleted = file.delete()
                }
                val updated = _downloadedEpisodes.value.filter { it.audioUrl != episode.audioUrl }
                _downloadedEpisodes.value = updated
                saveDownloadedEpisodesState(updated)
                // 同步移除离线播放队列中的同集，避免离线下播到已删除集时卡在队头
                if (_downloadPlaylist.value.any { it.audioUrl == episode.audioUrl }) {
                    _downloadPlaylist.value = _downloadPlaylist.value.filter { it.audioUrl != episode.audioUrl }
                }
                postUiMessage(if (deleted) R.string.message_deleted else R.string.message_delete_failed)
            }
        }
    }

    private fun postUiMessage(@StringRes resId: Int) {
        _uiMessages.tryEmit(getApplication<Application>().getString(resId))
    }

    private fun updateDownloadProgress(audioUrl: String, progress: Float, force: Boolean = false) {
        val normalized = progress.coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        val previousValue = lastDownloadProgressValue[audioUrl] ?: -1f
        val previousPublishAt = lastDownloadProgressPublishTimeMs[audioUrl] ?: 0L

        val shouldPublish = force ||
            previousValue < 0f ||
            normalized >= 1f ||
            (normalized - previousValue) >= DOWNLOAD_PROGRESS_MIN_DELTA ||
            (now - previousPublishAt) >= DOWNLOAD_PROGRESS_PUBLISH_INTERVAL_MS

        if (!shouldPublish) {
            return
        }

        lastDownloadProgressValue[audioUrl] = normalized
        lastDownloadProgressPublishTimeMs[audioUrl] = now
        _downloadProgress.update { current ->
            current.toMutableMap().apply { put(audioUrl, normalized) }
        }
    }

    private fun removeDownloadProgress(audioUrl: String) {
        lastDownloadProgressValue.remove(audioUrl)
        lastDownloadProgressPublishTimeMs.remove(audioUrl)
        _downloadProgress.update { current ->
            current.toMutableMap().apply { remove(audioUrl) }
        }
    }

    private fun downloadedFileForEpisode(episode: Episode): java.io.File {
        return DownloadFileManager.fileForAudioUrl(getApplication<Application>(), episode.audioUrl)
    }

    private suspend fun updateSortedLibraryPodcasts(podcasts: List<Podcast>) {
        _sortedLibraryPodcasts.value = withContext(Dispatchers.Default) {
            podcasts.withIndex()
                .map { (index, podcast) ->
                    val normalized = normalizeTitleForSort(podcast.title)
                    Triple(index, podcast, normalized)
                }
                .sortedWith(compareBy<Triple<Int, Podcast, String>>({ alphaBucket(it.third) }, { it.third }, { it.first }))
                .map { (index, podcast, _) -> index to podcast }
        }
    }

    private fun normalizeTitleForSort(title: String): String {
        if (title.isBlank()) return ""
        val latin = hanToLatinTransliterator.transliterate(title)
        return latin.uppercase(java.util.Locale.ROOT)
            .replace(Regex("[^A-Z0-9 ]"), "")
            .trim()
    }

    private fun alphaBucket(normalizedTitle: String): Int {
        val firstChar = normalizedTitle.firstOrNull { it.isLetterOrDigit() }
        return if (firstChar != null && firstChar in 'A'..'Z') firstChar - 'A' else 26
    }

    fun onSleepTimerScreenEntered() {
        isSleepTimerScreenVisible = true
    }

    fun onSleepTimerScreenExited() {
        isSleepTimerScreenVisible = false
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
                    // 倒计时页可见时按秒刷新；不可见时降到 5s 轮询，减少无谓唤醒
                    delay(if (isSleepTimerScreenVisible) 1_000L else 5_000L)
                }
                if (playbackController.isPlaying.value) {
                     playbackController.pause()
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
        playbackController.release()
    }
}
