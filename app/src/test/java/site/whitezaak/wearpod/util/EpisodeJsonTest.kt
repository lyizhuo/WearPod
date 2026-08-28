package site.whitezaak.wearpod.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import site.whitezaak.wearpod.domain.Episode

class EpisodeJsonTest {

    private fun sampleEpisode() = Episode(
        title = "Episode & <Test> 中文",
        audioUrl = "https://example.com/audio/1.mp3?token=a&b",
        duration = "01:02:03",
        pubDate = "20 Aug 2026",
        imageUrl = "https://example.com/img.jpg",
        description = "Line1\nLine2 with \"quotes\" and <b>html</b>",
        podcastTitle = "Podcast & Friends",
        podcastImageUrl = "https://example.com/pod.jpg"
    )

    @Test
    fun `round trip preserves all fields`() {
        val original = sampleEpisode()
        val restored = EpisodeJson.deserializeEpisode(EpisodeJson.serializeEpisode(original))

        assertEquals(original.title, restored.title)
        assertEquals(original.description, restored.description)
        assertEquals(original.pubDate, restored.pubDate)
        assertEquals(original.audioUrl, restored.audioUrl)
        assertEquals(original.imageUrl, restored.imageUrl)
        assertEquals(original.podcastTitle, restored.podcastTitle)
        assertEquals(original.podcastImageUrl, restored.podcastImageUrl)
        assertEquals(original.duration, restored.duration)
    }

    @Test
    fun `missing optional fields default to empty`() {
        val json = JSONObject().apply {
            put("title", "T")
            put("audioUrl", "https://example.com/a.mp3")
        }
        val restored = EpisodeJson.deserializeEpisode(json)
        assertEquals("T", restored.title)
        assertEquals("", restored.description)
        assertEquals("", restored.podcastTitle)
        assertEquals("", restored.duration)
    }

    @Test
    fun `non canonical pubDate normalized on deserialize`() {
        val json = JSONObject().apply {
            put("title", "T")
            put("audioUrl", "https://example.com/a.mp3")
            put("pubDate", "Thu, 20 Aug 2026 12:00:00 GMT")
        }
        assertEquals("20 Aug 2026", EpisodeJson.deserializeEpisode(json).pubDate)
    }

    @Test
    fun `episode list round trip keeps order`() {
        val episodes = listOf(
            sampleEpisode(),
            Episode("Second", "https://example.com/2.mp3", "00:30:00", "19 Aug 2026"),
        )
        val restored = EpisodeJson.deserializeEpisodes(EpisodeJson.serializeEpisodes(episodes))
        assertEquals(2, restored.size)
        assertEquals("Second", restored[1].title)
        assertEquals("19 Aug 2026", restored[1].pubDate)
    }

    @Test
    fun `urls with query strings and special chars survive`() {
        val episode = Episode("Q", "https://example.com/a.mp3?x=1&y=中%20文#frag")
        val restored = EpisodeJson.deserializeEpisode(EpisodeJson.serializeEpisode(episode))
        assertEquals(episode.audioUrl, restored.audioUrl)
    }
}
