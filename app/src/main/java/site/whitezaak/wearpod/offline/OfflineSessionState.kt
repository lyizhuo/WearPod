package site.whitezaak.wearpod.offline

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 本地上传会话（局域网内嵌 HTTP 服务）的对外状态。 */
sealed interface OfflineSessionState {
    data object Idle : OfflineSessionState
    data object Starting : OfflineSessionState

    /** 手表没有可用的局域网地址（未连 Wi-Fi / 只有蜂窝）。 */
    data object NoNetwork : OfflineSessionState

    data class Running(
        val pageUrl: String,
        val lastImport: ImportResult? = null,
    ) : OfflineSessionState

    data object ServerFailed : OfflineSessionState
}

/**
 * 会话状态与「本地订阅被改动」事件的进程内总线。
 *
 * 会话由前台服务持有，App 侧只读状态，避免 Activity 被回收时把服务一起带走。
 */
object OfflineSessionBus {
    val state = MutableStateFlow<OfflineSessionState>(OfflineSessionState.Idle)

    /** 页面成功导入/删除后发一次；App 端收到后从落盘文件重读订阅。 */
    val storeChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}
