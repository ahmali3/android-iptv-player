package dev.ahmed.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahmed.iptv.App
import dev.ahmed.iptv.data.ChannelRow
import dev.ahmed.iptv.data.GroupInfo
import dev.ahmed.iptv.data.Lists
import dev.ahmed.iptv.data.Playlist
import dev.ahmed.iptv.data.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val DP_PER_MIN = 4f
private const val HALF_HOUR = 30L * 60_000

private data class GuideData(val channels: List<ChannelRow>, val programs: Map<String, List<Program>>)

@Composable
fun GuideScreen(
    playlist: Playlist,
    onPlay: (List<ChannelRow>, Int) -> Unit,
    onCatchup: (ChannelRow, Program) -> Unit,
) {
    val app = App.instance
    val version = app.dataVersion
    val now = rememberNow()
    // Window: from 2 h ago (rounded to the half hour) to 24 h ahead.
    val from = remember(now / HALF_HOUR) { (now - 2 * 60 * 60_000L) / HALF_HOUR * HALF_HOUR }
    val to = from + 26L * 60 * 60_000

    val groups by produceState<List<GroupInfo>>(emptyList(), playlist.id, version) {
        value = withContext(Dispatchers.IO) { app.db.groups(playlist.id).filter { !it.hidden } }
    }
    val favCount by produceState(0, playlist.id, version) {
        value = withContext(Dispatchers.IO) { app.db.favoriteCount(playlist.id) }
    }
    var list by rememberSaveable(playlist.id) { mutableStateOf<String?>(null) }
    val current = list ?: if (favCount > 0) Lists.FAVORITES else groups.firstOrNull()?.name

    val data by produceState<GuideData?>(null, playlist.id, current, version, from) {
        val l = current ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val chans = app.db.channels(playlist.id, l, System.currentTimeMillis())
            GuideData(chans, app.db.programs(playlist.id, chans.map { it.epgId }.filter { it.isNotBlank() }, from, to))
        }
    }
    var selected by remember { mutableStateOf<Pair<ChannelRow, Program>?>(null) }

    val density = LocalDensity.current
    val pxPerMin = with(density) { DP_PER_MIN.dp.toPx() }
    fun xOf(t: Long) = (t - from) / 60_000f * pxPerMin
    var offset by remember(playlist.id) { mutableFloatStateOf(-1f) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle("Guide", modifier = Modifier.weight(1f))
            SecondaryButton("Now", { offset = -1f })
        }
        ListPicker(current, groups, favCount > 0) { list = it }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val logoW = 72.dp
            val logoPx = with(density) { logoW.toPx() }
            val trackW = constraints.maxWidth - logoPx
            val maxOffset = (xOf(to) - trackW).coerceAtLeast(0f)
            if (offset < 0f) offset = (xOf(now) - trackW * 0.25f).coerceIn(0f, maxOffset)
            val drag = rememberDraggableState { delta -> offset = (offset - delta).coerceIn(0f, maxOffset) }

            Column(
                Modifier.fillMaxSize().draggable(drag, Orientation.Horizontal).testTag("guideGrid"),
            ) {
                // time axis
                Row(Modifier.fillMaxWidth().height(32.dp)) {
                    Spacer(Modifier.width(logoW))
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        var t = from
                        while (t < to) {
                            val x = xOf(t)
                            Text(
                                clock(t), color = Pal.muted, fontSize = 12.sp, fontFamily = Pal.mono,
                                modifier = Modifier.offset { IntOffset((x - offset).roundToInt() + 6, 16) }
                                    .wrapContentWidth(Alignment.Start, unbounded = true),
                            )
                            t += HALF_HOUR
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.line))
                val d = data
                when {
                    current == null -> EmptyState("No channels yet", "Add a playlist first.")
                    d == null -> Spacer(Modifier.weight(1f))
                    d.channels.isEmpty() -> EmptyState("Nothing here", "This list has no channels.")
                    else -> Box(Modifier.weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(d.channels, key = { it.id }) { ch ->
                                GuideRow(ch, d.programs[ch.epgId].orEmpty(), now, logoW, { offset }, ::xOf) { p -> selected = ch to p }
                            }
                        }
                        // now line
                        Box(
                            Modifier
                                .offset { IntOffset((logoPx + xOf(now) - offset).roundToInt(), 0) }
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Pal.accent),
                        )
                    }
                }
            }
        }

        val sel = selected
        if (sel != null) ProgramCard(sel.first, sel.second, now, onClose = { selected = null },
            onWatch = {
                val chans = data?.channels.orEmpty()
                onPlay(chans, chans.indexOfFirst { it.id == sel.first.id }.coerceAtLeast(0))
            },
            onCatchup = { onCatchup(sel.first, sel.second) })
    }
}

