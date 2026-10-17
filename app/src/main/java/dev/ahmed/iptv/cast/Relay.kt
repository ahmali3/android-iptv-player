package dev.ahmed.iptv.cast

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A tiny HTTP relay on the phone, used when a TV can't load a stream directly.
 *
 * Cast receivers are web players: they need CORS headers on HLS playlists and segments,
 * and they send a browser user agent, which some providers block. The relay fetches the
 * stream with the app's own client, adds CORS headers and rewrites playlist entries so
 * every segment also goes through the relay. A random secret in the path keeps other
 * devices on the network from using it.
 */
class Relay(private val http: OkHttpClient, private val host: String) {

    private val secret = ByteArray(12).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(bindAll: Boolean = true): Relay {
        if (running) return this
        val s = if (bindAll) ServerSocket(0) else ServerSocket(0, 50, InetAddress.getByName(host))
        server = s
        val p = Executors.newCachedThreadPool { r -> Thread(r, "cast-relay").apply { isDaemon = true } }
        pool = p
        p.execute {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: IOException) { break }
                p.execute { handle(client) }
            }
        }
        return this
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
        pool = null
    }

    /** The relay address for [url]. The file name is kept at the end so players can still see the type. */
    fun wrap(url: String): String {
        val port = server?.localPort ?: error("relay not started")
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "stream" }
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.ifBlank { "stream" }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())
        return "http://$host:$port/$secret/$token/$name"
    }

    private fun unwrap(path: String): String? {
        val parts = path.removePrefix("/").split('/')
        if (parts.size < 2 || parts[0] != secret) return null
        return runCatching { String(Base64.getUrlDecoder().decode(parts[1])) }.getOrNull()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val out = s.getOutputStream()
                if (method == "OPTIONS") {
                    respond(out, 204, "No Content", emptyMap(), null)
                    return
                }
                val target = unwrap(parts[1].substringBefore('?'))
                if (target == null || (method != "GET" && method != "HEAD")) {
                    respond(out, 404, "Not Found", emptyMap(), null)
                    return
                }
                proxy(target, method, headers["range"], out)
            } catch (_: SocketException) {
                // The TV closed the connection (channel change, seek): nothing to do.
            } catch (_: IOException) {
            }
        }
    }

    private fun proxy(target: String, method: String, range: String?, out: OutputStream) {
        val req = Request.Builder().url(target).apply { if (range != null) header("Range", range) }.build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            respond(out, 502, "Bad Gateway", emptyMap(), null)
            return
        }
        resp.use { r ->
            val type = r.header("Content-Type").orEmpty()
            val finalUrl = r.request.url.toString()
            val isPlaylist = r.isSuccessful && (
                type.contains("mpegurl", true) ||
                    finalUrl.substringBefore('?').endsWith(".m3u8", true) ||
                    target.substringBefore('?').endsWith(".m3u8", true)
                )
            if (isPlaylist) {
                val text = r.body?.string().orEmpty()
                val body = rewritePlaylist(text, finalUrl).toByteArray()
                respond(out, r.code, r.message.ifBlank { "OK" }, mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to body.size.toString()), if (method == "HEAD") null else body)
                return
            }
            val h = LinkedHashMap<String, String>()
            h["Content-Type"] = type.ifBlank { "video/mp2t" }
            r.header("Content-Length")?.let { h["Content-Length"] = it }
            r.header("Content-Range")?.let { h["Content-Range"] = it }
            r.header("Accept-Ranges")?.let { h["Accept-Ranges"] = it }
            writeHead(out, r.code, r.message.ifBlank { "OK" }, h)
            if (method == "GET") r.body?.byteStream()?.let { copy(it, out) }
            out.flush()
        }
    }

    /** Points every URI in an HLS playlist (segments, variants, keys, subtitles) at the relay. */
    fun rewritePlaylist(text: String, baseUrl: String): String {
        val base = baseUrl.toHttpUrlOrNull()
        fun abs(u: String): String = base?.resolve(u.trim())?.toString() ?: u.trim()
        val uriAttr = Regex("""URI="([^"]+)"""")
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"${wrap(abs(m.groupValues[1]))}\"" }
                else -> wrap(abs(t))
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, msg: String, headers: Map<String, String>, body: ByteArray?) {
        val h = LinkedHashMap(headers)
        if ("Content-Length" !in h) h["Content-Length"] = (body?.size ?: 0).toString()
        writeHead(out, code, msg, h)
        if (body != null) out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, msg: String, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Content-Type\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }
}
