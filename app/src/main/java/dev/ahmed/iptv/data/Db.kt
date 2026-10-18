package dev.ahmed.iptv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite (no ORM) to keep the app small and fast. All calls are blocking; callers run
 * them on Dispatchers.IO.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "iptv.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type TEXT NOT NULL,
               url TEXT NOT NULL, username TEXT NOT NULL DEFAULT '', password TEXT NOT NULL DEFAULT '',
               epg_url TEXT NOT NULL DEFAULT '', stream_format TEXT NOT NULL DEFAULT 'ts',
               last_refresh INTEGER NOT NULL DEFAULT 0, expires INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE channels(id INTEGER PRIMARY KEY, playlist_id INTEGER NOT NULL, ckey TEXT NOT NULL,
               num INTEGER NOT NULL, sort INTEGER NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, gsort INTEGER NOT NULL,
               logo TEXT NOT NULL, url TEXT NOT NULL, epg_id TEXT NOT NULL, catchup INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX ch_grp ON channels(playlist_id, grp, sort)")
        db.execSQL("CREATE INDEX ch_sort ON channels(playlist_id, gsort, sort)")
        db.execSQL("CREATE INDEX ch_key ON channels(playlist_id, ckey)")
        db.execSQL("CREATE TABLE favorites(playlist_id INTEGER NOT NULL, ckey TEXT NOT NULL, PRIMARY KEY(playlist_id, ckey)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE recent(playlist_id INTEGER NOT NULL, ckey TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(playlist_id, ckey)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE hidden_groups(playlist_id INTEGER NOT NULL, grp TEXT NOT NULL, PRIMARY KEY(playlist_id, grp)) WITHOUT ROWID")
        db.execSQL(
            """CREATE TABLE programs(playlist_id INTEGER NOT NULL, epg_id TEXT NOT NULL, start INTEGER NOT NULL,
               stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX pr_ch ON programs(playlist_id, epg_id, start)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- playlists ----

    fun playlists(): List<Playlist> = readableDatabase.rawQuery(
        """SELECT p.*, (SELECT COUNT(*) FROM channels c WHERE c.playlist_id = p.id) AS cnt
           FROM playlists p ORDER BY p.id""", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Playlist(
                    id = c.long("id"), name = c.str("name"),
                    type = if (c.str("type") == "xtream") PlaylistType.XTREAM else PlaylistType.M3U,
                    url = c.str("url"), username = c.str("username"), password = c.str("password"),
                    epgUrl = c.str("epg_url"), streamFormat = c.str("stream_format"),
                    lastRefresh = c.long("last_refresh"), expires = c.long("expires"), channelCount = c.int("cnt"),
                )
            )
        }
    }

    fun savePlaylist(p: Playlist): Long {
        val v = ContentValues().apply {
            put("name", p.name)
            put("type", if (p.type == PlaylistType.XTREAM) "xtream" else "m3u")
            put("url", p.url)
            put("username", p.username)
            put("password", p.password)
            put("epg_url", p.epgUrl)
            put("stream_format", p.streamFormat)
            put("last_refresh", p.lastRefresh)
            put("expires", p.expires)
        }
        return if (p.id == 0L) writableDatabase.insertOrThrow("playlists", null, v)
        else { writableDatabase.update("playlists", v, "id=?", arrayOf(p.id.toString())); p.id }
    }

    fun deletePlaylist(id: Long) = writableDatabase.inTx {
        val a = arrayOf(id.toString())
        for (t in listOf("channels", "favorites", "recent", "hidden_groups", "programs")) delete(t, "playlist_id=?", a)
        delete("playlists", "id=?", a)
    }

    // ---- channels ----

    /** Replaces all channels of a playlist. [groupOrder] gives each group's position. */
    fun replaceChannels(playlistId: Long, channels: List<ParsedChannel>, groupOrder: Map<String, Int>) =
        writableDatabase.inTx {
            delete("channels", "playlist_id=?", arrayOf(playlistId.toString()))
            val st = compileStatement(
                "INSERT INTO channels(playlist_id, ckey, num, sort, name, grp, gsort, logo, url, epg_id, catchup) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
            )
            channels.forEachIndexed { i, ch ->
                st.clearBindings()
                st.bindLong(1, playlistId)
                st.bindString(2, ch.key)
                st.bindLong(3, ch.number.toLong())
                st.bindLong(4, i.toLong())
                st.bindString(5, ch.name)
                st.bindString(6, ch.group)
                st.bindLong(7, (groupOrder[ch.group] ?: Int.MAX_VALUE).toLong())
                st.bindString(8, ch.logo)
                st.bindString(9, ch.url)
                st.bindString(10, ch.epgId)
                st.bindLong(11, ch.catchupDays.toLong())
                st.executeInsert()
            }
            st.close()
        }

    fun groups(playlistId: Long): List<GroupInfo> = readableDatabase.rawQuery(
        """SELECT c.grp, COUNT(*) AS cnt, MIN(c.gsort) AS gs, MIN(c.sort) AS s,
                  EXISTS(SELECT 1 FROM hidden_groups h WHERE h.playlist_id = c.playlist_id AND h.grp = c.grp) AS hid
           FROM channels c WHERE c.playlist_id = ? GROUP BY c.grp ORDER BY gs, s""",
        arrayOf(playlistId.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(GroupInfo(c.str("grp"), c.int("cnt"), c.int("hid") == 1)) } }

    fun setGroupHidden(playlistId: Long, group: String, hidden: Boolean) {
        if (hidden) writableDatabase.insertWithOnConflict(
            "hidden_groups", null,
            ContentValues().apply { put("playlist_id", playlistId); put("grp", group) },
            SQLiteDatabase.CONFLICT_IGNORE
        ) else writableDatabase.delete("hidden_groups", "playlist_id=? AND grp=?", arrayOf(playlistId.toString(), group))
    }

    fun favoriteCount(playlistId: Long): Int = count("SELECT COUNT(*) FROM favorites WHERE playlist_id=?", playlistId)
    fun recentCount(playlistId: Long): Int = count("SELECT COUNT(*) FROM recent WHERE playlist_id=?", playlistId)
    fun channelCount(playlistId: Long): Int = count("SELECT COUNT(*) FROM channels WHERE playlist_id=?", playlistId)

    private fun count(sql: String, playlistId: Long): Int =
        readableDatabase.rawQuery(sql, arrayOf(playlistId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Channels in a group (or one of the [Lists]), each joined with the program airing at [now].
     * [search] filters by channel name across the whole playlist.
     */
    fun channels(playlistId: Long, list: String, now: Long, search: String? = null): List<ChannelRow> {
        val pid = playlistId.toString()
        val nowS = now.toString()
        val base = """SELECT c.id, c.ckey, c.num, c.name, c.grp, c.logo, c.url, c.epg_id, c.catchup,
                  EXISTS(SELECT 1 FROM favorites f WHERE f.playlist_id = c.playlist_id AND f.ckey = c.ckey) AS fav,
                  p.title AS ptitle, p.start AS pstart, p.stop AS pstop
           FROM channels c
           LEFT JOIN programs p ON p.rowid = (
               SELECT rowid FROM programs WHERE playlist_id = c.playlist_id AND epg_id = c.epg_id AND c.epg_id <> ''
               AND start <= ? AND stop > ? ORDER BY start DESC LIMIT 1)"""
        val (sql, args) = when {
            !search.isNullOrBlank() -> "$base WHERE c.playlist_id = ? AND c.name LIKE ? ESCAPE '\\' ORDER BY c.gsort, c.sort LIMIT 300" to
                arrayOf(nowS, nowS, pid, "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            list == Lists.ALL -> "$base WHERE c.playlist_id = ? ORDER BY c.gsort, c.sort" to arrayOf(nowS, nowS, pid)
            list == Lists.FAVORITES -> "$base JOIN favorites f2 ON f2.playlist_id = c.playlist_id AND f2.ckey = c.ckey WHERE c.playlist_id = ? GROUP BY c.ckey ORDER BY c.gsort, c.sort" to arrayOf(nowS, nowS, pid)
            list == Lists.RECENT -> "$base JOIN recent r ON r.playlist_id = c.playlist_id AND r.ckey = c.ckey WHERE c.playlist_id = ? GROUP BY c.ckey ORDER BY MAX(r.ts) DESC LIMIT 40" to arrayOf(nowS, nowS, pid)
            else -> "$base WHERE c.playlist_id = ? AND c.grp = ? ORDER BY c.sort" to arrayOf(nowS, nowS, pid, list)
        }
        return readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val hasNow = !c.isNull(c.getColumnIndexOrThrow("ptitle"))
                    add(
                        ChannelRow(
                            id = c.long("id"), key = c.str("ckey"), number = c.int("num"), name = c.str("name"),
                            group = c.str("grp"), logo = c.str("logo"), url = c.str("url"), epgId = c.str("epg_id"),
                            catchupDays = c.int("catchup"), favorite = c.int("fav") == 1,
                            nowTitle = if (hasNow) c.str("ptitle") else null,
                            nowStart = if (hasNow) c.long("pstart") else 0, nowStop = if (hasNow) c.long("pstop") else 0,
                        )
                    )
                }
            }
        }
    }

    fun setFavorite(playlistId: Long, key: String, fav: Boolean) {
        if (fav) writableDatabase.insertWithOnConflict(
            "favorites", null,
            ContentValues().apply { put("playlist_id", playlistId); put("ckey", key) },
            SQLiteDatabase.CONFLICT_IGNORE
        ) else writableDatabase.delete("favorites", "playlist_id=? AND ckey=?", arrayOf(playlistId.toString(), key))
    }

    fun isFavorite(playlistId: Long, key: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM favorites WHERE playlist_id = ? AND ckey = ?", arrayOf(playlistId.toString(), key)
    ).use { it.moveToFirst() }

    fun addRecent(playlistId: Long, key: String, ts: Long) {
        writableDatabase.insertWithOnConflict(
            "recent", null,
            ContentValues().apply { put("playlist_id", playlistId); put("ckey", key); put("ts", ts) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        writableDatabase.execSQL(
            "DELETE FROM recent WHERE playlist_id = ? AND ckey NOT IN (SELECT ckey FROM recent WHERE playlist_id = ? ORDER BY ts DESC LIMIT 40)",
            arrayOf<Any>(playlistId, playlistId)
        )
    }

    /** Channels with no guide id, as (row id, lower-case name), for name matching against the XMLTV. */
    fun channelsWithoutEpg(playlistId: Long): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT id, name FROM channels WHERE playlist_id = ? AND epg_id = ''", arrayOf(playlistId.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1).lowercase().trim()) } }

    fun setEpgIds(ids: List<Pair<Long, String>>) = writableDatabase.inTx {
        val st = compileStatement("UPDATE channels SET epg_id = ? WHERE id = ?")
        for ((rowId, epg) in ids) {
            st.clearBindings(); st.bindString(1, epg); st.bindLong(2, rowId); st.executeUpdateDelete()
        }
        st.close()
    }

    fun epgIds(playlistId: Long): HashSet<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT epg_id FROM channels WHERE playlist_id = ? AND epg_id <> ''", arrayOf(playlistId.toString())
    ).use { c -> HashSet<String>().apply { while (c.moveToNext()) add(c.getString(0)) } }

    // ---- programs ----

    /** Runs [block] inside one transaction that first clears the playlist's programs. */
    fun replacePrograms(playlistId: Long, block: (insert: (Program) -> Unit) -> Unit) = writableDatabase.inTx {
        delete("programs", "playlist_id=?", arrayOf(playlistId.toString()))
        val st = compileStatement("INSERT INTO programs(playlist_id, epg_id, start, stop, title, descr) VALUES(?,?,?,?,?,?)")
        block { p ->
            st.clearBindings()
            st.bindLong(1, playlistId); st.bindString(2, p.epgId); st.bindLong(3, p.start); st.bindLong(4, p.stop)
            st.bindString(5, p.title); st.bindString(6, p.desc)
            st.executeInsert()
        }
        st.close()
    }

    /** Programs overlapping [from, to) for the given guide ids, grouped by guide id. */
    fun programs(playlistId: Long, epgIds: Collection<String>, from: Long, to: Long): Map<String, List<Program>> {
        if (epgIds.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Program>>()
        for (chunk in epgIds.distinct().chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = arrayOf(playlistId.toString(), to.toString(), from.toString()) + chunk
            readableDatabase.rawQuery(
                "SELECT epg_id, start, stop, title, descr FROM programs WHERE playlist_id = ? AND start < ? AND stop > ? AND epg_id IN ($marks) ORDER BY start",
                args
            ).use { c ->
                while (c.moveToNext()) {
                    val p = Program(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4))
                    out.getOrPut(p.epgId) { ArrayList() }.add(p)
                }
            }
        }
        return out
    }

    private inline fun SQLiteDatabase.inTx(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
}
