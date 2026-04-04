import re

file_path = r"d:\200_Coding\AndroidStudio\WearPod3\app\src\main\java\site\whitezaak\wearpod\presentation\MainViewModel.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add repo and controller instances
content = content.replace(
    "class MainViewModel(application: Application) : AndroidViewModel(application) {",
    """import site.whitezaak.wearpod.data.FeedRepository\nimport site.whitezaak.wearpod.service.PlaybackController\n\nclass MainViewModel(application: Application) : AndroidViewModel(application) {\n    private val feedRepository = FeedRepository(application)\n    val playbackController = PlaybackController(application)"""
)

# 2. Replace flows for ExoPlayer
content = re.sub(
    r"private var controllerFuture: ListenableFuture<MediaController>\? = null.*?val mediaController = MutableStateFlow<MediaController\?>\(null\)\n\s+val isPlaying = MutableStateFlow\(false\)\n\s+val isBuffering = MutableStateFlow\(false\)",
    r"val isPlaying = playbackController.isPlaying\n    val isBuffering = playbackController.isBuffering",
    content, flags=re.DOTALL
)

# Replace remaining playback flows
content = re.sub(
    r"val currentPlayingEpisode = MutableStateFlow<Episode\?>\(null\)",
    r"val currentPlayingEpisode = playbackController.currentPlayingEpisode",
    content
)

content = re.sub(
    r"val currentPosition = MutableStateFlow\(0L\)\n\s+val currentDuration = MutableStateFlow\(0L\)",
    r"val currentPosition = playbackController.currentPosition\n    val currentDuration = playbackController.currentDuration",
    content
)

# 3. Replace activeConnections, withUrlInputStream
content = re.sub(
    r"private val activeConnections = mutableMapOf<RequestGroup, MutableSet<HttpURLConnection>>\(\)",
    r"",
    content
)

content = re.sub(
    r"private fun registerConnection.*?unregisterConnection\(group, connection\)\n\s+connection\.disconnect\(\)\n\s+\}\n\s+\}\n",
    r"",
    content, flags=re.DOTALL
)

# 4. Replace initializeController() entirely
content = re.sub(
    r"@UnstableApi\n\s+private fun initializeController\(\) \{.*?\n\s+fun onPlayerScreenEntered\(\)",
    r"private fun initializeController() {\n        playbackController.onPlayerConnected = {\n            restoreLastPlaybackToController()\n            pendingSeekPositionMs?.let {\n                playbackController.seekTo(it)\n                pendingSeekPositionMs = null\n            }\n            pendingEpisodeToPlay?.let {\n                pendingEpisodeToPlay = null\n                playEpisode(it)\n            }\n            syncPlayerScreenPlaybackState()\n        }\n    }\n\n    fun onPlayerScreenEntered()",
    content, flags=re.DOTALL
)

# 5. Fix references to mediaController.value
content = content.replace("mediaController.value?.isPlaying == true", "playbackController.isPlaying.value")
content = content.replace("mediaController.value?.isPlaying != true", "!playbackController.isPlaying.value")
content = content.replace("mediaController.value", "playbackController.mediaController")
content = content.replace("val controller = mediaController", "val controller = playbackController.mediaController")

# 6. Replace loadSubscriptions
content = re.sub(
    r"private fun loadSubscriptions\(\) \{.*?\n\s+\}\n\s+\}",
    r"""private fun loadSubscriptions() {
        viewModelScope.launch {
            val customId = getApplication<Application>().getSharedPreferences("wearpod_prefs", Context.MODE_PRIVATE)
                .getString("custom_opml_id", null)
            val loadedPodcasts = feedRepository.loadSubscriptions(customId)
            if (loadedPodcasts.isNotEmpty()) {
                _podcasts.value = loadedPodcasts
                if (isInboxScreenVisible) loadInboxEpisodes(force = true)
            } else if (isRefreshingInbox.value) {
                isRefreshingInbox.value = false
            }
        }
    }""",
    content, flags=re.DOTALL
)

# 7. Cancel connections
content = content.replace("cancelActiveConnections(RequestGroup.INBOX)", "feedRepository.cancelActiveConnections(\"INBOX\")")
content = content.replace("cancelActiveConnections(RequestGroup.FEED)", "feedRepository.cancelActiveConnections(\"FEED\")")

# 8. load Cached episodes state
content = re.sub(
    r"private fun loadCachedInboxEpisodesState.*?false\n\s+\}\n\s+\}",
    r"""private fun loadCachedInboxEpisodesState(ownerId: String? = _customOpmlId.value, clearIfMissing: Boolean = false): Boolean {
        val episodes = feedRepository.loadCachedInboxEpisodesState(ownerId)
        if (episodes == null) {
            if (clearIfMissing) {
                _inboxEpisodes.value = emptyList()
                resetVisibleInboxEpisodes()
            }
            return false
        }
        _inboxEpisodes.value = episodes
        resetVisibleInboxEpisodes()
        return true
    }""",
    content, flags=re.DOTALL
)

content = content.replace("saveInboxEpisodesState(allEpisodes)", "feedRepository.saveInboxEpisodesState(allEpisodes, _customOpmlId.value)")

# Replace shouldRefreshInbox
content = re.sub(
    r"private fun shouldRefreshInbox.*?INBOX_REFRESH_TTL_MS\n\s+\}",
    r"""private fun shouldRefreshInbox(ownerId: String? = _customOpmlId.value): Boolean {
        if (_inboxEpisodes.value.isEmpty()) return true
        val cachedAt = feedRepository.getInboxCacheTimestamp(ownerId)
        if (cachedAt <= 0L) return true
        return (System.currentTimeMillis() - cachedAt) >= INBOX_REFRESH_TTL_MS
    }""",
    content, flags=re.DOTALL
)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Refactoring applied.")
