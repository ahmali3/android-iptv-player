package dev.ahmed.iptv.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C as MC
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.ahmed.iptv.App
import dev.ahmed.iptv.data.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val aspectModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

private val scrim = Color(0x99000000)

@Composable
fun PlayerScreen(inPip: Boolean, onClose: () -> Unit, onPip: () -> Unit) {
    val app = App.instance
    val holder = app.player
    val ch = holder.channel
    var overlay by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var aspect by rememberSaveable { mutableIntStateOf(0) }
    var trackDialog by remember { mutableStateOf<Int?>(null) }
    var castDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val now = rememberNow()
    val context = LocalContext.current
    val cast = holder.cast
    val casting = cast.casting

    LaunchedEffect(overlay, poke, showList, casting, menu) {
        // While casting there is no video on the phone, so the controls stay up.
        if (overlay && !showList && !casting && !menu) {
            delay(4_000)
            overlay = false
        }
    }
    fun touch() {
        overlay = true
        poke++
    }

    val programs by produceState<List<Program>>(emptyList(), ch?.id, now / 60_000, app.dataVersion) {
        val c = ch ?: return@produceState
        if (c.epgId.isBlank()) {
            value = emptyList(); return@produceState
        }
        val t = System.currentTimeMillis()
        value = withContext(Dispatchers.IO) {
            app.db.programs(app.activePlaylistId, listOf(c.epgId), t, t + 8 * 60 * 60_000L)[c.epgId].orEmpty()
        }
    }
    val current = programs.firstOrNull { it.start <= now && it.stop > now }
    val next = programs.firstOrNull { it.start >= (current?.stop ?: now) }

    val swipe = with(LocalDensity.current) { 80.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("player")
            .pointerInput(inPip) {
                if (!inPip) detectTapGestures(onTap = { if (overlay) overlay = false else touch() }, onDoubleTap = { holder.togglePause(); touch() })
            }
            .pointerInput(inPip) {
                if (!inPip) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -swipe) holder.zap(+1) else if (total > swipe) holder.zap(-1)
                            if (kotlin.math.abs(total) > swipe) touch()
                        },
                        onVerticalDrag = { _, dy -> total += dy },
                    )
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    player = holder.player
                }
            },
            update = { it.resizeMode = aspectModes[aspect].first },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (casting) {
            Column(
                // Sits above the play/pause button in the middle.
                Modifier.align(Alignment.Center).padding(bottom = 140.dp).testTag("castingNow"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ch != null) ChannelLogo(ch.name, ch.logo, 56.dp)
                Text("Playing on ${cast.deviceName}", color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (cast.viaPhone) Text("Sent through your phone", color = Pal.muted, fontSize = 13.sp)
            }
        }

        if (holder.buffering && holder.error == null) {
            CircularProgressIndicator(color = Pal.accent, modifier = Modifier.align(Alignment.Center).size(48.dp))
        }
        val err = holder.error
        if (err != null && !inPip) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(16.dp)).background(scrim).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(err, color = Pal.text, fontSize = 15.sp, modifier = Modifier.testTag("playerError"))
                if (!err.startsWith("Reconnecting")) SecondaryButton("Try again", { ch?.let { holder.zap(0) } })
            }
        }

        if (!inPip) AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // top bar
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().background(scrim).safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pal.text) }
                    if (ch != null) {
                        Text(ch.number.toString(), color = Pal.muted, fontSize = 13.sp, fontFamily = Pal.mono)
                        Text(ch.name, color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Pill(if (holder.catchupTitle != null) "CATCH-UP" else "LIVE")
                    }
                    Box(Modifier.weight(1f))
                    if (!casting) {
                        TextAction("Audio") { trackDialog = MC.TRACK_TYPE_AUDIO; touch() }
                        TextAction("Subtitles") { trackDialog = MC.TRACK_TYPE_TEXT; touch() }
                        TextAction(aspectModes[aspect].second) { aspect = (aspect + 1) % aspectModes.size; touch() }
                    }
                    if (cast.available) TextAction(if (casting) "Casting" else "Cast", if (casting) Pal.accent else Pal.text, "castButton") { castDialog = true; touch() }
                    IconButton(onClick = { showList = !showList; touch() }) { Icon(Icons.Filled.List, "Channel list", tint = if (showList) Pal.accent else Pal.text) }
                    Box {
                        IconButton(onClick = { menu = true; touch() }, modifier = Modifier.testTag("playerMenu")) { Icon(Icons.Filled.MoreVert, "More", tint = Pal.text) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!casting) DropdownMenuItem(text = { Text("Picture-in-picture") }, onClick = { menu = false; onPip() })
                            DropdownMenuItem(
                                text = { Text("Open in another app (VLC…)") },
                                modifier = Modifier.testTag("openExternal"),
                                onClick = {
                                    menu = false
                                    val url = holder.currentUrl
                                    if (url != null && ch != null) {
                                        if (!casting) holder.pause()
                                        openInOtherApp(context, url, holder.catchupTitle?.let { "${ch.name} · $it" } ?: ch.name)
                                    }
                                },
                            )
                        }
                    }
                }
                // center play/pause
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(scrim)
                        .clickable { holder.togglePause(); touch() }
                        .testTag("playPause"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (holder.isPlaying) PauseGlyph(Pal.text, 26.dp) else Icon(Icons.Filled.PlayArrow, "Play", tint = Pal.text, modifier = Modifier.size(34.dp))
                }
                // bottom info
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(scrim).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val title = holder.catchupTitle ?: current?.title ?: ch?.nowTitle ?: "No guide information"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (current != null && holder.catchupTitle == null) Text(span(current.start, current.stop), color = Pal.text2, fontSize = 12.sp, fontFamily = Pal.mono)
                    }
                    if (current != null && holder.catchupTitle == null) {
                        ProgressLine(progressOf(current.start, current.stop, now), Modifier.fillMaxWidth(), 4.dp, Color(0xFF3A4048))
                    }
                    if (next != null && holder.catchupTitle == null) Text("Next · ${clock(next.start)} ${next.title}", color = Pal.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Swipe up or down to change channel", color = Pal.muted, fontSize = 12.sp)
                }
            }
        }

        if (showList && !inPip) ChannelPanel(Modifier.align(Alignment.CenterEnd)) { showList = false }

        trackDialog?.let { type -> TrackDialog(type) { trackDialog = null } }
        if (castDialog) CastDialog { castDialog = false }
    }
}

