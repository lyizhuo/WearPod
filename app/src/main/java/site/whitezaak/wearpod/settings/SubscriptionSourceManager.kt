package site.whitezaak.wearpod.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 订阅来源。
 *
 * 刻意不叫「在线/离线模式」：App 里既有的「离线」指没有网络/下载播放，
 * 而这里说的是**订阅数据存在哪**，避免用户与后续维护混淆。
 */
enum class SubscriptionSource {
    /** 订阅保存在同步站（pod.whitezaak.site），凭 5 位同步码拉取——现状行为。 */
    REMOTE,

    /** 订阅保存在手表本地，通过手表热点的本地网页上传 OPML。 */
    LOCAL;

    companion object {
        fun fromStored(value: String?): SubscriptionSource =
            if (value == STORED_LOCAL) LOCAL else REMOTE

        private const val STORED_LOCAL = "local"
    }
}

object SubscriptionSourceManager {

    // 与 AppLanguageManager 共用同一个 prefs 文件
    private const val PREFS_NAME = "wearpod_settings"
    private const val KEY_SOURCE = "subscription_source"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): SubscriptionSource =
        SubscriptionSource.fromStored(prefs(context).getString(KEY_SOURCE, null))

    fun set(context: Context, source: SubscriptionSource) {
        prefs(context).edit {
            putString(KEY_SOURCE, if (source == SubscriptionSource.LOCAL) "local" else "remote")
        }
    }
}
