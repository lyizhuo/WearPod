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
 * 硬超时 5 分钟只覆盖 Running 阶段；没有局域网地址时立即停服务，重试由 UI 轮询完成。
 */
class OfflineSessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { LocalSubscriptionStore(File(filesDir, OFFLINE_DIR_NAME)) }

    private var server: OfflineWebServer? = null
    private var hardStopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (server == null) {
            startSession()
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

        val ip = LocalIpResolver.resolve()
        if (ip == null) {
            // 未连 Wi-Fi / 只有蜂窝：不留前台服务空转，联网后由 UI 重新拉起。
            shutdown(OfflineSessionState.NoNetwork)
            return
        }
        startServer(ip)
    }

    private fun startServer(ip: String) {
        val api = OfflineApi(store, AssetWebAssets(assets)) { result ->
            OfflineSessionBus.storeChanged.tryEmit(Unit)
            val current = OfflineSessionBus.state.value
            if (current is OfflineSessionState.Running && result != null) {
                OfflineSessionBus.state.value = current.copy(lastImport = result)
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
                OfflineSessionBus.state.value = OfflineSessionState.Running(QrPayloads.pageUrl(ip, port))
                scheduleHardStop()
                return
            }
        }
        shutdown(OfflineSessionState.ServerFailed)
    }

    private fun shutdown(finalState: OfflineSessionState = OfflineSessionState.Idle) {
        hardStopRunnable?.let { handler.removeCallbacks(it) }
        hardStopRunnable = null
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        OfflineSessionBus.state.value = finalState
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleHardStop() {
        val runnable = Runnable { shutdown() }
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

        private const val CHANNEL_ID = "wearpod_offline_upload"
        private const val NOTIFICATION_ID = 4711
        private const val OFFLINE_DIR_NAME = "offline"
        private const val TEMP_DIR_NAME = "offline-http"
        private const val HARD_TIMEOUT_MS = 5 * 60 * 1000L
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
