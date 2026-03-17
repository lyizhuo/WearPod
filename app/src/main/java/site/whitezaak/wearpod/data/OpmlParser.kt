package site.whitezaak.wearpod.data

import android.util.Xml
import site.whitezaak.wearpod.domain.Podcast
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

class OpmlParser {
    @Throws(XmlPullParserException::class, IOException::class)
    fun parse(inputStream: InputStream): List<Podcast> {
        inputStream.use {
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(it, null)
            parser.nextTag()
            return readOpml(parser)
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readOpml(parser: XmlPullParser): List<Podcast> {
        val podcasts = mutableListOf<Podcast>()
        parser.require(XmlPullParser.START_TAG, null, "opml")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            if (parser.name == "body") {
                podcasts.addAll(readBody(parser))
            } else {
                skip(parser)
            }
        }
        return podcasts
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readBody(parser: XmlPullParser): List<Podcast> {
        val podcasts = mutableListOf<Podcast>()
        parser.require(XmlPullParser.START_TAG, null, "body")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            if (parser.name == "outline") {
                val podcast = readOutline(parser)
                if (podcast != null) {
                    podcasts.add(podcast)
                }
            } else {
                skip(parser)
            }
        }
        return podcasts
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readOutline(parser: XmlPullParser): Podcast? {
        parser.require(XmlPullParser.START_TAG, null, "outline")
        val title = parser.getAttributeValue(null, "title") ?: parser.getAttributeValue(null, "text") ?: ""
        val xmlUrl = parser.getAttributeValue(null, "xmlUrl") ?: ""
        
        // outline tag might be empty end tag
        if (xmlUrl.isNotEmpty()) {
            // handle nested outline tags or empty tags
            if (parser.isEmptyElementTag) {
                parser.next()
            } else {
                while (parser.next() != XmlPullParser.END_TAG) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    skip(parser)
                }
            }
            return Podcast(
                title = title,
                feedUrl = xmlUrl
            )
        } else {
            // Wait to see if there are nested outlines, but simple parse for now
             if (parser.isEmptyElementTag) {
                parser.next()
            } else {
                while (parser.next() != XmlPullParser.END_TAG) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    skip(parser)
                }
            }
        }
        return null
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
}
