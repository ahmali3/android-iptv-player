package dev.ahmed.iptv

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Drives the real app against a fake IPTV provider running on the emulator:
 * adds an Xtream playlist, checks groups, channels and the guide, plays a channel whose
 * audio is AC3 (Dolby Digital, which only plays through the bundled FFmpeg decoder),
 * then adds an M3U playlist. Screenshots are saved for the README.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()
    private val ts: ByteArray by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("test.ts").use { it.readBytes() }
    }
    private val now = System.currentTimeMillis()
    private val xmltvFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private fun t(ms: Long) = xmltvFmt.format(Instant.ofEpochMilli(ms))

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val action = request.requestUrl?.queryParameter("action")
                return when {
                    path == "/player_api.php" && action == null ->
                        json("""{"user_info":{"username":"demo","auth":1,"status":"Active","exp_date":"1893456000"},"server_info":{}}""")
                    path == "/player_api.php" && action == "get_live_categories" ->
                        json("""[{"category_id":"1","category_name":"SOCCER","parent_id":0},{"category_id":"2","category_name":"KIDS","parent_id":0},{"category_id":"3","category_name":"AR | NEWS","parent_id":0}]""")
                    path == "/player_api.php" && action == "get_live_streams" -> json(
                        """[
                          {"num":1,"name":"Pitch One","stream_id":101,"stream_icon":"","epg_channel_id":"pitch1","category_id":"1","tv_archive":1,"tv_archive_duration":3},
                          {"num":2,"name":"Pitch Two","stream_id":102,"stream_icon":"","epg_channel_id":"pitch2","category_id":"1","tv_archive":0},
                          {"num":3,"name":"Cartoon Hub","stream_id":103,"stream_icon":"","epg_channel_id":"","category_id":"2","tv_archive":0},
                          {"num":4,"name":"Al Akhbar","stream_id":104,"stream_icon":"","epg_channel_id":"akhbar","category_id":"3","tv_archive":0}
                        ]"""
                    )
                    path == "/xmltv.php" -> MockResponse().setBody(xmltv())
                    path.startsWith("/live/") && path.endsWith(".ts") ->
                        MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ts))
                    path == "/list.m3u" -> MockResponse().setBody(
                        "#EXTM3U url-tvg=\"${server.url("/xmltv.php")}\"\n" +
                            "#EXTINF:-1 tvg-id=\"pitch1\" group-title=\"MOVIES\",Film Four\n${server.url("/live/m/m/201.ts")}\n" +
                            "#EXTINF:-1 group-title=\"MUSIC\",Hits Radio\n${server.url("/live/m/m/202.ts")}\n"
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        rule.runOnUiThread { App.instance.player.stop() }
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun xmltv(): String {
        val h = 60 * 60_000L
        val start = now - now % (30 * 60_000L) - h
        fun prog(ch: String, from: Long, to: Long, title: String) =
            """<programme start="${t(from)}" stop="${t(to)}" channel="$ch"><title>$title</title><desc>About $title.</desc></programme>"""
        return """<?xml version="1.0" encoding="UTF-8"?><tv>
            <channel id="pitch1"><display-name>Pitch One</display-name></channel>
            <channel id="pitch2"><display-name>Pitch Two</display-name></channel>
            <channel id="cartoon"><display-name>Cartoon Hub</display-name></channel>
            <channel id="akhbar"><display-name>Al Akhbar</display-name></channel>
            ${prog("pitch1", start, start + 2 * h, "Derby Day Live")}
            ${prog("pitch1", start + 2 * h, start + 4 * h, "Post-match Analysis")}
            ${prog("pitch2", start, start + 3 * h, "Classic Matches")}
            ${prog("cartoon", start, start + 3 * h, "Saturday Cartoons")}
            ${prog("akhbar", start, start + h + 30 * 60_000L, "Evening Bulletin")}
            ${prog("akhbar", start + h + 30 * 60_000L, start + 3 * h, "World Report")}
            </tv>"""
    }

    private fun waitFor(timeoutMs: Long = 20_000, what: String, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(200)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun textExists(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600)
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(rule.activity.filesDir, "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** ExoPlayer may only be touched on the main thread. */
    private fun <T> onUi(block: () -> T): T {
        var result: Result<T>? = null
        rule.runOnUiThread { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Brings back the player controls, which hide after a few seconds. */
    private fun showControls() {
        if (!exists("playerMenu")) rule.onNodeWithTag("player").performClick()
        waitFor(what = "player controls") { exists("playerMenu") }
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun fullFlow() {
        val base = server.url("/").toString().trimEnd('/')

        // ---- first launch: welcome, add an Xtream playlist ----
        waitFor(what = "welcome screen") { textExists("Welcome") }
        screenshot("01_welcome")
        rule.onNodeWithText("Add playlist").performClick()
        waitFor(what = "add sheet") { exists("fieldServer") }
        rule.onNodeWithTag("fieldName").performTextInput("Home")
        rule.onNodeWithTag("fieldServer").performTextInput(base)
        rule.onNodeWithTag("fieldUser").performTextInput("demo")
        rule.onNodeWithTag("fieldPass").performTextInput("secret")
        Espresso.closeSoftKeyboard()
        screenshot("02_add_playlist")
        rule.onNodeWithTag("savePlaylist").performScrollTo().performClick()

        // ---- groups come from the provider, in its order ----
        waitFor(30_000, "provider groups") { exists("group_SOCCER") && exists("group_AR | NEWS") }
        waitFor(30_000, "guide loaded") { !App.instance.refreshing }
        screenshot("03_groups")

        // ---- channels in a group, with what's on now from the guide ----
        rule.onNodeWithTag("group_SOCCER").performClick()
        waitFor(what = "channels") { exists("channel_Pitch One") && exists("channel_Pitch Two") }
        waitFor(what = "now playing from the guide") { textExists("Derby Day Live") }
        assertTrue("Cartoon Hub is in another group", !exists("channel_Cartoon Hub"))
        screenshot("04_channels")

        // ---- play: video + AC3 audio through FFmpeg ----
        rule.onNodeWithTag("channel_Pitch One").performClick()
        waitFor(30_000, "playback to start") { App.instance.player.isPlaying }
        waitFor(what = "audio track") { onUi { App.instance.player.tracks(C.TRACK_TYPE_AUDIO) }.isNotEmpty() }
        val audio = onUi { App.instance.player.tracks(C.TRACK_TYPE_AUDIO) }
        assertTrue("AC3 track should be playable: $audio", audio.any { it.label.contains("Dolby Digital") && it.selected })
        Thread.sleep(2_000)
        assertTrue("still playing after 2 s", App.instance.player.isPlaying)
        screenshot("05_player")

        // swipe-to-zap equivalent
        rule.runOnUiThread { App.instance.player.zap(+1) }
        waitFor(what = "zap to next channel") { App.instance.player.channel?.name == "Pitch Two" }
        waitFor(30_000, "playback after zap") { App.instance.player.isPlaying }

        // ---- "Open in another app" hands the stream to VLC & co. through the system chooser ----
        var chooser: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser = intent
                return Instrumentation.ActivityResult(0, null) // don't actually open the chooser
            }
        }
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
        showControls()
        rule.onNodeWithTag("playerMenu").performClick()
        waitFor(what = "player menu") { exists("openExternal") }
        screenshot("09_player_menu")
        rule.onNodeWithTag("openExternal").performClick()
        waitFor(what = "chooser intent") { chooser != null }
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        @Suppress("DEPRECATION")
        val view = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertTrue("stream url: ${view.data}", view.data.toString().endsWith("/live/demo/secret/102.ts"))
        assertEquals("Pitch Two", view.getStringExtra("title"))
        waitFor(what = "phone pauses for the other app") { !App.instance.player.isPlaying }

        // ---- Cast: the TV picker opens (there are no TVs on the test network) ----
        val castAvailable = onUi { App.instance.player.cast.available }
        Log.i("dev.ahmed.test", "cast available on emulator: $castAvailable")
        if (castAvailable) {
            showControls()
            rule.onNodeWithTag("castButton").performClick()
            waitFor(what = "cast dialog") { textExists("Looking for TVs") || exists("castNone") }
            screenshot("10_cast")
            rule.onNodeWithText("Close").performClick()
            waitFor(what = "cast dialog closed") { !exists("castDialog") }
        }
        assertTrue("not casting", !onUi { App.instance.player.cast.casting })

        back() // close player
        waitFor(what = "mini player") { exists("miniPlayer") }

        // ---- favorite Pitch One (stream id 101); the guide then opens on Favorites ----
        rule.runOnUiThread {
            App.instance.db.setFavorite(App.instance.activePlaylistId, "101", true)
            App.instance.changed()
        }
        waitFor(what = "favorite star") { textExists("Favorite") || exists("channel_Pitch One") }
        back() // back to groups
        waitFor(what = "groups again") { exists("tile_Favorites") }

        // channel without tvg-id got its guide by name matching
        rule.onNodeWithTag("group_KIDS").performClick()
        waitFor(what = "name-matched guide") { textExists("Saturday Cartoons") }
        back()

        // ---- guide grid ----
        rule.onNodeWithTag("tab_Guide").performClick()
        waitFor(what = "guide grid") { exists("guideGrid") && textExists("Derby Day Live") }
        screenshot("06_guide")
        // A 2-hour program must be drawn about 480 dp wide, not squeezed to the screen width.
        val blockWidth = rule.onNodeWithText("Derby Day Live").fetchSemanticsNode().size.width
        assertTrue("guide block too narrow: $blockWidth px", blockWidth > with(rule.density) { 400.dp.toPx() })
        rule.onNodeWithText("Derby Day Live").performClick()
        waitFor(what = "program card") { exists("programCard") }
        screenshot("07_program")

        // ---- add an M3U playlist and switch to it ----
        rule.onNodeWithTag("tab_Playlists").performClick()
        waitFor(what = "playlists") { exists("playlist_Home") }
        rule.onNodeWithTag("addPlaylist").performClick()
        waitFor(what = "add sheet") { exists("fieldName") }
        rule.onNodeWithText("M3U").performClick()
        waitFor(what = "m3u fields") { exists("fieldM3u") }
        rule.onNodeWithTag("fieldName").performTextInput("Backup")
        rule.onNodeWithTag("fieldM3u").performTextInput(server.url("/list.m3u").toString())
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag("savePlaylist").performScrollTo().performClick()
        waitFor(30_000, "m3u loaded") { exists("playlist_Backup") && !App.instance.refreshing && textExists("2 channels") }
        screenshot("08_playlists")

        rule.onNodeWithTag("tab_Live").performClick()
        waitFor(what = "m3u groups") { exists("group_MOVIES") && exists("group_MUSIC") }

        // An M3U link that hides an Xtream login is converted.
        assertEquals(
            "http://h:1",
            App.instance.repo.normalize(
                dev.ahmed.iptv.data.Playlist(0, "x", dev.ahmed.iptv.data.PlaylistType.M3U, "http://h:1/get.php?username=a&password=b&type=m3u_plus")
            ).url,
        )
    }
}
