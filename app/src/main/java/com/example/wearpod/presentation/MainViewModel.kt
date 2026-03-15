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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Date
import java.text.SimpleDateFormat
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _podcasts = MutableStateFlow<List<Podcast>>(emptyList())
    val podcasts: StateFlow<List<Podcast>> = _podcasts.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _inboxEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val inboxEpisodes: StateFlow<List<Episode>> = _inboxEpisodes.asStateFlow()

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

    init {
        loadSubscriptions()
        initializeController()
        loadDownloadedEpisodesState()
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
                        val inputStream = URL(urlString).openStream()
                        OpmlParser().parse(inputStream)
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
                loadInboxEpisodes()
            }
        }
    }

    fun loadCustomOpml(id: String) {
        viewModelScope.launch {
            val loadedPodcasts = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
                        .edit().putString("custom_opml_id", id).apply()
                    _customOpmlId.value = id
                        
                    val urlString = "https://pod.whitezaak.site/$id.xml"
                    val inputStream = URL(urlString).openStream()
                    OpmlParser().parse(inputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList<Podcast>()
                }
            }
            if (loadedPodcasts.isNotEmpty()) {
                _podcasts.value = loadedPodcasts
                // Optional: Invalidate existing episodes if subscriptions change drastically
                _inboxEpisodes.value = emptyList()
                loadInboxEpisodes()
            }
        }
    }

    fun forceRefreshInbox() {
        _inboxEpisodes.value = emptyList()
        val currentId = _customOpmlId.value
        if (currentId != null) {
            loadCustomOpml(currentId)
        } else {
            loadSubscriptions()
        }
    }

    fun loadEpisodes(feedUrl: String) {
        viewModelScope.launch {
            // clear current
            _episodes.value = emptyList() 
            val loadedEpisodes = withContext(Dispatchers.IO) {
                try {
                    val inputStream = URL(feedUrl).openStream()
                    val result = RssParser().parse(inputStream)
                    
                    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
                    result.sortedByDescending { episode ->
                        try {
                            dateFormat.parse(episode.pubDate)?.time ?: 0L
                        } catch (e: Exception) {
                            0L
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
            _episodes.value = loadedEpisodes
        }
    }

    fun loadInboxEpisodes() {
        if (_inboxEpisodes.value.isNotEmpty()) return

        viewModelScope.launch {
            _inboxEpisodes.value = emptyList()
            val allEpisodes = mutableListOf<Episode>()
            val currentPodcasts = _podcasts.value
            
            withContext(Dispatchers.IO) {
                for (podcast in currentPodcasts) {
                    try {
                        val inputStream = URL(podcast.feedUrl).openStream()
                        val result = RssParser().parse(inputStream)
                        allEpisodes.addAll(result)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            // Sort by pubDate parsed back to Date objects
            // The format from RssParser is now "dd MMM yyyy", e.g. "14 Mar 2026"
            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
            val sortedEpisodes = allEpisodes.sortedByDescending { episode ->
                try {
                    dateFormat.parse(episode.pubDate)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
            _inboxEpisodes.value = sortedEpisodes
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
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    Log.d("WearPod", "onIsPlayingChanged: $playing")
                    isPlaying.value = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d("WearPod", "onPlaybackStateChanged: $playbackState")
                    isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                    
                    if (playbackState == Player.STATE_ENDED) {
                        playNextInQueue()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("WearPod", "ExoPlayer Error: ${error.message}", error)
                    isPlaying.value = false
                    isBuffering.value = false
                }
            })
        }, MoreExecutors.directExecutor())
    }

    private var currentPlayingUrl: String? = null

    fun playEpisode(episode: Episode) {
        val controller = mediaController.value
        if (controller == null) {
            Log.e("WearPod", "MediaController is null. Initialization failed or pending.")
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
        
        // Check if we have it downloaded
        val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
        val localFile = java.io.File(getApplication<Application>().filesDir, filename)
        val uriToPlay = if (localFile.exists()) {
            android.net.Uri.fromFile(localFile).toString()
        } else {
            episode.audioUrl
        }
        
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

    fun skipForward() {
        val controller = mediaController.value ?: return
        controller.seekForward()
    }

    fun skipBackward() {
        val controller = mediaController.value ?: return
        controller.seekBack()
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
        val prefs = getApplication<Application>().getSharedPreferences("wearpod_downloads", Context.MODE_PRIVATE)
        val array = JSONArray()
        episodes.forEach { ep ->
            val obj = JSONObject()
            obj.put("title", ep.title)
            obj.put("description", ep.description)
            obj.put("pubDate", ep.pubDate)
            obj.put("audioUrl", ep.audioUrl)
            obj.put("imageUrl", ep.imageUrl)
            obj.put("podcastTitle", ep.podcastTitle)
            obj.put("podcastImageUrl", ep.podcastImageUrl)
            obj.put("duration", ep.duration)
            array.put(obj)
        }
        prefs.edit().putString("downloaded_list", array.toString()).apply()
    }

    private fun loadDownloadedEpisodesState() {
        val prefs = getApplication<Application>().getSharedPreferences("wearpod_downloads", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("downloaded_list", null)
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<Episode>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(Episode(
                        title = obj.getString("title"),
                        description = obj.getString("description"),
                        pubDate = obj.getString("pubDate"),
                        audioUrl = obj.getString("audioUrl"),
                        imageUrl = obj.getString("imageUrl"),
                        podcastTitle = obj.getString("podcastTitle"),
                        podcastImageUrl = obj.getString("podcastImageUrl"),
                        duration = obj.getString("duration"),
                    ))
                }
                _downloadedEpisodes.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun downloadEpisode(episode: Episode) {
        if (_downloadedEpisodes.value.any { it.audioUrl == episode.audioUrl }) return
        
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
                    val file = java.io.File(getApplication<Application>().filesDir, filename)
                    
                    if (!file.exists()) {
                        URL(episode.audioUrl).openStream().use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    val updated = _downloadedEpisodes.value + episode
                    _downloadedEpisodes.value = updated
                    saveDownloadedEpisodesState(updated)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun deleteDownloadedEpisode(episode: Episode) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val filename = "episode_${episode.audioUrl.hashCode()}.mp3"
                val file = java.io.File(getApplication<Application>().filesDir, filename)
                if (file.exists()) {
                    file.delete()
                }
                val updated = _downloadedEpisodes.value.filter { it.audioUrl != episode.audioUrl }
                _downloadedEpisodes.value = updated
                saveDownloadedEpisodesState(updated)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