@Composable
private fun ListPicker(current: String?, groups: List<GroupInfo>, hasFav: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Pal.surface)
                .border(1.dp, Pal.line, RoundedCornerShape(18.dp))
                .clickable { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current?.let { Lists.label(it) } ?: "Choose a list", color = Pal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.ArrowDropDown, "Choose list", tint = Pal.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasFav) DropdownMenuItem(text = { Text("Favorites") }, onClick = { open = false; onPick(Lists.FAVORITES) })
            groups.forEach { g ->
                DropdownMenuItem(text = { Text(g.name) }, onClick = { open = false; onPick(g.name) })
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: ChannelRow,
    programs: List<Program>,
    now: Long,
    logoW: androidx.compose.ui.unit.Dp,
    offset: () -> Float,
    xOf: (Long) -> Float,
    onSelect: (Program) -> Unit,
) {
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().height(64.dp)) {
        Box(Modifier.width(logoW).fillMaxHeight(), contentAlignment = Alignment.Center) { ChannelLogo(ch.name, ch.logo, 44.dp) }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (programs.isEmpty()) {
                Text(
                    "No guide data", color = Pal.muted, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
            }
            for (p in programs) {
                val w = with(density) { ((p.stop - p.start) / 60_000f * DP_PER_MIN).dp } - 4.dp
                val live = p.start <= now && p.stop > now
                val past = p.stop <= now
                Column(
                    Modifier
                        .offset { IntOffset((xOf(p.start) - offset()).roundToInt() + 2, 6.dp.roundToPx()) }
                        // Programs can be wider than the visible track; let them overflow (clipped by the row).
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(w.coerceAtLeast(8.dp))
                        .height(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (live) Pal.raised else if (past) Pal.surface else Pal.surface2)
                        .clickable { onSelect(p) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    val blockPx = with(density) { w.coerceAtLeast(8.dp).toPx() }
                    val keepPx = with(density) { 100.dp.toPx() }
                    // When a show started before the visible area, keep its title on screen.
                    Column(
                        Modifier.offset {
                            val hidden = offset() - (xOf(p.start) + 2)
                            IntOffset(hidden.coerceIn(0f, (blockPx - keepPx).coerceAtLeast(0f)).roundToInt(), 0)
                        },
                    ) {
                        Text(
                            p.title, color = if (past) Pal.muted else Pal.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(span(p.start, p.stop), color = Pal.muted, fontSize = 11.sp, fontFamily = Pal.mono, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
private fun ProgramCard(ch: ChannelRow, p: Program, now: Long, onClose: () -> Unit, onWatch: () -> Unit, onCatchup: () -> Unit) {
    val live = p.start <= now && p.stop > now
    val past = p.stop <= now
    val canCatchup = ch.catchupDays > 0 && p.start < now && p.start > now - ch.catchupDays * 24L * 60 * 60_000
    Column(
        Modifier
            .padding(12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.surface)
            .border(1.dp, Pal.line, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)
            .testTag("programCard"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ch.name} · ${dayFmt.format(Instant.ofEpochMilli(p.start).atZone(ZoneId.systemDefault()))} · ${span(p.start, p.stop)}",
                color = Pal.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Pal.muted) }
        }
        Text(p.title, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
        if (p.desc.isNotBlank()) {
            Text(p.desc, color = Pal.text2, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
        }
        Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (live) "Watch live" else "Watch channel", onWatch, Modifier.weight(1f))
            if (canCatchup) SecondaryButton(if (past) "Watch from start" else "Start over", onCatchup, Modifier.weight(1f).height(50.dp))
        }
    }
}
