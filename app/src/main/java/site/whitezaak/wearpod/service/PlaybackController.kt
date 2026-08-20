package site.whitezaak.wearpod.service

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.Controller
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
    private var reconnectScheduled = false
    private var reconnectAttempted = false

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
    var onPeriodicPositionUpdate: ((Long) -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null
    var onMediaItemTransition: ((String) -> Unit)? = null
    var onPlayerError: ((error: androidx.media3.common.PlaybackException) -> Unit)? = null

    private var pendingInitialSeekMs: Long = -1L

    init {
        initializeController()
    }

    /**
     * 确保有一个可用的 MediaController。连接已断开（例如播放服务被系统回收）
     * 且当前没有正在进行的连接尝试时，触发重建。供播放/跳转等入口在 controller
     * 为空时调用，避免 pending 播放请求永远得不到执行。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun ensureConnected() {
        if (_mediaController != null) return
        val future = controllerFuture
        if (future != null && !future.isDone) return
        reconnectAttempted = false
        reconnectScheduled = false
        initializeController()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializeController() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                _mediaController = controller
                reconnectAttempted = false

                controller.addListener(object : Player.Listener {
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
                            if (pendingInitialSeekMs > 0L) {
                                val seekTarget = pendingInitialSeekMs.coerceAtMost(duration.coerceAtLeast(0L))
                                pendingInitialSeekMs = -1L
                                controller.seekTo(seekTarget)
                                _currentPosition.value = seekTarget
                            } else {
                                _currentPosition.value = controller.currentPosition.coerceAtLeast(0L)
                            }
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
                        onPlayerError?.invoke(error)
                    }
                })

                // 监听会话断开（服务被回收/重启），自动重建 controller。
                controller.addListener(object : Controller.Listener {
                    override fun onDisconnected(controller: Controller) {
                        handleControllerDisconnected()
                    }
                })

                // 事件驱动的进度更新：替代 UI 侧轮询，播放/暂停/跳转都由 MediaController
                // 周期位置更新推送（约 1s 一次，暂停时值不变由 StateFlow 去重），显著降低 CPU/电池开销。
                controller.setPeriodicPositionUpdateEnabled(true)
                controller.registerPeriodicPositionUpdate(
                    MoreExecutors.directExecutor(),
                    java.util.function.Consumer { positionMs ->
                        val position = positionMs.coerceAtLeast(0L)
                        _currentPosition.value = position
                        onPeriodicPositionUpdate?.invoke(position)
                    }
                )

                onPlayerConnected?.invoke()
            } catch (e: Exception) {
                Log.e("WearPod", "Failed to initialize MediaController", e)
                handleControllerDisconnected()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun handleControllerDisconnected() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        _mediaController?.release()
        _mediaController = null
        _isPlaying.value = false
        _isBuffering.value = false
        // 延时重建，给播放服务（前台服务常驻）留出重启窗口；只自动重试一次，避免疯狂循环。
        if (!reconnectAttempted) {
            reconnectAttempted = true
            Handler(Looper.getMainLooper()).postDelayed({
                reconnectScheduled = false
                initializeController()
            }, 1_000L)
        }
    }

    val mediaController: MediaController?
        get() = _mediaController

    fun getControllerPosition(): Long = _mediaController?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun getControllerDuration(): Long = _mediaController?.duration?.coerceAtLeast(0L) ?: 0L

    fun setMediaItem(episode: Episode, fileUri: String, startPositionMs: Long = 0L) {
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
        pendingInitialSeekMs = if (startPositionMs > 0L) startPositionMs else -1L
        _mediaController?.setMediaItem(mediaItem)
        _mediaController?.prepare()
    }

    fun play() {
        _mediaController?.play()
    }

    fun pause() {
        _mediaController?.pause()
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
        pendingInitialSeekMs = -1L
        _mediaController?.seekTo(positionMs)
        _currentPosition.value = positionMs
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        _mediaController = null
    }
}
