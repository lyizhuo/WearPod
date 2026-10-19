package site.whitezaak.wearpod.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import site.whitezaak.wearpod.R
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
 * 硬超时 5 分钟只覆盖 Running。
 */
class OfflineSessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { LocalSubscriptionStore(File(filesDir, OFFLINE_DIR_NAME)) }

    private var server: OfflineWebServer? = null
    private var hardStopRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var boundIp: String? = null
    private var pageUrl: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        when {
            server == null -> startSession()
            // 「继续导入」：结束页回到上传页，服务与端口保持不变
            OfflineSessionBus.state.value is OfflineSessionState.Completed -> {
                OfflineSessionBus.state.value = OfflineSessionState.Running(pageUrl.orEmpty())
                scheduleWatchdog()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ 对 dataSync 前台服务有 6h/24h 配额，这里用不到那么久，但必须实现。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdown()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun startSession() {
        startForeground(NOTIFICATION_ID, buildNotification())
        OfflineSessionBus.state.value = OfflineSessionState.Starting

        val ip = LocalIpResolver.resolve(this)
        if (ip == null) {
            Log.i(TAG, "startSession: no LAN address -> NoNetwork")
            shutdown(OfflineSessionState.NoNetwork)
            return
        }
        startServer(ip)
    }

    private fun startServer(ip: String) {
        val api = OfflineApi(store, AssetWebAssets(assets)) { result ->
            OfflineSessionBus.storeChanged.tryEmit(Unit)
            // 导入成功 → 结束页；删除只在页面上生效，不进结束页
            if (result != null) {
                OfflineSessionBus.state.value = OfflineSessionState.Completed(result)
            }
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
        }
        Log.w(TAG, "no free port in $PORT_RANGE")
        shutdown(OfflineSessionState.ServerFailed)
    }

    /** Running 期间复查网络：地址消失就转 NoNetwork，地址变了就在新地址上重绑。 */
    private fun checkNetwork() {
        if (OfflineSessionBus.state.value !is OfflineSessionState.Running) return
        val ip = LocalIpResolver.resolve(this)
        when {
            ip == null -> {
                Log.i(TAG, "network lost -> NoNetwork")
                shutdown(OfflineSessionState.NoNetwork)
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

    private fun shutdown(finalState: OfflineSessionState = OfflineSessionState.Idle) {
        hardStopRunnable?.let { handler.removeCallbacks(it) }
        hardStopRunnable = null
        handler.removeCallbacksAndMessages(null)
        watchdogRunnable = null
        server?.stop()
        server = null
        boundIp = null
        pageUrl = null
        OfflineSessionBus.state.value = finalState
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 硬超时：停服务，但不把「已完成」抹成「已停止」。 */
    private fun scheduleHardStop() {
        hardStopRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            val current = OfflineSessionBus.state.value
            shutdown(if (current is OfflineSessionState.Completed) current else OfflineSessionState.Idle)
        }
        hardStopRunnable = runnable
        handler.postDelayed(runnable, HARD_TIMEOUT_MS)
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
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OfflineSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.wearpod_icon)
            .setContentTitle(getString(R.string.offline_notification_title))
            .setContentText(getString(R.string.offline_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.offline_stop), stopIntent)
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
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private val PORT_RANGE = 8080..8090

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OfflineSessionService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OfflineSessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
