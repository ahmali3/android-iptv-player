package dev.ahmed.iptv.data

import org.xmlpull.v1.XmlPullParser
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Streaming XMLTV parser. It never holds the whole guide in memory: channels and
 * programmes are handed to the callbacks one at a time.
 *
 * XMLTV lists every <channel> before the <programme> elements, so [onChannelsDone] is
 * called once, with every display-name (lower-cased) mapped to its channel id, before
 * the first programme is read. This lets the caller match playlist channels that have no
 * tvg-id by name before deciding which programmes to keep via [accept].
 */
class XmltvParser(private val p: XmlPullParser) {

    fun parse(
        onChannelsDone: (Map<String, String>) -> Unit,
        accept: (epgId: String, start: Long, stop: Long) -> Boolean,
        onProgram: (Program) -> Unit,
    ): Int {
        val names = HashMap<String, String>()
        var channelsDone = false
        var count = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> {
                        val id = p.getAttributeValue(null, "id").orEmpty()
                        children { child ->
                            if (child == "display-name") {
                                val n = text().lowercase()
                                if (n.isNotEmpty() && id.isNotEmpty()) names.putIfAbsent(n, id)
                            }
                        }
                    }
                    "programme" -> {
                        if (!channelsDone) {
                            channelsDone = true
                            onChannelsDone(names)
                        }
                        val id = p.getAttributeValue(null, "channel").orEmpty()
                        val start = parseTime(p.getAttributeValue(null, "start").orEmpty())
                        val stop = parseTime(p.getAttributeValue(null, "stop").orEmpty())
                        if (id.isEmpty() || start == null || stop == null || stop <= start || !accept(id, start, stop)) {
                            children { }
                        } else {
                            var title = ""
                            var desc = ""
                            children { child ->
                                when (child) {
                                    "title" -> if (title.isEmpty()) title = text()
                                    "desc" -> if (desc.isEmpty()) desc = text()
                                }
                            }
                            onProgram(Program(id, start, stop, title.ifEmpty { "Untitled" }, desc))
                            count++
                        }
                    }
                }
            }
            ev = p.next()
        }
        if (!channelsDone) onChannelsDone(names)
        return count
    }

    /** Walks the direct children of the current element, ending on its END_TAG. */
    private inline fun children(onChild: (String) -> Unit) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            if (ev == XmlPullParser.START_TAG && p.depth == depth + 1) onChild(p.name)
        }
    }

    /** Reads the text of the current element, ending on its END_TAG. */
    private fun text(): String {
        val depth = p.depth
        val sb = StringBuilder()
        while (true) {
            val ev = p.next()
            when {
                ev == XmlPullParser.TEXT -> sb.append(p.text)
                ev == XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
                ev == XmlPullParser.END_TAG && p.depth == depth -> return sb.toString().trim()
            }
        }
    }

    companion object {
        /** Parses XMLTV times like "20261017183000 +0000". No offset means UTC. */
        fun parseTime(s: String): Long? {
            val t = s.trim()
            val digits = t.takeWhile { it.isDigit() }
            if (digits.length < 12) return null
            return try {
                val dt = LocalDateTime.of(
                    digits.substring(0, 4).toInt(),
                    digits.substring(4, 6).toInt(),
                    digits.substring(6, 8).toInt(),
                    digits.substring(8, 10).toInt(),
                    digits.substring(10, 12).toInt(),
                    if (digits.length >= 14) digits.substring(12, 14).toInt() else 0,
                )
                val rest = t.substring(digits.length).trim()
                val offset = if (rest.isEmpty()) ZoneOffset.UTC else ZoneOffset.of(rest)
                dt.toInstant(offset).toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
    }
}
