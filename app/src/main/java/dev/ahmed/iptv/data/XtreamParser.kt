package dev.ahmed.iptv.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/**
 * Streaming parser for the Xtream Codes player_api.php responses. Providers are loose
 * with types (numbers as strings, null vs ""), so every field is read leniently.
 */
object XtreamParser {

    data class Stream(
        val num: Int,
        val name: String,
        val streamId: String,
        val icon: String,
        val epgId: String,
        val categoryId: String,
        val archiveDays: Int,
    )

    data class Account(val auth: Boolean, val status: String, val expires: Long)

    fun categories(r: JsonReader): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue(); return out
        }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var id = ""
            var name = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "category_id" -> id = r.str()
                    "category_name" -> name = r.str()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id.isNotEmpty()) out += id to name.trim().ifEmpty { "Uncategorized" }
        }
        r.endArray()
        return out
    }

    fun streams(r: JsonReader, onStream: (Stream) -> Unit): Int {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue(); return 0
        }
        var count = 0
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var num = 0
            var name = ""
            var id = ""
            var icon = ""
            var epg = ""
            var cat = ""
            var archive = 0
            var archiveDays = 0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "num" -> num = r.str().toIntOrNull() ?: 0
                    "name" -> name = r.str()
                    "stream_id" -> id = r.str()
                    "stream_icon" -> icon = r.str()
                    "epg_channel_id" -> epg = r.str()
                    "category_id" -> cat = r.str()
                    "tv_archive" -> archive = r.str().toIntOrNull() ?: 0
                    "tv_archive_duration" -> archiveDays = r.str().toIntOrNull() ?: 0
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id.isNotEmpty()) {
                count++
                onStream(Stream(num, name.trim(), id, icon, epg.trim(), cat, if (archive == 1) archiveDays else 0))
            }
        }
        r.endArray()
        return count
    }

    fun account(r: JsonReader): Account {
        var auth = false
        var status = ""
        var exp = 0L
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue(); return Account(false, "", 0)
        }
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "user_info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "auth" -> { val v = r.str(); auth = v == "1" || v == "true" }
                        "status" -> status = r.str()
                        "exp_date" -> exp = (r.str().toLongOrNull() ?: 0L) * 1000L
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            } else r.skipValue()
        }
        r.endObject()
        return Account(auth, status, exp)
    }

    /** Reads any scalar as a string; null and objects/arrays become "". */
    private fun JsonReader.str(): String =
        when (peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> nextString()
            JsonToken.BOOLEAN -> nextBoolean().toString()
            JsonToken.NULL -> { nextNull(); "" }
            else -> { skipValue(); "" }
        }
}
