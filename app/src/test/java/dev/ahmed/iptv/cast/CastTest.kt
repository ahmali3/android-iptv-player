package dev.ahmed.iptv.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CastUrlsTest {
    @Test
    fun xtreamLiveBecomesHls() {
        assertEquals("http://h:8080/live/u/p/123.m3u8", CastUrls.castUrl("http://h:8080/live/u/p/123.ts"))
        assertEquals("http://h:8080/live/u/p/123.m3u8", CastUrls.castUrl("http://h:8080/u/p/123"))
        assertEquals("http://h:8080/live/u/p/123.m3u8", CastUrls.castUrl("http://h:8080/u/p/123.ts"))
    }

    @Test
    fun hlsAndOtherFormatsAreKept() {
        assertEquals("http://h/live/u/p/1.m3u8", CastUrls.castUrl("http://h/live/u/p/1.m3u8"))
        assertEquals("https://cdn/x/master.m3u8?token=1", CastUrls.castUrl("https://cdn/x/master.m3u8?token=1"))
        assertEquals("https://cdn/x/a.mpd", CastUrls.castUrl("https://cdn/x/a.mpd"))
    }

    @Test
    fun catchupAndPlainTsAskForHls() {
        assertEquals(
            "http://h/timeshift/u/p/120/2026-10-17:10-00/101.m3u8",
            CastUrls.castUrl("http://h/timeshift/u/p/120/2026-10-17:10-00/101.ts"),
        )
        assertEquals("http://h/a/b/c/stream.m3u8?x=1", CastUrls.castUrl("http://h/a/b/c/stream.ts?x=1"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/x-mpegURL", CastUrls.mimeType("http://h/live/u/p/1.m3u8"))
        assertEquals("application/dash+xml", CastUrls.mimeType("http://h/a.mpd?x"))
        assertEquals("video/mp4", CastUrls.mimeType("http://h/a.mp4"))
    }
}

class RelayTest {
    private val server = MockWebServer()
    private val segment = ByteArray(5000) { (it % 251).toByte() }
    private lateinit var relay: Relay
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/live/u/p/1.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\n/abs/seg2.ts\n"
                )
                "/live/u/p/seg1.ts" ->
                    if (request.getHeader("Range") != null) MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/5000").setBody(okio.Buffer().write(segment, 0, 4))
                    else MockResponse().setHeader("Content-Type", "video/mp2t").setBody(okio.Buffer().write(segment))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        relay = Relay(http, "127.0.0.1").start(bindAll = false)
    }

    @After
    fun tearDown() {
        relay.stop()
        server.shutdown()
    }

    private fun get(url: String, range: String? = null) =
        http.newCall(Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()).execute()

    @Test
    fun playlistIsRewrittenWithCors() {
        val r = get(relay.wrap(server.url("/live/u/p/1.m3u8").toString()))
        assertEquals(200, r.code)
        assertEquals("*", r.header("Access-Control-Allow-Origin"))
        val lines = r.body!!.string().lines()
        val segs = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, segs.size)
        assertTrue(segs.all { it.startsWith("http://127.0.0.1:") })
        assertTrue("file name kept", segs[0].endsWith("/seg1.ts"))
        val key = lines.first { it.startsWith("#EXT-X-KEY") }
        assertTrue(key, key.contains("URI=\"http://127.0.0.1:") && key.contains("/key.bin\""))

        // The rewritten segment address really fetches the segment.
        val seg = get(segs[0])
        assertEquals(200, seg.code)
        assertEquals("*", seg.header("Access-Control-Allow-Origin"))
        assertTrue(seg.body!!.bytes().contentEquals(segment))
    }

    @Test
    fun rangeIsForwarded() {
        val r = get(relay.wrap(server.url("/live/u/p/seg1.ts").toString()), "bytes=0-3")
        assertEquals(206, r.code)
        assertEquals("bytes 0-3/5000", r.header("Content-Range"))
        assertEquals(4, r.body!!.bytes().size)
    }

    @Test
    fun preflightAndWrongSecret() {
        val wrapped = relay.wrap(server.url("/live/u/p/seg1.ts").toString())
        val pre = http.newCall(Request.Builder().url(wrapped).method("OPTIONS", null).build()).execute()
        assertEquals(204, pre.code)
        assertEquals("*", pre.header("Access-Control-Allow-Origin"))

        val parts = wrapped.split('/').toMutableList()
        parts[3] = "not-the-secret"
        val bad = get(parts.joinToString("/"))
        assertEquals(404, bad.code)
        assertNotEquals(wrapped, parts.joinToString("/"))
    }
}
