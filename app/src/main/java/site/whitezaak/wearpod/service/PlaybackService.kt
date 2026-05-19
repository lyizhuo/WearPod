package site.whitezaak.wearpod.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import site.whitezaak.wearpod.presentation.MainActivity
import site.whitezaak.wearpod.util.DownloadFileManager
import android.net.Uri
import java.io.File
import org.json.JSONObject
import android.content.Context
import com.google.common.util.concurrent.Futures

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    private companion object {
        const val STREAM_CACHE_SIZE_BYTES = 64L * 1024L * 1024L
        const val PREFS_PLAYBACK = "wearpod_playback"
        const val KEY_LAST_EPISODE = "last_episode"
        const val KEY_LAST_POSITION = "last_position_ms"

        @Volatile
        private var sharedStreamCache: SimpleCache? = null

        private fun getOrCreateStreamCache(service: PlaybackService): SimpleCache {
            return synchronized(this) {
                sharedStreamCache ?: SimpleCache(
                    File(service.cacheDir, "stream_media_cache"),
                    LeastRecentlyUsedCacheEvictor(STREAM_CACHE_SIZE_BYTES),
                    StandaloneDatabaseProvider(service)
                ).also { sharedStreamCache = it }
            }
        }

        private fun releaseStreamCache() {
            synchronized(this) {
                sharedStreamCache?.release()
                sharedStreamCache = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val streamCache = getOrCreateStreamCache(this)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DataSource.Factory {
            object : DataSource {
                private var fileSrc: FileDataSource? = null
                private var cacheSrc: CacheDataSource? = null

                override fun addTransferListener(listener: TransferListener) {}

                override fun open(dataSpec: DataSpec): Long {
                    val isFile = dataSpec.uri.scheme?.let { it == "file" } ?: true
                    return if (isFile) {
                        FileDataSource().also { fileSrc = it }.open(dataSpec)
                    } else {
                        CacheDataSource.Factory()
                            .setCache(streamCache)
                            .setUpstreamDataSourceFactory(httpDataSourceFactory)
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                            .createDataSource()
                            .also { cacheSrc = it as CacheDataSource }
                            .open(dataSpec)
                    }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    (fileSrc ?: cacheSrc)!!.read(buffer, offset, length)

                override fun getUri(): Uri? = (fileSrc ?: cacheSrc)?.uri

                override fun close() {
                    (fileSrc ?: cacheSrc)?.close()
                }
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 45_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15000)
            .setSeekForwardIncrementMs(15000)
            .setWakeMode(C.WAKE_MODE_NONE)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                val isLocal = uri?.scheme == "file" || uri?.scheme == null
                player.setWakeMode(
                    if (isLocal) C.WAKE_MODE_NONE else C.WAKE_MODE_NETWORK
                )
            }
        })
            
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(buildPlayerPendingIntent())
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val prefs = getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
                    val episodeJson = prefs.getString(KEY_LAST_EPISODE, null)
                    val positionMs = prefs.getLong(KEY_LAST_POSITION, 0L).coerceAtLeast(0L)

                    if (episodeJson != null && positionMs > 0L) {
                        try {
                            val json = JSONObject(episodeJson)
                            val audioUrl = json.getString("audioUrl")
                            val title = json.getString("title")
                            val podcastTitle = json.optString("podcastTitle")
                            val imageUrl = json.optString("imageUrl")
                            val podcastImageUrl = json.optString("podcastImageUrl")

                            val resolvedUri = resolvePlayableUri(audioUrl)
                            val artworkUri = (imageUrl.ifBlank { podcastImageUrl })
                                .takeIf { it.isNotBlank() }
                                ?.let(Uri::parse)

                            val mediaItem = MediaItem.Builder()
                                .setMediaId(audioUrl)
                                .setUri(resolvedUri)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(title)
                                        .setArtist(podcastTitle)
                                        .setArtworkUri(artworkUri)
                                        .setIsPlayable(true)
                                        .build()
                                )
                                .build()

                            return Futures.immediateFuture(
                                MediaSession.MediaItemsWithStartPosition(
                                    listOf(mediaItem),
                                    0,
                                    positionMs
                                )
                            )
                        } catch (e: Exception) {
                            // Fall through to default empty result
                        }
                    }
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                    )
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        releaseStreamCache()
        super.onDestroy()
    }

    private fun buildPlayerPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        }
        return PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun resolvePlayableUri(audioUrl: String): String {
        val localFile = DownloadFileManager.fileForAudioUrl(this, audioUrl)
        return if (localFile.exists()) {
            Uri.fromFile(localFile).toString()
        } else {
            audioUrl
        }
    }
}
