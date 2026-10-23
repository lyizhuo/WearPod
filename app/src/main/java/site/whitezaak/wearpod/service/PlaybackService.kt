package site.whitezaak.wearpod.service

import android.app.Notification
import android.app.PendingIntent
import android.os.Bundle
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
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
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.presentation.MainActivity
import site.whitezaak.wearpod.util.DownloadFileManager
import site.whitezaak.wearpod.util.DurationUtils
import site.whitezaak.wearpod.util.isTrueWearOs
import android.net.Uri
import java.io.File
import org.json.JSONObject
import android.content.Context
import com.google.common.util.concurrent.Futures
import com.google.common.collect.ImmutableList

@UnstableApi
class PlaybackService : MediaSessionService() {

    /**
     * 媒体通知 Provider（厂商能力全部收敛在这里）：
     * - 公共：委托 [DefaultMediaNotificationProvider] 保留 Media3 默认通知
     *   （MediaStyle/媒体按钮/封面加载），只做三处增强：
     *   category=transport、单色小图标、OPPO 私有 extra。
     * - OPPO 圆表 Indicator（开放平台文档 id=11286 §接入条件）：
     *   媒体通知 extras 追加 `show_heytap_indicator=true`，后台播放显示为表盘指示器，
     *   其它厂商忽略该 extra，无副作用。
     * - 小米/非 Wear ROM：媒体通知标 ongoing 并挂 Wear ongoing-activity 扩展
     *   （`android.wearable.ongoingactivities.EXTENSIONS`），系统显示"持续活动"卡片。
     *   真 Wear OS 保持 Media3 原有非 ongoing 行为，避免改变 SwipeDismissable 语义。
     */
    private val mediaNotificationProvider: MediaNotification.Provider by lazy {
        WearPodMediaNotificationProvider(this)
    }
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
                            // 恢复播放时同样写入时长：系统媒体面板无需等播放器解析即可显示总长。
                            val durationMs = DurationUtils.parseDurationToMs(json.optString("duration"))

                            val metadataBuilder = MediaMetadata.Builder()
                                .setTitle(title)
                                .setArtist(podcastTitle)
                                .setAlbumTitle(podcastTitle)
                                .setArtworkUri(artworkUri)
                                .setIsPlayable(true)
                            if (durationMs > 0L) {
                                metadataBuilder.setDurationMs(durationMs)
                            }

                            val mediaItem = MediaItem.Builder()
                                .setMediaId(audioUrl)
                                .setUri(resolvedUri)
                                .setMediaMetadata(metadataBuilder.build())
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

        // OPPO 圆表 Indicator / 小米持续活动：统一由 Provider 处理（须在 onCreate 返回前设置）。
        setMediaNotificationProvider(mediaNotificationProvider)
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
        // 服务销毁后 Media3 不会主动撤回通知；ongoing/Indicator 残留会一直挂在常驻区，
        // 因此显式撤掉默认媒体通知（ID 与 Provider 委托的默认值一致）。
        NotificationManagerCompat.from(this)
            .cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
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

/**
 * 媒体通知 Provider：在 Media3 默认媒体通知上叠加厂商能力。
 *
 * 委托 [DefaultMediaNotificationProvider] 保留 Media3 默认媒体通知
 * （MediaStyle/媒体按钮/封面加载），只就地改写已构建的通知，零重建：
 * 1. 通知图标换成 App 自己的单色剪影（部分 OEM 会丢弃非自家资源的小图标）。
 * 2. `Notification.CATEGORY_TRANSPORT`：显式声明类别，避免 category=null 时
 *    OEM 媒体面板/指示灯判定不到媒体会话。
 * 3. OPPO 圆表 Indicator：extras 追加 `show_heytap_indicator=true`，
 *    仅 OPPO/HeyTap 系统读取该私有字段，其余厂商忽略。
 * 4. 非真 Wear OS（小米/OPPO 等全水桶安卓表）：标 ongoing 并挂 Wear
 *    ongoing-activity 扩展，系统据此渲染"持续活动"卡片并可回跳播放页。
 *    真 Wear OS 保持 Media3 原有的非 ongoing 语义，避免干扰 SwipeDismissable。
 */
@UnstableApi
private class WearPodMediaNotificationProvider(
    private val context: Context,
) : MediaNotification.Provider {

    private val delegate = DefaultMediaNotificationProvider(context).apply {
        setSmallIcon(R.drawable.ic_notification_podcast)
    }

    // 机型能力只与设备有关：构造时判定一次，避免每次通知刷新都查 PackageManager。
    private val isTrueWearOs = context.isTrueWearOs()

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val base = delegate.createNotification(
            mediaSession,
            mediaButtonPreferences,
            actionFactory,
            onNotificationChangedCallback,
        )
        // Media3 默认媒体通知的 extras 是可变 Bundle：直接改写，保留 MediaStyle/媒体按钮/封面。
        base.notification.category = Notification.CATEGORY_TRANSPORT
        base.notification.extras?.putBoolean(EXTRA_OPPO_HEYTAP_INDICATOR, true)

        if (!isTrueWearOs) {
            base.notification.flags = base.notification.flags or Notification.FLAG_ONGOING_EVENT
            attachWearOngoingActivity(mediaSession, base)
        }
        return base
    }

    /**
     * 把 Media3 的媒体通知升级为 Wear ongoing activity。
     *
     * [androidx.wear.ongoing.OngoingActivity] 只接受 [NotificationCompat.Builder] 作为序列化出口，
     * 而 Media3 交付的是已 build 的 [Notification]。因此用一个"暂存 builder"承载序列化：
     * 借它把 ongoing-activity 数据写成 extras Bundle，再整体搬进真正的媒体通知；暂存 builder
     * 本身永不 post，所以 channel 只需合法占位即可。
     */
    private fun attachWearOngoingActivity(mediaSession: MediaSession, base: MediaNotification) {
        val touchIntent = mediaSession.sessionActivity
            ?: base.notification.contentIntent
            ?: return
        val title = base.notification.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
        val staging = NotificationCompat.Builder(
            context,
            base.notification.channelId ?: DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification_podcast)
            .setContentTitle(title)
            .setContentIntent(touchIntent)
            .setOngoing(true)

        OngoingActivity.Builder(context, base.notificationId, staging)
            .setStaticIcon(R.drawable.ic_notification_podcast)
            .setTouchIntent(touchIntent)
            .apply { if (title.isNotEmpty()) setTitle(title) }
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoingActivityId(base.notificationId)
            .build()
            .apply(context)

        val ongoingBundle = staging.extras?.getBundle(EXTRA_WEAR_ONGOING) ?: return
        val destinationExtras = base.notification.extras
            ?: Bundle().also { base.notification.extras = it }
        destinationExtras.putBundle(EXTRA_WEAR_ONGOING, ongoingBundle)
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    companion object {
        const val EXTRA_OPPO_HEYTAP_INDICATOR = "show_heytap_indicator"

        /** 与 Google/Xiaomi 系统实现一致的 ongoing-activity 扩展 key。 */
        const val EXTRA_WEAR_ONGOING = "android.wearable.ongoingactivities.EXTENSIONS"
    }
}