@Composable
private fun ChannelPanel(modifier: Modifier, onClose: () -> Unit) {
    val holder = App.instance.player
    val list = holder.currentList()
    val cur = holder.channel
    val start = list.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
    val state = rememberLazyListState((start - 2).coerceAtLeast(0))
    Column(
        modifier.width(300.dp).fillMaxHeight().background(Color(0xF0121519)).safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Channels", color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextAction("Close", onClick = onClose)
        }
        LazyColumn(state = state, modifier = Modifier.testTag("channelPanel")) {
            itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                val on = c.id == cur?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Pal.raised else Color.Transparent)
                        .border(1.dp, if (on) Pal.accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { holder.play(list, i, App.instance.activePlaylistId) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(c.number.toString(), color = Pal.muted, fontSize = 11.sp, fontFamily = Pal.mono, modifier = Modifier.width(30.dp), maxLines = 1)
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Pal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        c.nowTitle?.let { Text(it, color = Pal.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackDialog(type: Int, onDismiss: () -> Unit) {
    val holder = App.instance.player
    val options = remember(holder.tracksVersion, type) { holder.tracks(type) }
    val subsOn = holder.subtitlesEnabled()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == MC.TRACK_TYPE_AUDIO) "Audio" else "Subtitles") },
        text = {
            Column {
                if (options.isEmpty()) Text(if (type == MC.TRACK_TYPE_AUDIO) "This stream has a single audio track." else "This stream has no subtitles.", color = Pal.text2)
                if (type == MC.TRACK_TYPE_TEXT && options.isNotEmpty()) {
                    TrackRow("Off", !subsOn) { holder.subtitlesOff(); onDismiss() }
                }
                options.forEach { o ->
                    TrackRow(o.label, o.selected && (type != MC.TRACK_TYPE_TEXT || subsOn)) { holder.select(o); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, "Selected", tint = Pal.accent)
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        color = Pal.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Pal.accent).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun TextAction(label: String, color: Color = Pal.text, tag: String? = null, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).height(44.dp).padding(horizontal = 10.dp, vertical = 13.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}
