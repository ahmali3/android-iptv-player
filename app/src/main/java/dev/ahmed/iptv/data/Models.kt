package dev.ahmed.iptv.data

enum class PlaylistType { XTREAM, M3U }

data class Playlist(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    /** Xtream: server base URL (scheme://host:port). M3U: playlist URL or content:// URI. */
    val url: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    /** Xtream stream container: "ts" or "m3u8". */
    val streamFormat: String = "ts",
    val lastRefresh: Long = 0,
    val expires: Long = 0,
    val channelCount: Int = 0,
)

/** A channel as parsed from a source, before it is stored. */
data class ParsedChannel(
    val key: String,
    val number: Int,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val epgId: String,
    val catchupDays: Int = 0,
)

/** A channel row joined with the program airing now (if any). */
data class ChannelRow(
    val id: Long,
    val key: String,
    val number: Int,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val epgId: String,
    val catchupDays: Int,
    val favorite: Boolean,
    val nowTitle: String?,
    val nowStart: Long,
    val nowStop: Long,
)

data class GroupInfo(val name: String, val count: Int, val hidden: Boolean)

data class Program(
    val epgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String,
)

/** Special lists that are not provider groups. */
object Lists {
    const val ALL = "\u0000all"
    const val FAVORITES = "\u0000fav"
    const val RECENT = "\u0000recent"
    fun label(list: String) = when (list) {
        ALL -> "All channels"
        FAVORITES -> "Favorites"
        RECENT -> "Recent"
        else -> list
    }
}
