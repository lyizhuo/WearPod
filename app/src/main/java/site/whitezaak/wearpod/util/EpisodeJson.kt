package site.whitezaak.wearpod.util

import org.json.JSONArray
import org.json.JSONObject
import site.whitezaak.wearpod.domain.Episode

object EpisodeJson {
    fun serializeEpisode(ep: Episode): JSONObject {
        return JSONObject().apply {
            put("title", ep.title)
            put("description", ep.description)
            put("pubDate", ep.pubDate)
            put("audioUrl", ep.audioUrl)
            put("imageUrl", ep.imageUrl)
            put("podcastTitle", ep.podcastTitle)
            put("podcastImageUrl", ep.podcastImageUrl)
            put("duration", ep.duration)
        }
    }

    fun deserializeEpisode(obj: JSONObject): Episode {
        val rawPubDate = obj.optString("pubDate")
        val normalizedPubDate = PubDateNormalizer.toCanonicalDate(rawPubDate) ?: rawPubDate
        return Episode(
            title = obj.getString("title"),
            description = obj.optString("description"),
            pubDate = normalizedPubDate,
            audioUrl = obj.getString("audioUrl"),
            imageUrl = obj.optString("imageUrl"),
            podcastTitle = obj.optString("podcastTitle"),
            podcastImageUrl = obj.optString("podcastImageUrl"),
            duration = obj.optString("duration"),
        )
    }

    fun serializeEpisodes(episodes: List<Episode>): String {
        val array = JSONArray()
        episodes.forEach { ep ->
            array.put(serializeEpisode(ep))
        }
        return array.toString()
    }

    fun deserializeEpisodes(jsonStr: String): List<Episode> {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Episode>()
        for (i in 0 until array.length()) {
            list.add(deserializeEpisode(array.getJSONObject(i)))
        }
        return list
    }
}