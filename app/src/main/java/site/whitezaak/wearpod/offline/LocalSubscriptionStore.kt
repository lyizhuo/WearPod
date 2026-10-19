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

data class ImportResult(val added: Int, val skipped: Int, val total: Int)

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
    fun load(): List<Podcast>? {
        if (!file.isFile) return null
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(podcasts: List<Podcast>) {
        dir.mkdirs()
        val payload = toJson(podcasts.take(maxPodcasts))
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(payload, Charsets.UTF_8)
            tmp.delete()
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

        val existing = load().orEmpty()
        val seen = existing.mapTo(mutableSetOf()) { it.feedUrl.lowercase(Locale.ROOT) }
        val merged = existing.toMutableList()
        for (podcast in valid) {
            if (merged.size >= maxPodcasts) break
            if (!seen.add(podcast.feedUrl.lowercase(Locale.ROOT))) continue
            merged += podcast
        }
        save(merged)
        val added = merged.size - existing.size
        return ImportResult(added = added, skipped = parsed.size - added, total = merged.size)
    }

    fun delete(feedUrls: Collection<String>): List<Podcast> {
        val current = load().orEmpty()
        if (feedUrls.isEmpty()) return current
        val removing = feedUrls.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
        val remaining = current.filterNot { it.feedUrl.lowercase(Locale.ROOT) in removing }
        if (remaining.size != current.size) save(remaining)
        return remaining
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
    }
}
