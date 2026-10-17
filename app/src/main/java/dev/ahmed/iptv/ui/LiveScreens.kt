package dev.ahmed.iptv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahmed.iptv.App
import dev.ahmed.iptv.data.ChannelRow
import dev.ahmed.iptv.data.GroupInfo
import dev.ahmed.iptv.data.Lists
import dev.ahmed.iptv.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** Current time that ticks every 30 s so progress bars move. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private data class GroupsData(val groups: List<GroupInfo>, val fav: Int, val recent: Int, val all: Int)

@Composable
fun GroupsScreen(
    playlist: Playlist,
    playlists: List<Playlist>,
    onOpenList: (String) -> Unit,
    onPlay: (List<ChannelRow>, Int) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    val app = App.instance
    val version = app.dataVersion
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    val now = rememberNow()

    val data by produceState<GroupsData?>(null, playlist.id, version) {
        value = withContext(Dispatchers.IO) {
            val db = app.db
            GroupsData(db.groups(playlist.id), db.favoriteCount(playlist.id), db.recentCount(playlist.id), db.channelCount(playlist.id))
        }
    }
    val searchResults by produceState<List<ChannelRow>>(emptyList(), playlist.id, query, version) {
        value = if (query.isBlank()) emptyList() else {
            delay(150) // debounce typing
            withContext(Dispatchers.IO) { app.db.channels(playlist.id, Lists.ALL, System.currentTimeMillis(), query) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PlaylistHeader(playlist, playlists, onAddPlaylist)
        StatusBanner(app.status, app.refreshing) { app.status = null }
        SearchField(query, { query = it }, "Search groups or channels", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        val d = data
        if (d == null) {
            Spacer(Modifier.weight(1f))
        } else if (d.all == 0) {
            Box(Modifier.weight(1f)) {
                EmptyState(
                    if (app.refreshing) "Loading channels…" else "No channels yet",
                    if (app.refreshing) "This takes a few seconds the first time." else "Pull the latest list from your provider.",
                    if (app.refreshing) null else "Refresh",
                ) { app.refresh(playlist.id) }
            }
        } else LazyColumn(Modifier.weight(1f).testTag("groupList")) {
            if (query.isNotBlank()) {
                val q = query.trim()
                val groups = d.groups.filter { it.name.contains(q, ignoreCase = true) }
                if (groups.isNotEmpty()) {
                    item { SectionLabel("Groups") }
                    items(groups, key = { "g" + it.name }) { g -> GroupRow(g, false, { onOpenList(g.name) }, {}) }
                }
                item { SectionLabel(if (searchResults.isEmpty()) "No matching channels" else "Channels") }
                itemsIndexed(searchResults, key = { _, c -> "c" + c.id }) { i, c ->
                    ChannelItem(c, now, false, { onPlay(searchResults, i) }, { toggleFav(playlist.id, c) })
                }
                return@LazyColumn
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tile("Favorites", d.fav, Icons.Filled.Star, Modifier.weight(1f)) { onOpenList(Lists.FAVORITES) }
                    Tile("Recent", d.recent, Icons.Filled.Refresh, Modifier.weight(1f)) { onOpenList(Lists.RECENT) }
                    Tile("All", d.all, Icons.Filled.List, Modifier.weight(1f)) { onOpenList(Lists.ALL) }
                }
            }
            item {
                val visible = d.groups.count { !it.hidden }
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (editing) "Tap a group to show or hide it" else "Groups · $visible",
                        color = Pal.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (editing) "Done" else "Edit",
                        color = Pal.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { editing = !editing }.padding(12.dp),
                    )
                }
            }
            val shown = if (editing) d.groups else d.groups.filter { !it.hidden }
            items(shown, key = { "g" + it.name }) { g ->
                GroupRow(g, editing, { onOpenList(g.name) }) {
                    app.scope.launch {
                        app.db.setGroupHidden(playlist.id, g.name, !g.hidden)
                        withContext(Dispatchers.Main) { app.changed() }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun PlaylistHeader(playlist: Playlist, playlists: List<Playlist>, onAdd: () -> Unit) {
    val app = App.instance
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(vertical = 4.dp)) {
                Text("PLAYLIST", color = Pal.muted, fontSize = 12.sp, letterSpacing = 0.5.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        playlist.name, color = Pal.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.Filled.ArrowDropDown, "Switch playlist", tint = Pal.muted)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                playlists.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        trailingIcon = { if (p.id == playlist.id) Icon(Icons.Filled.Check, null, tint = Pal.accent) },
                        onClick = { menu = false; app.setActive(p.id); app.refreshIfStale() },
                    )
                }
                DropdownMenuItem(text = { Text("Add playlist…") }, onClick = { menu = false; onAdd() })
            }
        }
        IconButton(onClick = { app.refresh(playlist.id) }, enabled = !app.refreshing) {
            Icon(Icons.Filled.Refresh, "Refresh playlist", tint = if (app.refreshing) Pal.muted else Pal.text)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Pal.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun Tile(label: String, count: Int, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.surface)
            .border(1.dp, Pal.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("tile_$label"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = Pal.accent, modifier = Modifier.size(20.dp))
        Text(label, color = Pal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(NumberFormat.getIntegerInstance().format(count), color = Pal.muted, fontSize = 12.sp)
    }
}

@Composable
private fun GroupRow(g: GroupInfo, editing: Boolean, onOpen: () -> Unit, onToggleHidden: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = if (editing) onToggleHidden else onOpen)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp)
            .testTag("group_${g.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            g.name, color = if (editing && g.hidden) Pal.muted else Pal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (editing) {
            Text(if (g.hidden) "Hidden" else "Shown", color = if (g.hidden) Pal.muted else Pal.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(g.count.toString(), color = Pal.muted, fontSize = 12.sp, fontFamily = Pal.mono)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Pal.muted)
        }
    }
}

@Composable
fun ChannelsScreen(
    playlist: Playlist,
    list: String,
    onBack: () -> Unit,
    onPlay: (List<ChannelRow>, Int) -> Unit,
) {
    val app = App.instance
    val version = app.dataVersion
    val now = rememberNow()
    var query by rememberSaveable(list) { mutableStateOf("") }
    val rows by produceState<List<ChannelRow>?>(null, playlist.id, list, version, now / 60_000) {
        value = withContext(Dispatchers.IO) { app.db.channels(playlist.id, list, System.currentTimeMillis()) }
    }
    val shown = remember(rows, query) {
        val r = rows.orEmpty()
        if (query.isBlank()) r else r.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val playing = app.player.channel

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to groups", tint = Pal.text) }
            Column(Modifier.weight(1f)) {
                Text(Lists.label(list), color = Pal.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${rows?.size ?: 0} channels · ${playlist.name}", color = Pal.muted, fontSize = 12.sp)
            }
        }
        SearchField(query, { query = it }, "Search in this list", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when {
            rows == null -> Spacer(Modifier.weight(1f))
            shown.isEmpty() -> Box(Modifier.weight(1f)) {
                EmptyState(
                    if (list == Lists.FAVORITES) "No favorites yet" else "Nothing here",
                    if (list == Lists.FAVORITES) "Long-press any channel to add it to your favorites." else "No channels match.",
                )
            }
            else -> LazyColumn(Modifier.weight(1f).testTag("channelList")) {
                itemsIndexed(shown, key = { _, c -> c.id }) { i, c ->
                    ChannelItem(c, now, playing?.id == c.id, { onPlay(shown, i) }, { toggleFav(playlist.id, c) })
                }
            }
        }
    }
}

fun toggleFav(playlistId: Long, c: ChannelRow) {
    val app = App.instance
    app.scope.launch {
        app.db.setFavorite(playlistId, c.key, !c.favorite)
        withContext(Dispatchers.Main) {
            app.status = if (c.favorite) "Removed ${c.name} from favorites" else "Added ${c.name} to favorites"
            app.changed()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelItem(c: ChannelRow, now: Long, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (playing) Pal.surface2 else Pal.bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Toggle favorite")
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("channel_${c.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(c.number.toString(), color = Pal.muted, fontSize = 12.sp, fontFamily = Pal.mono, modifier = Modifier.width(34.dp), maxLines = 1)
        ChannelLogo(c.name, c.logo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    c.name, color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (c.favorite) Icon(Icons.Filled.Star, "Favorite", tint = Pal.accent, modifier = Modifier.size(14.dp))
            }
            Text(
                c.nowTitle ?: if (c.epgId.isBlank()) "No guide data" else "No program info right now",
                color = if (c.nowTitle != null) Pal.text2 else Pal.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (c.nowTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressLine(progressOf(c.nowStart, c.nowStop, now), Modifier.weight(1f))
                    Text(span(c.nowStart, c.nowStop), color = Pal.muted, fontSize = 11.sp, fontFamily = Pal.mono)
                }
            }
        }
    }
}
