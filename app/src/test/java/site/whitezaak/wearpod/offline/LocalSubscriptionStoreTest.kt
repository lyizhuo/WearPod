package site.whitezaak.wearpod.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import site.whitezaak.wearpod.data.OpmlParser
import site.whitezaak.wearpod.domain.Podcast

class LocalSubscriptionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(maxPodcasts: Int = LocalSubscriptionStore.MAX_PODCASTS) =
        LocalSubscriptionStore(
            dir = tempFolder.root,
            newParser = { OpmlParser { KXmlParser() } },
            maxPodcasts = maxPodcasts,
        )

    private fun opml(vararg entries: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<opml version=\"2.0\"><head><title>t</title></head><body>")
        for ((title, url) in entries) {
            append("<outline type=\"rss\" text=\"").append(title).append("\" title=\"")
                .append(title).append("\" xmlUrl=\"").append(url).append("\"/>")
        }
        append("</body></opml>")
    }

    @Test
    fun `load returns null before anything is written`() {
        assertNull(store().load())
        assertEquals(false, store().exists())
    }

    @Test
    fun `import adds entries and counts duplicates as skipped`() {
        val store = store()
        val result = store.importOpml(
            opml(
                "The Daily" to "https://example.com/daily.xml",
                "Syntax" to "https://example.com/syntax.xml",
                "The Daily again" to "https://example.com/daily.xml",
            ).byteInputStream()
        )

        assertEquals(2, result.added)
        assertEquals(1, result.skipped)
        assertEquals(2, result.total)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(listOf("The Daily", "Syntax"), loaded!!.map { it.title })
    }

    @Test
    fun `import keeps chinese titles and decodes entities`() {
        val store = store()
        store.importOpml(opml("中文 &amp; 播客" to "https://example.com/cn.xml").byteInputStream())

        assertEquals("中文 & 播客", store.load()?.single()?.title)
    }

    @Test
    fun `import skips invalid urls inside an otherwise valid file`() {
        val store = store()
        val result = store.importOpml(
            opml(
                "Good" to "https://example.com/g.xml",
                "Ftp" to "ftp://example.com/feed.xml",
                "NoScheme" to "/local/feed.xml",
            ).byteInputStream()
        )
        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        assertEquals(1, result.total)
    }

    @Test(expected = InvalidOpmlException::class)
    fun `import throws when no entry is usable`() {
        store().importOpml(
            opml(
                "Ftp" to "ftp://example.com/feed.xml",
                "NoScheme" to "/local/feed.xml",
            ).byteInputStream()
        )
    }

    @Test(expected = InvalidOpmlException::class)
    fun `import throws on unusable opml`() {
        store().importOpml("this is not opml at all".byteInputStream())
    }

    @Test
    fun `save with empty list is not the same as never written`() {
        val store = store()
        store.save(emptyList())
        assertEquals(true, store.exists())
        assertEquals(emptyList<Podcast>(), store.load())
    }

    @Test
    fun `import respects the podcast limit`() {
        val store = store(maxPodcasts = 200)
        val entries = (1..250).map { "P$it" to "https://example.com/$it.xml" }.toTypedArray()

        val result = store.importOpml(opml(*entries).byteInputStream())

        assertEquals(200, result.total)
        assertEquals(200, result.added)
        assertEquals(50, result.skipped)
        assertEquals(200, store.load()?.size)
    }

    @Test
    fun `import on second run adds nothing`() {
        val store = store()
        val body = opml("The Daily" to "https://example.com/daily.xml")
        store.importOpml(body.byteInputStream())
        val second = store.importOpml(body.byteInputStream())

        assertEquals(0, second.added)
        assertEquals(1, second.skipped)
        assertEquals(1, second.total)
    }

    @Test
    fun `delete removes matching feed urls case insensitively`() {
        val store = store()
        store.importOpml(
            opml(
                "A" to "https://example.com/A.xml",
                "B" to "https://example.com/b.xml",
            ).byteInputStream()
        )

        val remaining = store.delete(listOf("https://EXAMPLE.com/a.xml"))

        assertEquals(listOf("B"), remaining.map { it.title })
        assertEquals(listOf("B"), store.load()?.map { it.title })
    }

    @Test
    fun `load survives a corrupt file by returning empty list`() {
        val store = store()
        store.save(listOf(Podcast(title = "A", feedUrl = "https://example.com/a.xml")))
        java.io.File(tempFolder.root, "subscriptions.json").writeText("{ not json")

        val loaded = store.load()
        assertTrue(loaded != null && loaded.isEmpty())
    }
}
