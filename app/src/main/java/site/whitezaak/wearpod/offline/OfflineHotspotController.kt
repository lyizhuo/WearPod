package site.whitezaak.wearpod.offline

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * Local-Only Hotspot（LOHS）控制器。
 *
 * 已按 AOSP 行为校准（packages/modules/Wifi）：
 * - 能力**没有**公开查询接口（`isLocalOnlyHotspotSupported` 不存在），只能真跑一次看回调；
 * - 唯一的前台门禁是 `FrameworkFacade.isAppForeground` = `getUidImportance(uid) <= IMPORTANCE_VISIBLE`，
 *   所以调用方必须先成为前台服务（importance 125）；
 * - 同一 pid 重复请求会抛 `IllegalStateException`（不是错误码），因此这里只允许一个未释放的请求；
 * - 回错误码不存在 `ERROR_TOO_MANY_REQUESTS`，只有 NO_CHANNEL / GENERIC / INCOMPATIBLE_MODE / TETHERING_DISALLOWED。
 */
class OfflineHotspotController(private val context: Context) {

    sealed interface Result {
        data class Started(val ssid: String?, val passphrase: String?) : Result
        data object Unsupported : Result
        data class Failed(val reason: Reason) : Result
    }

    enum class Reason {
        WIFI_DISABLED,
        PERMISSION,
        INCOMPATIBLE,
        NO_CHANNEL,
        TETHERING_DISALLOWED,
        GENERIC,
        TIMEOUT,
        CONCURRENT,
        UNKNOWN,
    }

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var pending = false
    private var timeoutRunnable: Runnable? = null
    private var resultCallback: ((Result) -> Unit)? = null

    /** 热点被系统或用户停掉时回调（调用方应结束整个会话）。 */
    var onHotspotStopped: (() -> Unit)? = null

    val isWifiEnabled: Boolean
        get() = wifiManager?.isWifiEnabled == true

    fun start(timeoutMs: Long = DEFAULT_TIMEOUT_MS, onResult: (Result) -> Unit) {
        val manager = wifiManager ?: run {
            onResult(Result.Unsupported)
            return
        }
        if (reservation != null || pending) {
            onResult(Result.Failed(Reason.CONCURRENT))
            return
        }
        if (!manager.isWifiEnabled) {
            onResult(Result.Failed(Reason.WIFI_DISABLED))
            return
        }

        pending = true
        resultCallback = onResult
        val timeout = Runnable {
            if (!pending) return@Runnable
            pending = false
            val callback = resultCallback
            resultCallback = null
            callback?.invoke(Result.Failed(Reason.TIMEOUT))
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, timeoutMs)

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                finish(Result.Started(ssidOf(res.softApConfiguration), passphraseOf(res.softApConfiguration)))
            }

            override fun onStopped() {
                reservation = null
                pending = false
                onHotspotStopped?.invoke()
            }

            override fun onFailed(reason: Int) {
                reservation = null
                finish(Result.Failed(mapFailure(reason)))
            }
        }

        try {
            @Suppress("DEPRECATION")
            manager.startLocalOnlyHotspot(callback, mainHandler)
        } catch (_: SecurityException) {
            finish(Result.Failed(Reason.PERMISSION))
        } catch (_: IllegalStateException) {
            finish(Result.Failed(Reason.CONCURRENT))
        } catch (_: Exception) {
            finish(Result.Failed(Reason.UNKNOWN))
        }
    }

    /** 释放 reservation（必须在主线程调用）。 */
    fun stop() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        pending = false
        resultCallback = null
        runCatching { reservation?.close() }
        reservation = null
    }

    private fun finish(result: Result) {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        pending = false
        val callback = resultCallback
        resultCallback = null
        callback?.invoke(result)
    }

    private fun ssidOf(config: SoftApConfiguration?): String? {
        @Suppress("DEPRECATION")
        return config?.ssid?.takeIf { it.isNotEmpty() }
    }

    private fun passphraseOf(config: SoftApConfiguration?): String? {
        if (config == null) return null
        if (config.securityType == SoftApConfiguration.SECURITY_TYPE_OPEN) return null
        return config.passphrase?.takeIf { it.isNotEmpty() }
    }

    private fun mapFailure(reason: Int): Reason = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> Reason.NO_CHANNEL
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> Reason.INCOMPATIBLE
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> Reason.TETHERING_DISALLOWED
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> Reason.GENERIC
        else -> Reason.UNKNOWN
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
