package site.whitezaak.wearpod.offline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import site.whitezaak.wearpod.R
import java.io.File

/**
 * 离线上传会话的唯一持有者：前台服务（foregroundServiceType=dataSync）。
 *
 * 为什么必须是前台服务：AOSP 的 LOHS 入口会检查
 * `FrameworkFacade.isAppForeground` = `getUidImportance(uid) <= IMPORTANCE_VISIBLE`，
 * 失败直接返回 ERROR_INCOMPATIBLE_MODE；而且 reservation 绑进程存活（进程死即拆 AP）。
 * 把热点放在 ViewModel/Activity 里在 AOSP 层面就跑不通。
 *
 * 硬超时 ≤5 分钟：AP 空载功耗实测 270–333 mW（手表推断 1 小时 4%–14% 电量），用完必须立刻释放。
 */
class OfflineSessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { LocalSubscriptionStore(File(filesDir, OFFLINE_DIR_NAME)) }

    private var hotspot: OfflineHotspotController? = null
    private var server: OfflineWebServer? = null
    private var hardStopRunnable: Runnable? = null
    private var addressAttempts = 0

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
        hotspot?.stop()
        hotspot = null
        super.onDestroy()
    }

    private fun startSession() {
        // 先成为前台服务，再申请热点：LOHS 的前台门禁按 uid importance 判定。
        startForeground(NOTIFICATION_ID, buildNotification())
        OfflineSessionBus.state.value = OfflineSessionState.Starting

        if (!hasNearbyPermission()) {
            OfflineSessionBus.state.value = OfflineSessionState.Error(ErrorKind.PERMISSION_DENIED)
            shutdown()
            return
        }

        scheduleHardStop()
        val controller = OfflineHotspotController(this)
        controller.onHotspotStopped = { shutdown() }
        hotspot = controller
        controller.start { result -> onHotspotResult(result) }
    }

    private fun onHotspotResult(result: OfflineHotspotController.Result) {
        when (result) {
            is OfflineHotspotController.Result.Started -> awaitAccessPointAddress(result)
            OfflineHotspotController.Result.Unsupported -> fallbackToSharedNetwork(ErrorKind.HOTSPOT_FAILED)
            is OfflineHotspotController.Result.Failed -> when (result.reason) {
                OfflineHotspotController.Reason.PERMISSION -> {
                    OfflineSessionBus.state.value = OfflineSessionState.Error(ErrorKind.PERMISSION_DENIED)
                    shutdown()
                }

                OfflineHotspotController.Reason.WIFI_DISABLED -> {
                    // Wear OS 会在有蓝牙省电时关掉 Wi-Fi，且没有公开 API 让应用自己打开。
                    OfflineSessionBus.state.value = OfflineSessionState.Error(ErrorKind.WIFI_DISABLED)
                    shutdown()
                }

                else -> fallbackToSharedNetwork(ErrorKind.HOTSPOT_FAILED)
            }
        }
    }

    private fun awaitAccessPointAddress(started: OfflineHotspotController.Result.Started) {
        val candidates = LocalIpResolver.enumerate()
        val accessPointIp = LocalIpResolver.pickAccessPoint(candidates)
        if (accessPointIp != null) {
            startServer(started.ssid, started.passphrase, accessPointIp)
            return
        }
        addressAttempts++
        if (addressAttempts < ADDRESS_ATTEMPTS) {
            handler.postDelayed({ awaitAccessPointAddress(started) }, ADDRESS_RETRY_MS)
            return
        }
        // 热点口命名不匹配既有正则时的兜底：仍给出一个可达地址，页面照常可用。
        val anyIp = LocalIpResolver.pick(candidates)
        if (anyIp != null) {
            startServer(started.ssid, started.passphrase, anyIp)
        } else {
            OfflineSessionBus.state.value = OfflineSessionState.Error(ErrorKind.NO_ADDRESS)
            shutdown()
        }
    }

    private fun fallbackToSharedNetwork(kind: ErrorKind) {
        val ip = LocalIpResolver.pick(LocalIpResolver.enumerate())
        if (ip == null) {
            OfflineSessionBus.state.value = OfflineSessionState.Error(kind)
            shutdown()
            return
        }
        startServer(ssid = null, passphrase = null, ip = ip)
    }

    private fun startServer(ssid: String?, passphrase: String?, ip: String) {
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
                OfflineSessionBus.state.value =
                    OfflineSessionState.Running(ssid, passphrase, QrPayloads.pageUrl(ip, port))
                return
            }
        }
        OfflineSessionBus.state.value = OfflineSessionState.Error(ErrorKind.SERVER_FAILED)
        shutdown()
    }

    private fun shutdown() {
        hardStopRunnable?.let { handler.removeCallbacks(it) }
        hardStopRunnable = null
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        hotspot?.stop()
        hotspot = null
        OfflineSessionBus.state.value = OfflineSessionState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleHardStop() {
        val runnable = Runnable { shutdown() }
        hardStopRunnable = runnable
        handler.postDelayed(runnable, HARD_TIMEOUT_MS)
    }

    private fun hasNearbyPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
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
        private const val ADDRESS_ATTEMPTS = 10
        private const val ADDRESS_RETRY_MS = 300L
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
