package dev.ahmed.iptv.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.google.gson.stream.JsonReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class Repo(private val context: Context, val db: Db) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    class RefreshException(message: String) : Exception(message)

    // ---------- playlists ----------

    /**
     * Normalises a playlist before saving: an M3U link that is really an Xtream panel
     * becomes an Xtream login, and Xtream server URLs lose trailing slashes.
     */
    fun normalize(p: Playlist): Playlist {
        if (p.type == PlaylistType.M3U) {
            val x = M3uParser.xtreamFromUrl(p.url)
            if (x != null) return p.copy(type = PlaylistType.XTREAM, url = x.server, username = x.username, password = x.password)
            return p.copy(url = p.url.trim())
        }
        var server = p.url.trim().trimEnd('/')
        if (!server.startsWith("http://", true) && !server.startsWith("https://", true)) server = "http://$server"
        server = server.removeSuffix("/player_api.php").removeSuffix("/get.php")
        return p.copy(url = server, username = p.username.trim(), password = p.password.trim())
    }

    // ---------- channel refresh ----------

    /** Downloads and stores the channel list. Returns the updated playlist. */
    fun refreshChannels(p: Playlist, progress: (String) -> Unit): Playlist {
        val now = System.currentTimeMillis()
        return when (p.type) {
            PlaylistType.XTREAM -> refreshXtream(p, now, progress)
            PlaylistType.M3U -> refreshM3u(p, now, progress)
        }
    }

    private fun xtreamApi(p: Playlist, action: String?): String {
        val base = "${p.url}/player_api.php?username=${enc(p.username)}&password=${enc(p.password)}"
        return if (action == null) base else "$base&action=$action"
    }

    private fun refreshXtream(p: Playlist, now: Long, progress: (String) -> Unit): Playlist {
        progress("Signing in…")
        val account = json(xtreamApi(p, null)) { XtreamParser.account(it) }
        if (!account.auth) {
            throw RefreshException(
                if (account.status.isNotBlank()) "Login refused (${account.status})" else "Login failed — check the server, username and password"
            )
        }
        progress("Loading groups…")
        val cats = json(xtreamApi(p, "get_live_categories")) { XtreamParser.categories(it) }
        val catName = cats.toMap()
        val order = HashMap<String, Int>()
        cats.forEachIndexed { i, (_, name) -> order.putIfAbsent(name, i) }
        progress("Loading channels…")
        val list = ArrayList<ParsedChannel>(4096)
        val ext = if (p.streamFormat == "m3u8") "m3u8" else "ts"
        json(xtreamApi(p, "get_live_streams")) { r ->
            XtreamParser.streams(r) { s ->
                val group = catName[s.categoryId] ?: "Uncategorized"
                if (group !in order) order[group] = order.size + 10_000
                list += ParsedChannel(
                    key = s.streamId,
                    number = if (s.num > 0) s.num else list.size + 1,
                    name = s.name.ifBlank { "Channel ${s.streamId}" },
                    group = group,
                    logo = s.icon,
                    url = "${p.url}/live/${enc(p.username)}/${enc(p.password)}/${s.streamId}.$ext",
                    epgId = s.epgId,
                    catchupDays = s.archiveDays,
                )
            }
        }
        if (list.isEmpty()) throw RefreshException("The provider returned no live channels")
        progress("Saving ${list.size} channels…")
        db.replaceChannels(p.id, list, order)
        val updated = p.copy(lastRefresh = now, expires = account.expires)
        db.savePlaylist(updated)
        return updated
    }

    private fun refreshM3u(p: Playlist, now: Long, progress: (String) -> Unit): Playlist {
        progress("Downloading playlist…")
        val list = ArrayList<ParsedChannel>(4096)
        val order = HashMap<String, Int>()
        var headerEpg = ""
        open(p.url).use { input ->
            val reader = InputStreamReader(input, Charsets.UTF_8).buffered(64 * 1024)
            M3uParser.parse(reader, onEpgUrl = { headerEpg = it }) { ch ->
                order.putIfAbsent(ch.group, order.size)
                list += ch
            }
        }
        if (list.isEmpty()) throw RefreshException("No channels found in this playlist")
        progress("Saving ${list.size} channels…")
        db.replaceChannels(p.id, list, order)
        val updated = p.copy(lastRefresh = now, epgUrl = p.epgUrl.ifBlank { headerEpg })
        db.savePlaylist(updated)
        return updated
    }

    // ---------- guide refresh ----------

    fun epgUrlFor(p: Playlist): String = when {
        p.epgUrl.isNotBlank() -> p.epgUrl
        p.type == PlaylistType.XTREAM -> "${p.url}/xmltv.php?username=${enc(p.username)}&password=${enc(p.password)}"
        else -> ""
    }

    /** Downloads the XMLTV guide and stores the programs for this playlist's channels. Returns the count. */
    fun refreshGuide(p: Playlist, progress: (String) -> Unit): Int {
        val url = epgUrlFor(p)
        if (url.isBlank()) return 0
        progress("Loading guide…")
        val now = System.currentTimeMillis()
        val wanted = db.epgIds(p.id)
        val keepFrom = now - 7L * DAY
        val keepTo = now + 3L * DAY
        var count = 0
        open(url).use { raw ->
            val parser = Xml.newPullParser()
            parser.setInput(maybeGunzip(raw), null)
            db.replacePrograms(p.id) { insert ->
                count = XmltvParser(parser).parse(
                    onChannelsDone = { names ->
                        // Match channels that have no tvg-id by their display name.
                        val matches = db.channelsWithoutEpg(p.id).mapNotNull { (rowId, name) ->
                            names[name]?.let { rowId to it }
                        }
                        if (matches.isNotEmpty()) {
                            db.setEpgIds(matches)
                            matches.forEach { wanted += it.second }
                        }
                    },
                    accept = { id, start, stop -> id in wanted && stop > keepFrom && start < keepTo },
                    onProgram = insert,
                )
            }
        }
        progress("Guide loaded")
        return count
    }

    // ---------- catch-up ----------

    /** Xtream catch-up (timeshift) URL for a past program, or null if not possible. */
    fun catchupUrl(p: Playlist, ch: ChannelRow, prog: Program): String? {
        if (p.type != PlaylistType.XTREAM || ch.catchupDays <= 0) return null
        val minutes = ((prog.stop - prog.start) / 60_000L).coerceAtLeast(1)
        val start = TIMESHIFT_FMT.format(Instant.ofEpochMilli(prog.start).atZone(ZoneId.systemDefault()))
        return "${p.url}/timeshift/${enc(p.username)}/${enc(p.password)}/$minutes/$start/${ch.key}.ts"
    }

    // ---------- I/O helpers ----------

    /** Opens an http(s) URL or a content:// / file:// URI. */
    fun open(url: String): InputStream {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return context.contentResolver.openInputStream(Uri.parse(url))
                ?: throw RefreshException("Can't open the playlist file")
        }
        val resp = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: IllegalArgumentException) {
            throw RefreshException("Invalid URL: $url")
        } catch (e: IOException) {
            throw RefreshException("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!resp.isSuccessful) {
            resp.close()
            throw RefreshException("Server answered HTTP ${resp.code}")
        }
        return resp.body?.byteStream() ?: throw RefreshException("Empty response")
    }

    private fun <T> json(url: String, read: (JsonReader) -> T): T = open(url).use { input ->
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered(64 * 1024))
        r.isLenient = true
        try {
            read(r)
        } catch (e: IllegalStateException) {
            throw RefreshException("Unexpected reply from the server")
        } catch (e: com.google.gson.stream.MalformedJsonException) {
            throw RefreshException("Unexpected reply from the server")
        }
    }

    companion object {
        const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
        const val DAY = 24L * 60 * 60 * 1000
        private val TIMESHIFT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Guides are often served as .xml.gz; detect gzip by its magic bytes rather than the name. */
        fun maybeGunzip(input: InputStream): InputStream {
            val b = BufferedInputStream(input, 64 * 1024)
            b.mark(2)
            val m1 = b.read()
            val m2 = b.read()
            b.reset()
            return if (m1 == 0x1f && m2 == 0x8b) BufferedInputStream(GZIPInputStream(b, 64 * 1024), 64 * 1024) else b
        }
    }
}
