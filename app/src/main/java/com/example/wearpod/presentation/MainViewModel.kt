package com.example.wearpod.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wearpod.R
import com.example.wearpod.data.OpmlParser
import com.example.wearpod.data.RssParser
import com.example.wearpod.domain.Episode
import com.example.wearpod.domain.Podcast
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
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import java.util.Date
import java.util.Locale
import android.util.Log
import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.wearpod.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import android.media.AudioManager
import android.content.Context
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private data class FeedCacheEntry(
        val episodes: List<Episode>,
        val timestampMs: Long,
    )

    private data class LastPlaybackState(
        val episode: Episode,
        val positionMs: Long,
    )

    private val _podcasts = MutableStateFlow<List<Podcast>>(emptyList())
    val podcasts: StateFlow<List<Podcast>> = _podcasts.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _inboxEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val inboxEpisodes: StateFlow<List<Episode>> = _inboxEpisodes.asStateFlow()

    private val _visibleInboxEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val visibleInboxEpisodes: StateFlow<List<Episode>> = _visibleInboxEpisodes.asStateFlow()

    val hasMoreInboxEpisodes = MutableStateFlow(false)

    private val _customOpmlId = MutableStateFlow<String?>(null)
    val customOpmlId: StateFlow<String?> = _customOpmlId.asStateFlow()

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
    val currentSleepTimerMode = MutableStateFlow("Off")
    private var sleepTimerJob: Job? = null
    private var pauseOnEpisodeEnd = false
    private var inboxLoadJob: Job? = null
    private var feedLoadJob: Job? = null
    private var inboxVisibleLimit = 0
    private var currentFeedUrl: String? = null
    private val feedCache = mutableMapOf<String, FeedCacheEntry>()
    private var lastPlaybackState: LastPlaybackState? = null
    private var lastPlaybackPersistTimeMs = 0L
    private var pendingEpisodeToPlay: Episode? = null
    private var pendingSeekPositionMs: Long? = null
    private var isPlayerScreenVisible = false
    private val prefetchedArtworkTimes = mutableMapOf<String, Long>()

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
        const val FEED_CACHE_TTL_MS = 2 * 60 * 1000L
        const val SEEK_SETTLE_DELAY_MS = 120L
        const val PLAYBACK_PERSIST_INTERVAL_MS = 1_500L
        const val ARTWORK_PREFETCH_TTL_MS = 5 * 60 * 1000L
        val PUB_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    }
    
    val currentPosition = MutableStateFlow(0L)
    val currentDuration = MutableStateFlow(0L)
    private var progressJob: Job? = null

    init {
        val prefs = getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
        val initialCustomId = prefs.getString("custom_opml_id", null)
        _customOpmlId.value = initialCustomId
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
                        val urlString = "https://pod.whitezaak.site/$customId.xml"
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
                loadInboxEpisodes(force = true)
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
                        .edit().putString("custom_opml_id", id).apply()

                    val urlString = "https://pod.whitezaak.site/$id.xml"
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
                loadInboxEpisodes(force = true)
            } else if (isRefreshingInbox.value) {
                isRefreshingInbox.value = false
            }
        }
    }

    fun forceRefreshInbox() {
        if (isRefreshingInbox.value) return
        isRefreshingInbox.value = true
        val currentId = _customOpmlId.value
        if (currentId != null) {
            loadCustomOpml(currentId)
        } else {
            loadSubscriptions()
        }
    }

    fun loadEpisodes(feedUrl: String) {
        val now = System.currentTimeMillis()
        val cached = feedCache[feedUrl]

        if (cached != null && now - cached.timestampMs <= FEED_CACHE_TTL_MS) {
            _episodes.value = cached.episodes
            if (currentFeedUrl == feedUrl) {
                return
            }
        } else if (currentFeedUrl != feedUrl) {
            _episodes.value = emptyList()
        }

        if (feedLoadJob?.isActive == true && currentFeedUrl == feedUrl) {
            return
        }

        currentFeedUrl = feedUrl
        feedLoadJob?.cancel()
        isLoadingFeed.value = true

        feedLoadJob = viewModelScope.launch {
            try {
                val loadedEpisodes = withContext(Dispatchers.IO) {
                    try {
                        val parsedEpisodes = LinkedHashMap<String, Episode>()
                        val result = withUrlInputStream(feedUrl) { inputStream ->
                            RssParser().parse(
                                inputStream,
                                maxItems = MAX_TOTAL_INBOX_ITEMS,
                                onBatchParsed = { batch ->
                                    synchronized(parsedEpisodes) {
                                        mergeEpisodes(parsedEpisodes, batch)
                                        _episodes.value = sortEpisodesByDate(parsedEpisodes.values)
                                    }
                                }
                            )
                        }

                        sortEpisodesByDate(result)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }
                }

                _episodes.value = loadedEpisodes
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
            inboxLoadJob?.cancel()
        }

        // Set refreshing state immediately
        isRefreshingInbox.value = true

        inboxLoadJob = viewModelScope.launch {
            val currentPodcasts = _podcasts.value
            try {
                val freshEpisodes = LinkedHashMap<String, Episode>()
                val mergeLock = Any()
                val allEpisodes = withContext(Dispatchers.IO) {
                    val rssParser = RssParser()
                    val limiter = Semaphore(MAX_CONCURRENT_INBOX_FETCH)

                    coroutineScope {
                        val fetchDeferreds = currentPodcasts.map { podcast ->
                            async {
                                limiter.withPermit {
                                    try {
                                        withUrlInputStream(podcast.feedUrl) { inputStream ->
                                            rssParser.parse(
                                                inputStream,
                                                maxItems = MAX_INBOX_ITEMS_PER_FEED,
                                                onBatchParsed = { batch ->
                                                    synchronized(mergeLock) {
                                                        mergeEpisodes(freshEpisodes, batch)
                                                        val snapshot = sortEpisodesByDate(freshEpisodes.values)
                                                            .take(MAX_TOTAL_INBOX_ITEMS)
                                                        _inboxEpisodes.value = snapshot
                                                        publishVisibleInboxEpisodes()
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

                        fetchDeferreds.awaitAll()
                            .flatten()
                            .distinctBy { episodeKey(it) }
                            .let(::sortEpisodesByDate)
                            .take(MAX_TOTAL_INBOX_ITEMS)
                    }
                }

                _inboxEpisodes.value = allEpisodes
                if (allEpisodes.isNotEmpty()) {
                    saveInboxEpisodesState(allEpisodes)
                }
                resetVisibleInboxEpisodes()
                Log.d("WearPod", "Finished loading inbox with limit=${MAX_CONCURRENT_INBOX_FETCH}. Total: ${_inboxEpisodes.value.size}")
            } finally {
                isRefreshingInbox.value = false
            }
        }
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
        hasMoreInboxEpisodes.value = _inboxEpisodes.value.size > visibleEpisodes.size
    }

    private fun sortEpisodesByDate(episodes: Collection<Episode>): List<Episode> {
        return episodes.sortedByDescending { episodeTimestamp(it.pubDate) }
    }

    private fun episodeTimestamp(pubDate: String): Long {
        return try {
            LocalDate.parse(pubDate, PUB_DATE_FORMATTER)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } catch (e: Exception) {
            0L
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
        prefs.edit()
            .putString(inboxCacheKey(ownerId), serializeEpisodes(episodes))
            .apply()
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
        prefs.edit()
            .putString(KEY_LAST_EPISODE, serializeEpisode(episode).toString())
            .putLong(KEY_LAST_POSITION, safePosition)
            .apply()
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
        val currentUri = controller.currentMediaItem?.localConfiguration?.uri?.toString()

        if (currentUri == null) {
            controller.setMediaItem(MediaItem.fromUri(resolvePlayableUri(state.episode)))
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
            android.net.Uri.fromFile(localFile).toString()
        } else {
            episode.audioUrl
        }
    }

    private inline fun <T> withUrlInputStream(url: String, block: (java.io.InputStream) -> T): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        return try {
            connection.inputStream.buffered().use(block)
        } finally {
            connection.disconnect()
        }
    }

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
                 
                 val currentUri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
                 if (currentUri != null) {
                     val matchingEpisode = _episodes.value.find { it.audioUrl == currentUri } 
                         ?: _inboxEpisodes.value.find { it.audioUrl == currentUri }
                         ?: _playlist.value.find { it.audioUrl == currentUri }
                         ?: _downloadedEpisodes.value.find { it.audioUrl == currentUri }
                         ?: lastPlaybackState?.episode
                     if (matchingEpisode != null) {
                         currentPlayingEpisode.value = matchingEpisode
                         currentPlayingUrl = matchingEpisode.audioUrl
                     }
                 }
                 currentPosition.value = controller.currentPosition.coerceAtLeast(0L)
                 currentDuration.value = controller.duration.coerceAtLeast(0L)
            }

            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    Log.d("WearPod", "onIsPlayingChanged: $playing")
                    isPlaying.value = playing
                    if (playing) {
                        startProgressTracking()
                    } else {
                        stopProgressTracking()
                        persistLastPlaybackState()
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d("WearPod", "onPlaybackStateChanged: $playbackState")
                    isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                    
                    if (playbackState == Player.STATE_READY) {
                        currentDuration.value = controller?.duration ?: 0L
                        currentPosition.value = controller?.currentPosition ?: currentPosition.value
                        persistLastPlaybackState()
                    }
                    
                    if (playbackState == Player.STATE_ENDED) {
                        if (pauseOnEpisodeEnd) {
                            currentSleepTimerMode.value = "Off"
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
        currentDuration.value = controller.duration.coerceAtLeast(0L)
        if (controller.isPlaying) {
            startProgressTracking()
        }
    }

    fun onPlayerScreenExited() {
        isPlayerScreenVisible = false
    }

    fun prefetchEpisodeArtwork(episode: Episode?) {
        if (episode == null) return
        val rawUrl = episode.imageUrl.ifEmpty { episode.podcastImageUrl }
        if (rawUrl.isBlank()) return

        val imageUrl = if (rawUrl.startsWith("http://")) {
            rawUrl.replaceFirst("http://", "https://")
        } else {
            rawUrl
        }

        val now = System.currentTimeMillis()
        val lastPrefetch = prefetchedArtworkTimes[imageUrl]
        if (lastPrefetch != null && (now - lastPrefetch) < ARTWORK_PREFETCH_TTL_MS) {
            return
        }

        prefetchedArtworkTimes[imageUrl] = now
        val context = getApplication<Application>().applicationContext
        context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(imageUrl)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .size(320)
                .build()
        )
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                val controller = mediaController.value
                controller?.let {
                    if (!isSeeking) {
                        currentPosition.value = it.currentPosition
                    }
                    currentDuration.value = it.duration
                    maybePersistPlaybackState()
                }
                delay(
                    when {
                        controller?.isPlaying == true && isPlayerScreenVisible -> 120L
                        controller?.isPlaying == true -> 800L
                        else -> 1200L
                    }
                )
            }
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
            postUiMessage("Preparing player...")
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
        currentDuration.value = 0L
        lastPlaybackState = LastPlaybackState(episode, 0L)
        persistLastPlaybackState(0L)
        
        // Check if we have it downloaded
        val uriToPlay = resolvePlayableUri(episode)
        
        val mediaItem = MediaItem.fromUri(uriToPlay)
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
        val newPos = (controller.currentPosition + 15000L).coerceAtMost(controller.duration)
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
            postUiMessage("Added to playlist")
        } else {
            postUiMessage("Already in playlist")
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
        prefs.edit().putString("downloaded_list", serializeEpisodes(episodes)).apply()
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
            postUiMessage("Already downloaded")
            return
        }
        if (_downloadingEpisodes.value.any { it.audioUrl == episode.audioUrl }) {
            postUiMessage("Already downloading")
            return
        }

        _downloadingEpisodes.value = _downloadingEpisodes.value + episode
        updateDownloadProgress(episode.audioUrl, 0f)
        postUiMessage("Downloading...")
        
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
                    postUiMessage("Downloaded")
                } catch (e: Exception) {
                    if (file.exists()) {
                        file.delete()
                    }
                    Log.e("WearPod", "Download failed for ${episode.audioUrl}", e)
                    postUiMessage("Download failed")
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
                postUiMessage(if (deleted) "Deleted" else "Delete failed")
            }
        }
    }

    private fun postUiMessage(message: String) {
        _uiMessages.tryEmit(message)
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

    fun setSleepTimer(mode: String, minutes: Int) {
        currentSleepTimerMode.value = mode
        sleepTimerJob?.cancel()
        pauseOnEpisodeEnd = false
        
        if (minutes > 0) {
            sleepTimerJob = viewModelScope.launch {
                delay(minutes * 60 * 1000L)
                val controller = mediaController.value
                if (controller?.isPlaying == true) {
                     controller.pause()
                }
                currentSleepTimerMode.value = "Off"
            }
        } else if (minutes == -1) {
            pauseOnEpisodeEnd = true
        }
    }

    override fun onCleared() {
        persistLastPlaybackState()
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
