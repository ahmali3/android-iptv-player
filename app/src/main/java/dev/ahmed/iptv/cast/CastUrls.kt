package dev.ahmed.iptv.cast

/**
 * Cast receivers (Chromecast, Android TV) play HLS, DASH and MP4, but not the raw MPEG-TS
 * streams most IPTV providers hand out. Xtream panels serve every channel as HLS too, so
 * casting asks for the .m3u8 version of the same stream.
 */
object CastUrls {

    private val xtreamLive = Regex("""^(https?://[^/?#]+)/(?:live/)?([^/?#]+)/([^/?#]+)/(\d+)(?:\.ts)?$""", RegexOption.IGNORE_CASE)

    fun castUrl(url: String): String {
        val u = url.trim()
        val path = u.substringBefore('?').substringBefore('#')
        val lower = path.lowercase()
        if (lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".mp4") || lower.endsWith(".m3u")) return u
        // Xtream live: host/live/user/pass/123.ts or host/user/pass/123 (no extension).
        if (!u.contains('?')) {
            xtreamLive.matchEntire(u)?.let { m ->
                val (host, user, pass, id) = m.destructured
                if (!user.equals("timeshift", true) && !user.equals("movie", true) && !user.equals("series", true)) {
                    return "$host/live/$user/$pass/$id.m3u8"
                }
            }
        }
        // Anything else ending in .ts (timeshift/catch-up included): ask for the HLS variant.
        if (lower.endsWith(".ts")) return u.substring(0, path.length - 3) + ".m3u8" + u.substring(path.length)
        return u
    }

    fun mimeType(url: String): String {
        val lower = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".mp4") -> "video/mp4"
            else -> "application/x-mpegURL"
        }
    }
}
