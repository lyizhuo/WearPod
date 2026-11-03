package site.whitezaak.wearpod.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.settings.AppLanguageManager
import java.io.File

/**
 * 本地上传会话的持有者：前台服务（foregroundServiceType=dataSync）。
 *
 * 只做「局域网内嵌 HTTP 服务」——目标机型开不了 Local-Only Hotspot，手机与手表
 * 连同一个 Wi-Fi 即可。放前台服务的理由：用户操作手机期间 Activity 可能被回收，
 * 服务仍在跑；退出/超时也能可靠释放端口。
 *
 * 生命周期：Running 期间每 3 秒复查一次局域网地址（地址消失 → NoNetwork 并停服务；
 * 地址变化 → 在新地址上重绑）；导入成功 → Completed（服务继续跑，便于「继续导入」）；
 * 用户离开页面立即停止；系统熄屏不算离开，服务存活 2 分钟后停止；5 分钟硬超时兜底。
 * 超时停止时保留 Completed，其余状态切回 Idle 并发出 sessionEnded 让页面退出。
 */
class OfflineSessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { LocalSubscriptionStore(File(filesDir, OFFLINE_DIR_NAME)) }

    private var server: OfflineWebServer? = null
    private var hardStopRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var screenOffStopRunnable: Runnable? = null
    private var boundIp: String? = null
    private var pageUrl: String? = null
    private var ended = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> scheduleScreenOffStop()
                Intent.ACTION_SCREEN_ON -> cancelScreenOffStop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        when {
            server == null -> startSession()
            // 「继续导入」：结束页回到上传页，服务与端口保持不变，但硬超时要重新计时，
            // 否则第二次上传会继承第一次的 5 分钟截止时间。
            OfflineSessionBus.state.value is OfflineSessionState.Completed -> {
                OfflineSessionBus.state.value = OfflineSessionState.Running(pageUrl.orEmpty())
                scheduleHardStop()
                scheduleWatchdog()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ 对 dataSync 前台服务有 6h/24h 配额，这里用不到那么久，但必须实现。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdown(timeoutFinalState(), notifyEnded = true)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun startSession() {
        ended = false
        startForeground(NOTIFICATION_ID, buildNotification())
        OfflineSessionBus.state.value = OfflineSessionState.Starting
        if (!isInteractive()) {
            scheduleScreenOffStop()
        }

        val ip = LocalIpResolver.resolve(this)
        if (ip == null) {
            Log.i(TAG, "startSession: no LAN address -> NoNetwork")
            shutdown(OfflineSessionState.NoNetwork)
            return
        }
        startServer(ip)
    }

    private fun startServer(ip: String) {
        val api = OfflineApi(
            store = store,
            assets = AssetWebAssets(assets),
            languageTag = AppLanguageManager.getSelectedLanguageTag(this),
        ) { change ->
            OfflineSessionBus.storeChanged.tryEmit(Unit)
            // 导入与删除都进结束页，并如实带出数量
            val summary = when (change) {
                is PageChange.Imported -> ChangeSummary(added = change.added, skipped = change.skipped)
                is PageChange.Deleted -> ChangeSummary(deleted = change.deleted)
            }
            OfflineSessionBus.state.value = OfflineSessionState.Completed(summary)
        }
        for (port in PORT_RANGE) {
            val candidate = OfflineWebServer(
                bindAddress = ip,
                port = port,
                api = api,
                tempDir = File(cacheDir, TEMP_DIR_NAME),
            )
            if (candidate.startServer()) {
                server = candidate
                boundIp = ip
                val url = QrPayloads.pageUrl(ip, port)
                pageUrl = url
                OfflineSessionBus.state.value = OfflineSessionState.Running(url)
                Log.i(TAG, "server running at $url")
                scheduleHardStop()
                scheduleWatchdog()
                return
            }
            candidate.lastError?.let { Log.w(TAG, "bind failed on $ip:$port", it) }
        }
        Log.w(TAG, "no port in $PORT_RANGE bindable on $ip")
        shutdown(OfflineSessionState.ServerFailed)
    }

    /** 复查网络：地址消失就停（Completed 保留状态），地址变了就在新地址上重绑。 */
    private fun checkNetwork() {
        // 结束页仍可能「继续导入」，服务与端口都还活着，因此 Completed 也要继续跟踪网络。
        val state = OfflineSessionBus.state.value
        if (state !is OfflineSessionState.Running && state !is OfflineSessionState.Completed) return
        val ip = LocalIpResolver.resolve(this)
        when {
            ip == null -> {
                // Running 掉网 → 引导页并停服务；Completed 掉网 → 保留结束页状态。
                val finalState =
                    if (state is OfflineSessionState.Completed) state else OfflineSessionState.NoNetwork
                Log.i(TAG, "network lost -> $finalState")
                shutdown(finalState)
            }

            ip != boundIp -> {
                Log.i(TAG, "LAN address changed $boundIp -> $ip, rebinding")
                server?.stop()
                server = null
                startServer(ip)
            }

            else -> scheduleWatchdog()
        }
    }

    private fun scheduleWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { checkNetwork() }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, WATCHDOG_INTERVAL_MS)
    }

    private fun shutdown(
        finalState: OfflineSessionState = OfflineSessionState.Idle,
        notifyEnded: Boolean = false,
    ) {
        if (ended) return
        ended = true
        screenOffStopRunnable?.let { handler.removeCallbacks(it) }
        screenOffStopRunnable = null
        hardStopRunnable?.let { handler.removeCallbacks(it) }
        hardStopRunnable = null
        handler.removeCallbacksAndMessages(null)
        watchdogRunnable = null
        server?.stop()
        server = null
        boundIp = null
        pageUrl = null
        OfflineSessionBus.state.value = finalState
        if (notifyEnded && finalState !is OfflineSessionState.Completed) {
            OfflineSessionBus.sessionEnded.tryEmit(Unit)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun timeoutFinalState(): OfflineSessionState =
        OfflineSessionBus.state.value.let {
            if (it is OfflineSessionState.Completed) it else OfflineSessionState.Idle
        }

    /** 硬超时：停服务，但不把「已完成」抹成「已停止」；超时退出页面。 */
    private fun scheduleHardStop() {
        hardStopRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            shutdown(timeoutFinalState(), notifyEnded = true)
        }
        hardStopRunnable = runnable
        handler.postDelayed(runnable, HARD_TIMEOUT_MS)
    }

    private fun isInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive != false

    /** 熄屏不是用户退出：给 2 分钟宽限，期间用户点亮屏幕可继续。 */
    private fun scheduleScreenOffStop() {
        cancelScreenOffStop()
        val runnable = Runnable {
            Log.i(TAG, "screen off timeout -> stop")
            shutdown(timeoutFinalState(), notifyEnded = true)
        }
        screenOffStopRunnable = runnable
        handler.postDelayed(runnable, SCREEN_OFF_TIMEOUT_MS)
    }

    private fun cancelScreenOffStop() {
        screenOffStopRunnable?.let { handler.removeCallbacks(it) }
        screenOffStopRunnable = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.offline_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.offline_notification_text)
                    setShowBadge(false)
                }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setContentTitle(getString(R.string.offline_notification_title))
            .setContentText(getString(R.string.offline_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "site.whitezaak.wearpod.offline.action.STOP"

        private const val TAG = "WearPodOffline"
        private const val CHANNEL_ID = "wearpod_offline_upload"
        private const val NOTIFICATION_ID = 4711
        private const val OFFLINE_DIR_NAME = "offline"
        private const val TEMP_DIR_NAME = "offline-http"
        private const val HARD_TIMEOUT_MS = 5 * 60 * 1000L
        private const val SCREEN_OFF_TIMEOUT_MS = 2 * 60 * 1000L
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private val PORT_RANGE = 8080..8090

        fun start(context: Context) {
            // 后台被拒（ForegroundServiceStartNotAllowedException）不能杀进程：
            // 页面侧的重试循环可能已经不在前台。
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, OfflineSessionService::class.java),
                )
            }.onFailure { Log.w(TAG, "startForegroundService rejected", it) }
        }

        fun stop(context: Context) {
            // 服务已被超时停掉时，后台 startService 会抛 BackgroundServiceStartNotAllowedException。
            runCatching {
                context.startService(
                    Intent(context, OfflineSessionService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure { Log.w(TAG, "startService(ACTION_STOP) rejected", it) }
        }
    }
}
