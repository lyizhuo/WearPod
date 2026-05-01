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
import site.whitezaak.wearpod.data.FeedRepository
import site.whitezaak.wearpod.service.PlaybackController
import site.whitezaak.wearpod.util.PubDateNormalizer
import android.icu.text.Transliterator

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val feedRepository = FeedRepository(application)
    val playbackController = PlaybackController(application)
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

    val mediaController = MutableStateFlow<MediaController?>(null)
    
    val isPlaying = playbackController.isPlaying
    val isBuffering = playbackController.isBuffering
    val currentPlayingEpisode = playbackController.currentPlayingEpisode

    private val _playlist = MutableStateFlow<List<Episode>>(emptyList())
    val playlist: StateFlow<List<Episode>> = _playlist.asStateFlow()

    private val _recentlyPlayedEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val recentlyPlayedEpisodes: StateFlow<List<Episode>> = _recentlyPlayedEpisodes.asStateFlow()

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
    private val lastDownloadProgressPublishTimeMs = ConcurrentHashMap<String, Long>()
    private val lastDownloadProgressValue = ConcurrentHashMap<String, Float>()
    private val activeDownloadJobs = mutableMapOf<String, Job>()
    private val activeDownloadConnections = mutableMapOf<String, HttpURLConnection>()
    private val cancellingUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        const val PREFS_PLAYLIST = "wearpod_playlist"
        const val PREFS_PLAYBACK = "wearpod_playback"
        const val KEY_LAST_EPISODE = "last_episode"
        const val KEY_LAST_POSITION = "last_position_ms"
        const val KEY_INBOX_CACHE_TIMESTAMP_SUFFIX = "_timestamp"
        const val FEED_CACHE_TTL_MS = 2 * 60 * 1000L
        const val INBOX_REFRESH_TTL_MS = 2 * 60 * 1000L
        const val BATCH_UI_PUBLISH_INTERVAL_MS = 250L
        const val DOWNLOAD_PROGRESS_PUBLISH_INTERVAL_MS = 140L
        const val DOWNLOAD_PROGRESS_MIN_DELTA = 0.02f
        const val SEEK_SETTLE_DELAY_MS = 120L
        const val PLAYBACK_PERSIST_INTERVAL_MS = 1_500L
        val PUB_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    }
    
    val currentPosition = playbackController.currentPosition
    val currentDuration = playbackController.currentDuration
    private var progressJob: Job? = null

    init {
        val prefs = getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
        val initialCustomId = prefs.getString("custom_opml_id", null)
        _customOpmlId.value = initialCustomId
        _appLanguageTag.value = AppLanguageManager.getSelectedLanguageTag(application)
        loadCachedInboxEpisodesState(initialCustomId)
        loadDownloadedEpisodesState()
        loadPlaylistState()
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
            val loadedPodcasts = feedRepository.loadSubscriptions(_customOpmlId.value)
            if (loadedPodcasts.isNotEmpty()) {
                _podcasts.value = loadedPodcasts
                updateSortedLibraryPodcasts(loadedPodcasts)
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

            getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
                .edit { putString("custom_opml_id", id) }

            val loadedPodcasts = feedRepository.loadSubscriptions(id)
            
            if (loadedPodcasts.isNotEmpty()) {
                _podcasts.value = loadedPodcasts
                updateSortedLibraryPodcasts(loadedPodcasts)
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
                    val parsedEpisodes = LinkedHashMap<String, Episode>().apply {
                        mergeEpisodes(this, baselineEpisodes)
                    }
                    val result = feedRepository.fetchFeedEpisodes(feedUrl, "FEED") { batch ->
                        synchronized(parsedEpisodes) {
                            mergeEpisodes(parsedEpisodes, batch)
                            if (shouldPublishFeedBatch()) {
                                _episodes.value = sortEpisodesByDate(parsedEpisodes.values)
                                    .take(MAX_TOTAL_INBOX_ITEMS)
                            }
                        }
                    }

                    val merged = LinkedHashMap<String, Episode>().apply {
                        mergeEpisodes(this, parsedEpisodes.values.toList())
                        mergeEpisodes(this, result)
                    }
                    sortEpisodesByDate(merged.values).take(MAX_TOTAL_INBOX_ITEMS)
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
                    val fetchedLists = feedRepository.fetchInboxEpisodesConcurrently(currentPodcasts, "INBOX") { batch ->
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

                    synchronized(mergeLock) {
                        fetchedLists.forEach { mergeEpisodes(freshEpisodes, it) }
                        sortEpisodesByDate(freshEpisodes.values)
                            .take(MAX_TOTAL_INBOX_ITEMS)
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
            val episode = deserializeEpisode(org.json.JSONObject(episodeJson))
            val position = prefs.getLong(KEY_LAST_POSITION, 0L).coerceAtLeast(0L)
            lastPlaybackState = LastPlaybackState(episode, position)
            playbackController.hydrateState(episode, position)
            currentPlayingUrl = episode.audioUrl

            // Ensure last-played episode is in the playlist so it won't vanish on switch
            val playlist = _playlist.value.toMutableList()
            if (playlist.none { it.audioUrl == episode.audioUrl }) {
                playlist.add(episode)
                _playlist.value = playlist
                savePlaylistState()
            }
        } catch (e: Exception) {
            Log.w("WearPod", "Failed to parse last playback state", e)
        }
    }

    private fun restoreLastPlaybackToController() {
        val state = lastPlaybackState ?: return
        val currentDuration = playbackController.getControllerDuration()
        
        if (currentDuration <= 0L) {
            playbackController.setMediaItem(state.episode, resolvePlayableUri(state.episode))
        }

        if (state.positionMs > 0) {
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
            
            if (isPlayerScreenVisible && isAppInForeground) {
                startProgressTracking()
            }
        }
        
        playbackController.onPlaybackEnded = {
            // Mark the just-completed episode as recently played (100% completed)
            val completedUrl = currentPlayingUrl
            if (completedUrl != null) {
                resolveEpisodeByAudioUrl(completedUrl)?.let { ep ->
                    val recent = _recentlyPlayedEpisodes.value.toMutableList()
                    recent.removeAll { it.audioUrl == completedUrl }
                    recent.add(ep)
                    if (recent.size > 3) recent.removeAt(0)
                    _recentlyPlayedEpisodes.value = recent
                }
            }

            if (pauseOnEpisodeEnd) {
                currentSleepTimerMode.value = SleepTimerMode.Off
                currentSleepTimerRemainingMs.value = null
                pauseOnEpisodeEnd = false
                playbackController.clearMediaItem()
                currentPlayingUrl = null
                persistLastPlaybackState()
            } else {
                val queue = _playlist.value.toMutableList()
                if (queue.isNotEmpty()) {
                    val next = queue.removeAt(0)
                    _playlist.value = queue
                    savePlaylistState()
                    currentPlayingUrl = null
                    playEpisode(next)
                } else {
                    playbackController.clearMediaItem()
                    currentPlayingUrl = null
                    persistLastPlaybackState()
                }
            }
        }

        playbackController.onMediaItemTransition = { mediaId ->
            currentPlayingUrl = mediaId
            resolveEpisodeByAudioUrl(mediaId)?.let { episode ->
                lastPlaybackState = LastPlaybackState(episode, 0L)
            }
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

    fun onPlayerScreenEntered() {
        isPlayerScreenVisible = true
        syncPlayerScreenPlaybackState()
        startProgressTracking()
    }

    fun onPlayerScreenExited() {
        isPlayerScreenVisible = false
        if (!playbackController.isPlaying.value) {
            stopProgressTracking()
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        if (isAppInForeground == inForeground) {
            return
        }
        isAppInForeground = inForeground

        if (shouldTrackProgress()) {
            startProgressTracking()
        } else {
            stopProgressTracking()
        }
    }

    private fun shouldTrackProgress(): Boolean {
        val playing = playbackController.isPlaying.value
        val needsInteractiveProgress = isPlayerScreenVisible && isAppInForeground
        return playing || needsInteractiveProgress
    }

    private fun syncPlayerScreenPlaybackState() {
        val controllerPosition = playbackController.getControllerPosition()
            .takeIf { it > 0L } ?: lastPlaybackState?.positionMs?.coerceAtLeast(0L)
        
        val controllerDuration = playbackController.getControllerDuration()
        val episodeDuration = playbackController.currentPlayingEpisode.value?.duration
            ?.takeIf { it.isNotBlank() }
            ?.let(::parseDurationToMs)
            ?.coerceAtLeast(0L) ?: 0L
        val fallbackDuration = maxOf(playbackController.currentDuration.value, controllerDuration, episodeDuration)

        playbackController.updateProgressSnapshot(
            positionMs = controllerPosition ?: playbackController.currentPosition.value,
            durationMs = fallbackDuration
        )
    }

    private fun progressPollIntervalMs(): Long {
        val isPlaying = playbackController.isPlaying.value
        return when {
            isPlaying && isPlayerScreenVisible && isAppInForeground -> 180L
            isPlaying && isAppInForeground -> 800L
            isPlaying -> 2500L
            isPlayerScreenVisible && isAppInForeground -> 1200L
            else -> 2500L
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        if (!shouldTrackProgress()) {
            return
        }
        progressJob = viewModelScope.launch {
            while (isActive) {
                if (!shouldTrackProgress()) {
                    break
                }
                if (!isSeeking) {
                    playbackController.syncProgress()
                }
                maybePersistPlaybackState()
                delay(progressPollIntervalMs())
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
        val controller = playbackController.mediaController
        if (controller == null) {
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

        // Auto-add to playlist if not already present
        val playlist = _playlist.value.toMutableList()
        if (playlist.none { it.audioUrl == episode.audioUrl }) {
            playlist.add(episode)
            _playlist.value = playlist
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

    private var isSeeking = false

    fun seekTo(positionMs: Long) {
        val controller = playbackController.mediaController
        if (controller == null) {
            playbackController.updateProgressSnapshot(positionMs = positionMs)
            persistLastPlaybackState(positionMs)
            pendingSeekPositionMs = positionMs
            return
        }
        isSeeking = true
        progressJob?.cancel()
        playbackController.seekTo(positionMs)
        persistLastPlaybackState(positionMs)
        
        viewModelScope.launch {
            delay(SEEK_SETTLE_DELAY_MS)
            isSeeking = false
            startProgressTracking()
        }
    }

    fun skipForward() {
        val controller = playbackController.mediaController ?: return
        isSeeking = true
        progressJob?.cancel()
        val durationCap = playbackController.getControllerDuration().takeIf { it > 0L } ?: Long.MAX_VALUE
        val newPos = (playbackController.getControllerPosition() + 15000L).coerceAtMost(durationCap).coerceAtLeast(0L)
        playbackController.seekTo(newPos)
        persistLastPlaybackState(newPos)

        viewModelScope.launch {
            delay(SEEK_SETTLE_DELAY_MS)
            isSeeking = false
            startProgressTracking()
        }
    }

    fun skipBackward() {
        val controller = playbackController.mediaController ?: return
        isSeeking = true
        progressJob?.cancel()
        val newPos = (playbackController.getControllerPosition() - 15000L).coerceAtLeast(0L)
        playbackController.seekTo(newPos)
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
        val existingIndex = current.indexOfFirst { it.audioUrl == episode.audioUrl }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            current.add(episode)
            _playlist.value = current
        } else {
            current.add(episode)
            _playlist.value = current
            postUiMessage(R.string.message_added_to_playlist)
        }
        savePlaylistState()
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
            putString("playlist_items", serializeEpisodes(_playlist.value))
        }
    }

    private fun loadPlaylistState() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_PLAYLIST, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("playlist_items", null)
        if (jsonStr != null) {
            try {
                _playlist.value = deserializeEpisodes(jsonStr)
            } catch (e: Exception) {
                Log.w("WearPod", "Failed to parse playlist state", e)
            }
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
        updateDownloadProgress(episode.audioUrl, 0f, force = true)
        postUiMessage(R.string.message_downloading)

        val job = viewModelScope.launch {
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
                        activeDownloadConnections[episode.audioUrl] = connection

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

                    updateDownloadProgress(episode.audioUrl, 1f, force = true)
                    val updated = _downloadedEpisodes.value + episode
                    _downloadedEpisodes.value = updated
                    saveDownloadedEpisodesState(updated)
                    postUiMessage(R.string.message_downloaded)
                } catch (e: Exception) {
                    if (file.exists()) {
                        file.delete()
                    }
                    if (episode.audioUrl !in cancellingUrls) {
                        Log.e("WearPod", "Download failed for ${episode.audioUrl}", e)
                        postUiMessage(R.string.message_download_failed)
                    }
                } finally {
                    activeDownloadConnections.remove(episode.audioUrl)
                    activeDownloadJobs.remove(episode.audioUrl)
                    _downloadingEpisodes.value = _downloadingEpisodes.value.filter { it.audioUrl != episode.audioUrl }
                    removeDownloadProgress(episode.audioUrl)
                }
            }
        }
        activeDownloadJobs[episode.audioUrl] = job
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
            val filename = "episode_${url.hashCode()}.mp3"
            val file = java.io.File(getApplication<Application>().filesDir, filename)
            if (file.exists()) file.delete()
            cancellingUrls.remove(url)
        }

        postUiMessage(R.string.message_download_cancelled)
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
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            put(audioUrl, normalized)
        }
    }

    private fun removeDownloadProgress(audioUrl: String) {
        lastDownloadProgressValue.remove(audioUrl)
        lastDownloadProgressPublishTimeMs.remove(audioUrl)
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            remove(audioUrl)
        }
    }

    private fun downloadedFileForEpisode(episode: Episode): java.io.File {
        val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
        return java.io.File(getApplication<Application>().filesDir, filename)
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

    private fun serializeEpisodes(episodes: List<Episode>): String {
        val array = org.json.JSONArray()
        episodes.forEach { ep ->
            array.put(serializeEpisode(ep))
        }
        return array.toString()
    }

    private fun deserializeEpisodes(jsonStr: String): List<Episode> {
        val array = org.json.JSONArray(jsonStr)
        val list = mutableListOf<Episode>()
        for (i in 0 until array.length()) {
            list.add(deserializeEpisode(array.getJSONObject(i)))
        }
        return list
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
