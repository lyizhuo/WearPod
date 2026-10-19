package site.whitezaak.wearpod.offline

import android.content.res.AssetManager

/** 离线页面的静态资源读取口（测试里用 lambda 提供假资源）。 */
fun interface WebAssets {
    /** path 例："index.html"；不存在返回 null。 */
    fun read(path: String): String?
}

class AssetWebAssets(private val assets: AssetManager) : WebAssets {
    override fun read(path: String): String? = try {
        assets.open("offline/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Exception) {
        null
    }
}
