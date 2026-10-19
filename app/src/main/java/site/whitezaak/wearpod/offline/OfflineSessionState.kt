package site.whitezaak.wearpod.offline

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 离线上传会话（热点 + 本地 HTTP 服务）的对外状态。 */
sealed interface OfflineSessionState {
    data object Idle : OfflineSessionState
    data object Starting : OfflineSessionState
    data class Running(
        /** null = 热点没起来，退化成「手机与手表同一 Wi-Fi」模式。 */
        val ssid: String?,
        val passphrase: String?,
        val pageUrl: String,
        val lastImport: ImportResult? = null,
    ) : OfflineSessionState
    data class Error(val kind: ErrorKind) : OfflineSessionState
}

enum class ErrorKind { PERMISSION_DENIED, WIFI_DISABLED, HOTSPOT_FAILED, SERVER_FAILED, NO_ADDRESS }

/**
 * 会话状态与「本地订阅被改动」事件的进程内总线。
 *
 * 会话由前台服务持有（LOHS 有 importance ≤ IMPORTANCE_VISIBLE 的硬门禁），
 * App 侧只读状态、不持有热点，避免 Activity 被回收时把热点一起带走。
 */
object OfflineSessionBus {
    val state = MutableStateFlow<OfflineSessionState>(OfflineSessionState.Idle)

    /** 页面成功导入/删除后发一次；App 端收到后从落盘文件重读订阅。 */
    val storeChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}
