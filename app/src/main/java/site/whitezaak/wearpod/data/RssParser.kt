package site.whitezaak.wearpod.data

import android.util.Xml
import site.whitezaak.wearpod.domain.Episode
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

class RssParser {
    private companion object {
        const val EMIT_BATCH_SIZE = 4
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun parse(
        inputStream: InputStream,
        maxItems: Int = Int.MAX_VALUE,
        onBatchParsed: ((List<Episode>) -> Unit)? = null,
    ): List<Episode> {
        inputStream.use {
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(it, null)
            parser.nextTag()
            return readFeed(parser, maxItems, onBatchParsed)
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readFeed(
        parser: XmlPullParser,
        maxItems: Int,
        onBatchParsed: ((List<Episode>) -> Unit)?,
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        parser.require(XmlPullParser.START_TAG, null, "rss")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            if (parser.name == "channel") {
                episodes.addAll(readChannel(parser, maxItems, onBatchParsed))
            } else {
                skip(parser)
            }
        }
        return episodes
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readChannel(
        parser: XmlPullParser,
        maxItems: Int,
        onBatchParsed: ((List<Episode>) -> Unit)?,
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val pendingBatch = mutableListOf<Episode>()
        parser.require(XmlPullParser.START_TAG, null, "channel")
        var channelTitle = ""
        var channelImageUrl = ""
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            when (parser.name) {
                "title" -> channelTitle = readText(parser)
                "image" -> {
                    // Extract url from image tag
                    channelImageUrl = readImage(parser)
                }
                "itunes:image" -> {
                    channelImageUrl = parser.getAttributeValue(null, "href")
                        ?.takeIf { it.isNotBlank() }
                        ?: channelImageUrl
                    skip(parser)
                }
                "item" -> {
                    if (episodes.size < maxItems) {
                        val episode = readItem(parser).copy(
                            podcastTitle = channelTitle,
                            podcastImageUrl = channelImageUrl,
                        )
                        episodes.add(episode)
                        pendingBatch.add(episode)
                        if (pendingBatch.size >= EMIT_BATCH_SIZE) {
                            onBatchParsed?.invoke(pendingBatch.toList())
                            pendingBatch.clear()
                        }
                    } else {
                        skip(parser)
                    }
                }
                else -> skip(parser)
            }
        }
        if (pendingBatch.isNotEmpty()) {
            onBatchParsed?.invoke(pendingBatch.toList())
        }
        return episodes
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readImage(parser: XmlPullParser): String {
        var url = ""
        parser.require(XmlPullParser.START_TAG, null, "image")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "url") {
                url = readText(parser)
            } else {
                skip(parser)
            }
        }
        return url
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readItem(parser: XmlPullParser): Episode {
        parser.require(XmlPullParser.START_TAG, null, "item")
        var title = ""
        var audioUrl = ""
        var duration = ""
        var pubDate = ""
        var imageUrl = ""
        var description = ""

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            when (parser.name) {
                "title" -> title = readText(parser)
                "enclosure" -> {
                    audioUrl = parser.getAttributeValue(null, "url") ?: ""
                    skip(parser)
                }
                "media:content" -> {
                    if (audioUrl.isBlank()) {
                        val mediaUrl = parser.getAttributeValue(null, "url").orEmpty()
                        val mediaType = parser.getAttributeValue(null, "type").orEmpty()
                        if (mediaUrl.isNotBlank() && mediaType.startsWith("audio")) {
                            audioUrl = mediaUrl
                        }
                    }
                    if (imageUrl.isBlank()) {
                        val mediaUrl = parser.getAttributeValue(null, "url").orEmpty()
                        val mediaType = parser.getAttributeValue(null, "type").orEmpty()
                        if (mediaUrl.isNotBlank() && mediaType.startsWith("image")) {
                            imageUrl = mediaUrl
                        }
                    }
                    skip(parser)
                }
                "media:thumbnail" -> {
                    if (imageUrl.isBlank()) {
                        imageUrl = parser.getAttributeValue(null, "url") ?: ""
                    }
                    skip(parser)
                }
                "itunes:duration" -> duration = readText(parser)
                "pubDate" -> {
                    val rawDate = readText(parser)
                    pubDate = formatDate(rawDate)
                }
                "itunes:image" -> {
                    imageUrl = parser.getAttributeValue(null, "href") ?: ""
                    skip(parser)
                }
                "image" -> {
                    if (imageUrl.isBlank()) {
                        imageUrl = readImage(parser)
                    } else {
                        skip(parser)
                    }
                }
                "description" -> description = readText(parser)
                else -> skip(parser)
            }
        }
        return Episode(
            title,
            audioUrl,
            formatDuration(duration),
            pubDate,
            imageUrl,
            description
        )
    }

    private fun formatDuration(rawDuration: String): String {
        if (rawDuration.isEmpty()) return "00:00"
        return try {
            if (rawDuration.contains(":")) {
                rawDuration // Already HH:MM:SS format
            } else {
                val totalSeconds = rawDuration.toLong()
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                if (hours > 0) {
                    String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                }
            }
        } catch (_: Exception) {
            rawDuration
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text
            parser.nextTag()
        }
        return result
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException()
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    private fun formatDate(rawDate: String): String {
        // Simple formatter for RSS dates: "EEE, dd MMM yyyy HH:mm:ss Z"
        try {
            val formatStr = "EEE, dd MMM yyyy HH:mm:ss Z"
            val format = SimpleDateFormat(formatStr, Locale.ENGLISH)
            val date = format.parse(rawDate)
            if (date != null) {
                // Force US Locale so output is "dd MMM yyyy" in English (e.g. 14 Mar 2026) for later parsing
                val outFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
                return outFormat.format(date)
            }
        } catch (_: Exception) {
            return rawDate
        }
        return rawDate
    }
}
