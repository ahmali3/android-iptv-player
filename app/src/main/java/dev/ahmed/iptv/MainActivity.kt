package dev.ahmed.iptv

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.ahmed.iptv.data.ChannelRow
import dev.ahmed.iptv.data.Playlist
import dev.ahmed.iptv.data.Program
import dev.ahmed.iptv.ui.BottomBar
import dev.ahmed.iptv.ui.ChannelsScreen
import dev.ahmed.iptv.ui.EmptyState
import dev.ahmed.iptv.ui.GroupsScreen
import dev.ahmed.iptv.ui.GuideScreen
import dev.ahmed.iptv.ui.IptvTheme
import dev.ahmed.iptv.ui.MiniPlayer
import dev.ahmed.iptv.ui.Pal
import dev.ahmed.iptv.ui.PlayerScreen
import dev.ahmed.iptv.ui.PlaylistSheet
import dev.ahmed.iptv.ui.PlaylistsScreen
import dev.ahmed.iptv.ui.SideBar
import dev.ahmed.iptv.ui.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var inPip by mutableStateOf(false)
    private var playerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        App.instance.refreshIfStale()
        setContent { IptvTheme { Root() } }
    }

    @Composable
    private fun Root() {
        val app = App.instance
        val holder = app.player
        var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
        var openList by rememberSaveable { mutableStateOf<String?>(null) }
        var player by rememberSaveable { mutableStateOf(false) }
        var sheet by rememberSaveable { mutableStateOf(false) }
        var editingId by rememberSaveable { mutableStateOf(0L) }
        // Keeps the browsing screens' state (search text and results, scroll position) while the player is open.
        val browseState = rememberSaveableStateHolder()
        val tv = app.isTv

        val playlists by produceState<List<Playlist>?>(null, app.dataVersion) {
            value = withContext(Dispatchers.IO) { app.db.playlists() }
        }
        val active = playlists?.let { all -> all.firstOrNull { it.id == app.activePlaylistId } ?: all.firstOrNull() }

        // The player only makes sense while something is loaded.
        if (player && holder.channel == null) player = false
        LaunchedEffect(player) { setPlayerMode(player) }

        fun play(list: List<ChannelRow>, index: Int) {
            val p = active ?: return
            holder.play(list, index, p.id)
            player = true
        }
        fun catchup(ch: ChannelRow, prog: Program) {
            val p = active ?: return
            val url = app.repo.catchupUrl(p, ch, prog) ?: return
            holder.playCatchup(ch, url, prog.title)
            player = true
        }

        BackHandler(enabled = player || openList != null || tab != Tab.LIVE) {
            when {
                player -> player = false
                tab == Tab.LIVE && openList != null -> openList = null
                else -> tab = Tab.LIVE
            }
        }

        @Composable
        fun Content(modifier: Modifier) {
            Box(modifier) {
                val list = playlists
                when {
                    list == null -> Unit
                    active == null && tab != Tab.PLAYLISTS -> EmptyState(
                        "Welcome",
                        "Add your provider's Xtream Codes login or an M3U playlist link to start watching.",
                        "Add playlist",
                    ) { editingId = 0L; sheet = true }
                    tab == Tab.LIVE && openList == null -> GroupsScreen(active!!, list, { openList = it }, ::play) { editingId = 0L; sheet = true }
                    tab == Tab.LIVE -> ChannelsScreen(active!!, openList!!, { openList = null }, ::play)
                    tab == Tab.GUIDE -> GuideScreen(active!!, ::play, ::catchup)
                    else -> PlaylistsScreen(list, active?.id ?: 0L, { editingId = 0L; sheet = true }) { editingId = it.id; sheet = true }
                }
            }
        }
        val onTab: (Tab) -> Unit = { if (it == tab && it == Tab.LIVE) openList = null; tab = it }

        Box(Modifier.fillMaxSize().background(Pal.bg)) {
            if (player) {
                PlayerScreen(inPip, onClose = { player = false }, onPip = { enterPip() })
            } else browseState.SaveableStateProvider("browse") {
                if (tv) {
                    // TV: tabs on the left, and a margin so nothing sits at the very edge of the screen.
                    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
                        SideBar(tab, onTab)
                        Column(Modifier.weight(1f)) {
                            Content(Modifier.weight(1f))
                            MiniPlayer { player = true }
                        }
                    }
                } else Column(Modifier.fillMaxSize()) {
                    Content(Modifier.weight(1f).statusBarsPadding())
                    MiniPlayer { player = true }
                    BottomBar(tab, onTab)
                }
            }
            if (sheet) {
                val editing = playlists?.firstOrNull { it.id == editingId }
                PlaylistSheet(editing) { sheet = false }
            }
        }
    }

    /** Landscape + immersive while the full-screen player is open. */
    private fun setPlayerMode(on: Boolean) {
        playerOpen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        setPictureInPictureParams(pipParams(autoEnter = on && !App.instance.player.cast.casting))
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(autoEnter)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    /** Android 11 and older can't enter picture-in-picture by themselves when you go home. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && playerOpen && !App.instance.player.cast.casting && App.instance.player.channel != null) {
            runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }
        }
    }

    private fun enterPip() {
        if (App.instance.player.channel != null && !App.instance.player.cast.casting) enterPictureInPictureMode(pipParams(autoEnter = playerOpen))
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        App.instance.player.appVisible = true
    }

    override fun onStop() {
        super.onStop()
        App.instance.player.appVisible = isInPictureInPictureMode
        // Keep playing in picture-in-picture; otherwise stop using data in the background.
        if (!isInPictureInPictureMode && !isChangingConfigurations) App.instance.player.pause()
    }
}
