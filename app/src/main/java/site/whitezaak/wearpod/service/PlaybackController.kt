package site.whitezaak.wearpod.service

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import site.whitezaak.wearpod.domain.Episode
import android.net.Uri

class PlaybackController(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var _mediaController: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _currentDuration = MutableStateFlow(0L)
    val currentDuration: StateFlow<Long> = _currentDuration.asStateFlow()

    private val _currentPlayingEpisode = MutableStateFlow<Episode?>(null)
    val currentPlayingEpisode: StateFlow<Episode?> = _currentPlayingEpisode.asStateFlow()

    var onPlayerConnected: (() -> Unit)? = null
    var onPositionChanged: ((Long) -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null
    var onMediaItemTransition: ((String) -> Unit)? = null

    init {
        initializeController()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializeController() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                _mediaController = controller

                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                        if (playbackState == Player.STATE_READY) {
                            val duration = controller.duration.coerceAtLeast(0L)
                            if (duration > 0L) {
                                _currentDuration.value = duration
                            }
                            _currentPosition.value = controller.currentPosition.coerceAtLeast(0L)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            onPlaybackEnded?.invoke()
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val mediaId = mediaItem?.mediaId
                        if (!mediaId.isNullOrEmpty()) {
                            onMediaItemTransition?.invoke(mediaId)
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("WearPod", "ExoPlayer Error: ${error.message}", error)
                        _isPlaying.value = false
                        _isBuffering.value = false
                    }
                })
                onPlayerConnected?.invoke()
            } catch (e: Exception) {
                Log.e("WearPod", "Failed to initialize MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    val mediaController: MediaController?
        get() = _mediaController

    fun getControllerPosition(): Long = _mediaController?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun getControllerDuration(): Long = _mediaController?.duration?.coerceAtLeast(0L) ?: 0L

    fun setMediaItem(episode: Episode, fileUri: String) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(episode.audioUrl)
            .setUri(fileUri)
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

        _currentPlayingEpisode.value = episode
        _mediaController?.setMediaItem(mediaItem)
        _mediaController?.prepare()
    }

    fun play() {
        _mediaController?.play()
    }

    fun pause() {
        _mediaController?.pause()
    }

    fun syncProgress() {
        _mediaController?.let {
            _currentPosition.value = it.currentPosition.coerceAtLeast(0L)
            _currentDuration.value = it.duration.coerceAtLeast(0L)
        }
    }

    fun clearMediaItem() {
        _mediaController?.pause()
        _currentPlayingEpisode.value = null
        _isPlaying.value = false
    }

    fun hydrateState(episode: Episode, positionMs: Long) {
        _currentPlayingEpisode.value = episode
        _currentPosition.value = positionMs
    }

    fun updateProgressSnapshot(positionMs: Long? = null, durationMs: Long? = null) {
        positionMs?.let {
            _currentPosition.value = it.coerceAtLeast(0L)
        }
        durationMs?.let {
            _currentDuration.value = it.coerceAtLeast(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        _mediaController?.seekTo(positionMs)
        _currentPosition.value = positionMs
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        _mediaController = null
    }
}
