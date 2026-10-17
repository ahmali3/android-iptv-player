package dev.ahmed.iptv.data

import java.io.BufferedReader
import java.net.URI
import java.net.URLDecoder

/** Streaming M3U / M3U8 (extended) playlist parser. Pure Kotlin, so it is unit-testable on the JVM. */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /**
     * Reads the playlist line by line and calls [onChannel] for each entry.
     * [onEpgUrl] is called with the guide URL from the header (url-tvg / x-tvg-url) if present.
     * Returns the number of channels found.
     */
    fun parse(
        reader: BufferedReader,
        onEpgUrl: (String) -> Unit = {},
        onChannel: (ParsedChannel) -> Unit,
    ): Int {
        var count = 0
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    val epg = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                    if (!epg.isNullOrBlank()) onEpgUrl(epg.split(',').first().trim())
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // #EXTVLCOPT and other directives
                else -> {
                    val info = pendingInfo
                    pendingInfo = null
                    if (info != null) {
                        count++
                        val ch = channelFrom(info, pendingGroup, line, count)
                        onChannel(ch)
                    }
                }
            }
        }
        return count
    }

    internal fun channelFrom(info: String, extGroup: String?, url: String, index: Int): ParsedChannel {
        val attrs = attributes(info)
        val name = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Channel $index" }
        val group = (attrs["group-title"] ?: extGroup).orEmpty().trim().ifBlank { "Uncategorized" }
        val number = attrs["tvg-chno"]?.toIntOrNull() ?: index
        val catchup = attrs["catchup-days"]?.toIntOrNull() ?: attrs["tvg-rec"]?.toIntOrNull() ?: 0
        return ParsedChannel(
            key = name,
            number = number,
            name = name,
            group = group,
            logo = attrs["tvg-logo"].orEmpty(),
            url = url,
            epgId = attrs["tvg-id"].orEmpty().trim(),
            catchupDays = catchup,
        )
    }

    internal fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The display name is the text after the first comma that is not inside quotes. */
    internal fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }

    data class XtreamLogin(val server: String, val username: String, val password: String)

    /**
     * Many M3U links are really Xtream panels: http://host:port/get.php?username=U&password=P&type=m3u_plus.
     * Returns the Xtream login hidden in such a link, or null.
     */
    fun xtreamFromUrl(url: String): XtreamLogin? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val path = uri.path.orEmpty()
        if (!path.endsWith("get.php") && !path.endsWith("player_api.php")) return null
        val query = uri.rawQuery ?: return null
        val params = query.split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            if (k.isEmpty()) null else k to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }.toMap()
        val user = params["username"] ?: return null
        val pass = params["password"] ?: return null
        if (user.isEmpty() || pass.isEmpty()) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val prefix = path.substringBeforeLast('/')
        return XtreamLogin("$scheme://${uri.host}$port$prefix", user, pass)
    }
}
