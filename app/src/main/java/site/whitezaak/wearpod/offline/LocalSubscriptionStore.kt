package site.whitezaak.wearpod.offline

import org.json.JSONArray
import org.json.JSONObject
import site.whitezaak.wearpod.data.OpmlParser
import site.whitezaak.wearpod.domain.Podcast
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Locale

class InvalidOpmlException(cause: Throwable? = null) : Exception(cause)

/** 落盘文件存在但无法解析。此时**不能**当成空库继续写入，否则会整库覆盖。 */
class CorruptSubscriptionStoreException(cause: Throwable? = null) : Exception(cause)

data class ImportResult(val added: Int, val skipped: Int, val total: Int)

data class DeleteResult(val remaining: List<Podcast>, val removed: Int)

/**
 * 离线模式（订阅保存在手表本地）的落盘存储。
 *
 * 纯 JVM 逻辑，不 import android.*，便于单测；文件是 JSON 数组，位于应用私有 filesDir 下。
 * 与后端 `wearpod-sync-web/src/lib/opml.ts` 的口径对齐：feedUrl 必须 http/https、按 feedUrl 去重、总量上限 200。
 */
class LocalSubscriptionStore(
    private val dir: File,
    private val newParser: () -> OpmlParser = { OpmlParser() },
    private val maxPodcasts: Int = MAX_PODCASTS,
) {

    private val file = File(dir, FILE_NAME)

    /** 本地库是否已经写过盘（false = 从未导入过，调用方应回落到内置默认订阅）。 */
    fun exists(): Boolean = file.isFile

    /**
     * 返回 null 表示「从未写入过」；文件存在但内容损坏时返回空列表（不做静默回弹内置订阅）。
     */
    fun load(): List<Podcast>? = synchronized(LOCK) {
        if (!file.isFile) return null
        try {
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 与 [load] 相同，但文件损坏时抛 [CorruptSubscriptionStoreException]。
     *
     * 写入路径必须用它：把损坏的文件当成空库再 [save] 会把用户整库订阅覆盖掉。
     */
    private fun loadStrict(): List<Podcast> {
        if (!file.isFile) return emptyList()
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw CorruptSubscriptionStoreException(e)
        }
    }

    private fun parse(text: String): List<Podcast> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val feedUrl = obj.optString("feedUrl", "").trim()
            if (feedUrl.isEmpty()) return@mapNotNull null
            Podcast(
                title = obj.optString("title", "").trim().ifEmpty { feedUrl },
                feedUrl = feedUrl,
                imageUrl = obj.optString("imageUrl", ""),
                description = obj.optString("description", ""),
            )
        }
    }

    fun save(podcasts: List<Podcast>) = synchronized(LOCK) {
        dir.mkdirs()
        val payload = toJson(podcasts.take(maxPodcasts))
        // 唯一临时名：并发写入方不能共用一个 tmp，否则可能把半截内容 rename 成正式文件。
        val tmp = File.createTempFile("$FILE_NAME.", ".tmp", dir)
        try {
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 导入一份 OPML。解析不出任何有效条目时抛 [InvalidOpmlException]。
     *
     * added = 新落盘的条数；skipped = 解析总数 - added（含无效条目、重复、超出上限）。
     */
    fun importOpml(input: InputStream): ImportResult {
        val parsed = try {
            newParser().parse(input)
        } catch (e: Exception) {
            throw InvalidOpmlException(e)
        }
        val valid = parsed.mapNotNull(::normalize)
        if (valid.isEmpty()) throw InvalidOpmlException()

        // 锁内重读：NanoHTTPD 每个请求一个线程，并发导入/删除必须串行化「合并 + 落盘」。
        return synchronized(LOCK) {
            val existing = loadStrict()
            val seen = existing.mapTo(mutableSetOf()) { it.feedUrl.lowercase(Locale.ROOT) }
            val merged = existing.toMutableList()
            for (podcast in valid) {
                if (merged.size >= maxPodcasts) break
                if (!seen.add(podcast.feedUrl.lowercase(Locale.ROOT))) continue
                merged += podcast
            }
            if (merged.size != existing.size) save(merged)
            val added = merged.size - existing.size
            ImportResult(added = added, skipped = parsed.size - added, total = merged.size)
        }
    }

    /** 返回删除后的列表与**实际**删除条数（并发写入时调用方不能再自己算差值）。 */
    fun delete(feedUrls: Collection<String>): DeleteResult = synchronized(LOCK) {
        val current = loadStrict()
        val removing = feedUrls.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
        if (removing.isEmpty()) return@synchronized DeleteResult(current, 0)
        val remaining = current.filterNot { it.feedUrl.lowercase(Locale.ROOT) in removing }
        val removed = current.size - remaining.size
        if (removed > 0) save(remaining)
        DeleteResult(remaining, removed)
    }

    private fun toJson(podcasts: List<Podcast>): String {
        val array = JSONArray()
        for (podcast in podcasts) {
            array.put(
                JSONObject().apply {
                    put("title", podcast.title)
                    put("feedUrl", podcast.feedUrl)
                    if (podcast.imageUrl.isNotEmpty()) put("imageUrl", podcast.imageUrl)
                    if (podcast.description.isNotEmpty()) put("description", podcast.description)
                }
            )
        }
        return array.toString()
    }

    private fun normalize(podcast: Podcast): Podcast? {
        val feedUrl = podcast.feedUrl.trim()
        if (feedUrl.isEmpty()) return null
        val uri = try {
            URI(feedUrl)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return Podcast(
            title = podcast.title.trim().ifEmpty { feedUrl },
            feedUrl = feedUrl,
            imageUrl = podcast.imageUrl,
            description = podcast.description,
        )
    }

    companion object {
        const val MAX_PODCASTS = 200
        private const val FILE_NAME = "subscriptions.json"

        /**
         * 进程级锁：服务与 ViewModel 各自持有独立 store 实例，却指向同一个文件，
         * 实例级锁挡不住跨实例的并发写。
         */
        private val LOCK = Any()
    }
}
