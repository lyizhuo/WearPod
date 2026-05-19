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
import kotlinx.coroutines.flow.update
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
    private var isPlayerScreenVisible = false
    private var isAppInForeground = true
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

        viewModelScope.launch(Dispatchers.IO) {
            loadCachedInboxEpisodesState(initialCustomId)
            loadDownloadedEpisodesState()
            loadPlaylistState()
            loadLastPlaybackState()
        }
        initializeController()

        viewModelScope.launch {
            // Delay heavy initialization to ensure UI layout passes are smooth
            delay(500)
            loadSubscriptions()
        }
    }

    private fun loadSubscriptions() {
        if (!ConnectivityObserver.isOnline.value) return
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
                                    .take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
                            }
                        }
                    }

                    val merged = LinkedHashMap<String, Episode>().apply {
                        mergeEpisodes(this, parsedEpisodes.values.toList())
                        mergeEpisodes(this, result)
                    }
                    sortEpisodesByDate(merged.values).take(FeedRepository.MAX_TOTAL_INBOX_ITEMS)
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
        if (!ConnectivityObserver.isOnline.value) return
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
                }

                _inboxEpisodes.value = allEpisodes
                if (allEpisodes.isNotEmpty()) {
                    saveInboxEpisodesState(allEpisodes)
                }
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

            // Ensure restored episode is at the head of playlist
            val currentPlaylist = _playlist.value.toMutableList()
            currentPlaylist.removeAll { it.audioUrl == episode.audioUrl }
            currentPlaylist.add(0, episode)
            _playlist.value = currentPlaylist
            savePlaylistState()
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
                    // Download mode: track recently played (grey), FIFO max 2
                    if (completedUrl != null) {
                        resolveEpisodeByAudioUrl(completedUrl)?.let { ep ->
                            val recent = _downloadRecentlyPlayed.value.toMutableList()
                            recent.removeAll { it.audioUrl == completedUrl }
                            recent.add(ep)
                            if (recent.size > 2) recent.removeAt(0)
                            _downloadRecentlyPlayed.value = recent
                        }
                    }
                    val queue = _downloadPlaylist.value
                    if (queue.isNotEmpty()) {
                        val next = queue[0]
                        _downloadPlaylist.value = queue.drop(1)
                        currentPlayingUrl = null
                        playEpisode(next)
                    } else {
                        _isDownloadPlaylistMode.value = false
                        playbackController.clearMediaItem()
                        currentPlayingUrl = null
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
            ?.let(DurationUtils::parseDurationToMs)
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
            isPlaying && isPlayerScreenVisible && isAppInForeground -> 500L
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

    fun playFromDownloads(episode: Episode) {
        _isDownloadPlaylistMode.value = true
        val others = _downloadedEpisodes.value.filter { it.audioUrl != episode.audioUrl }
        _downloadPlaylist.value = others
        _downloadRecentlyPlayed.value = emptyList()
        playEpisode(episode)
    }

    fun playEpisode(episode: Episode) {
        if (!ConnectivityObserver.isOnline.value && !downloadedFileForEpisode(episode).exists()) {
            postUiMessage(R.string.message_offline_play_error)
            return
        }

        if (_isDownloadPlaylistMode.value && !downloadedFileForEpisode(episode).exists()) {
            _isDownloadPlaylistMode.value = false
            _downloadPlaylist.value = emptyList()
        }

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
                val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
                val file = java.io.File(getApplication<Application>().filesDir, filename)
                try {
                    if (!file.exists()) {
                        val connection = (URL(episode.audioUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = NetworkConfig.CONNECT_TIMEOUT_MS
                            readTimeout = NetworkConfig.READ_TIMEOUT_MS
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
