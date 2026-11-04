package site.whitezaak.wearpod.offline

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 手表本地订阅的一次变更（来自手机端网页），用于结束页如实报数。 */
data class ChangeSummary(
    val added: Int = 0,
    val deleted: Int = 0,
    val skipped: Int = 0,
) {
    /** 同一次本地上传会话里可能连续导入/删除，结束页显示累计值。 */
    operator fun plus(change: PageChange): ChangeSummary = when (change) {
        is PageChange.Imported -> copy(
            added = added + change.added,
            skipped = skipped + change.skipped,
        )
        is PageChange.Deleted -> copy(deleted = deleted + change.deleted)
    }
}

/** 本地上传会话（局域网内嵌 HTTP 服务）的对外状态。 */
sealed interface OfflineSessionState {
    data object Idle : OfflineSessionState
    data object Starting : OfflineSessionState

    /** 手表没有可用的局域网地址（未连 Wi-Fi / 只有蜂窝）。 */
    data object NoNetwork : OfflineSessionState

    data class Running(val pageUrl: String) : OfflineSessionState

    /** 手机网页点了「完成」：显示结束页；HTTP 服务已关闭，可「继续导入」重新开启。 */
    data class Completed(val summary: ChangeSummary) : OfflineSessionState

    data object ServerFailed : OfflineSessionState
}

/** 手机端网页引发的一次导入/删除事件；「完成」由 OfflineApi 的独立回调触发。 */
sealed interface PageChange {
    data class Imported(val added: Int, val skipped: Int, val total: Int) : PageChange
    data class Deleted(val deleted: Int, val total: Int) : PageChange
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

    /** 服务因超时/系统回收而结束；页面收到后退出本页。显式停止不发。 */
    val sessionEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
