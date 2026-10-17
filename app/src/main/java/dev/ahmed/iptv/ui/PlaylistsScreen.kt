package dev.ahmed.iptv.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahmed.iptv.App
import dev.ahmed.iptv.data.Playlist
import dev.ahmed.iptv.data.PlaylistType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun ago(ms: Long): String {
    if (ms == 0L) return "never"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} d ago"
    }
}

@Composable
fun PlaylistsScreen(playlists: List<Playlist>, activeId: Long, onAdd: () -> Unit, onEdit: (Playlist) -> Unit) {
    val app = App.instance
    var confirmDelete by remember { mutableStateOf<Playlist?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Playlists", modifier = Modifier.weight(1f))
            IconButton(onClick = onAdd, modifier = Modifier.testTag("addPlaylist")) { Icon(Icons.Filled.Add, "Add playlist", tint = Pal.accent) }
        }
        StatusBanner(app.status, app.refreshing) { app.status = null }
        if (playlists.isEmpty()) {
            EmptyState("No playlists yet", "Add your provider's Xtream Codes login or an M3U link to get started.", "Add playlist", onAdd)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(playlists, key = { it.id }) { p ->
                    PlaylistCard(p, p.id == activeId, onSelect = { app.setActive(p.id); app.refreshIfStale() },
                        onRefresh = { app.refresh(p.id) }, onEdit = { onEdit(p) }, onDelete = { confirmDelete = p })
                }
            }
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("Its channels, favorites and guide data will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    app.scope.launch {
                        app.db.deletePlaylist(p.id)
                        val remaining = app.db.playlists()
                        withContext(Dispatchers.Main) {
                            if (app.player.channel != null && p.id == activeId) app.player.stop()
                            if (p.id == activeId) app.setActive(remaining.firstOrNull()?.id ?: 0L) else app.changed()
                        }
                    }
                }) { Text("Delete", color = Pal.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaylistCard(p: Playlist, active: Boolean, onSelect: () -> Unit, onRefresh: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.surface)
            .border(2.dp, if (active) Pal.accent else Pal.surface2, RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp)
            .testTag("playlist_${p.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (p.type == PlaylistType.XTREAM) Pal.accentDim else Pal.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (p.type == PlaylistType.XTREAM) "XC" else "M3U", color = Pal.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.name, color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = if (p.type == PlaylistType.XTREAM) "Xtream" else "M3U"
            Text("$kind · ${NumberFormat.getIntegerInstance().format(p.channelCount)} channels", color = Pal.muted, fontSize = 12.sp)
            val exp = if (p.expires > 0) " · expires ${dateFmt.format(Instant.ofEpochMilli(p.expires).atZone(ZoneId.systemDefault()))}" else ""
            Text("Updated ${ago(p.lastRefresh)}$exp", color = Pal.muted, fontSize = 12.sp)
        }
        if (active) Icon(Icons.Filled.Check, "Active", tint = Pal.accent)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options", tint = Pal.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Refresh now") }, onClick = { menu = false; onRefresh() })
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Delete", color = Pal.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Bottom sheet for adding or editing a playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(editing: Playlist?, onDismiss: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var xtream by rememberSaveable { mutableStateOf(editing?.type != PlaylistType.M3U) }
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var server by rememberSaveable { mutableStateOf(if (editing?.type == PlaylistType.XTREAM) editing?.url.orEmpty() else "") }
    var user by rememberSaveable { mutableStateOf(editing?.username.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf(editing?.password.orEmpty()) }
    var m3u by rememberSaveable { mutableStateOf(if (editing?.type == PlaylistType.M3U) editing?.url.orEmpty() else "") }
    var epg by rememberSaveable { mutableStateOf(editing?.epgUrl.orEmpty()) }
    var hls by rememberSaveable { mutableStateOf(editing?.streamFormat == "m3u8") }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            m3u = uri.toString()
            if (name.isBlank()) name = "My playlist"
        }
    }

    fun save() {
        val valid = if (xtream) server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() else m3u.isNotBlank()
        if (!valid) {
            error = if (xtream) "Enter the server URL, username and password." else "Enter a playlist URL or pick a file."
            return
        }
        val raw = Playlist(
            id = editing?.id ?: 0L,
            name = name.trim().ifBlank { if (xtream) "Xtream" else "M3U" },
            type = if (xtream) PlaylistType.XTREAM else PlaylistType.M3U,
            url = if (xtream) server else m3u,
            username = if (xtream) user else "",
            password = if (xtream) pass else "",
            epgUrl = epg.trim(),
            streamFormat = if (hls) "m3u8" else "ts",
            lastRefresh = 0,
            expires = editing?.expires ?: 0,
        )
        val p = app.repo.normalize(raw)
        app.scope.launch {
            val id = app.db.savePlaylist(p)
            withContext(Dispatchers.Main) {
                app.setActive(id)
                app.status = if (p.type == PlaylistType.XTREAM && !xtream) "That link is an Xtream login, so it was added as Xtream." else null
                app.refresh(id)
                onDismiss()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Pal.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (editing == null) "Add playlist" else "Edit playlist", color = Pal.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.bg).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Segment("Xtream Codes", xtream, Modifier.weight(1f)) { xtream = true; error = null }
                Segment("M3U", !xtream, Modifier.weight(1f)) { xtream = false; error = null }
            }
            Field("Name", name, { name = it }, "e.g. Home", "fieldName")
            if (xtream) {
                Field("Server URL", server, { server = it }, "http://provider.example:8080", "fieldServer", KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Username", user, { user = it }, "", "fieldUser", modifier = Modifier.weight(1f))
                    Field("Password", pass, { pass = it }, "", "fieldPass", KeyboardType.Password, password = true, modifier = Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { hls = !hls }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Stream format: ${if (hls) "HLS (.m3u8)" else "MPEG-TS (.ts)"}", color = Pal.text, fontSize = 14.sp)
                        Text("Try HLS if channels keep buffering or won't start.", color = Pal.muted, fontSize = 12.sp)
                    }
                    Text("Change", color = Pal.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("The guide is loaded from your provider automatically.", color = Pal.muted, fontSize = 12.sp)
            } else {
                Field("Playlist URL", if (m3u.startsWith("content://")) "Picked file" else m3u, { m3u = it }, "http://…/playlist.m3u", "fieldM3u", KeyboardType.Uri)
                SecondaryButton("Pick a file instead", { picker.launch(arrayOf("*/*")) })
                Field("EPG / XMLTV URL (optional)", epg, { epg = it }, "http://…/guide.xml.gz", "fieldEpg", KeyboardType.Uri)
                Text("Links with a username and password switch to Xtream automatically.", color = Pal.muted, fontSize = 12.sp)
            }
            error?.let { Text(it, color = Pal.error, fontSize = 13.sp) }
            Spacer(Modifier.height(2.dp))
            PrimaryButton(if (editing == null) "Add playlist" else "Save", { save() }, Modifier.fillMaxWidth().testTag("savePlaylist"))
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Pal.raised else Pal.bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Pal.text else Pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Pal.muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Pal.accent,
            unfocusedBorderColor = Pal.raised,
            focusedLabelColor = Pal.accent,
            unfocusedLabelColor = Pal.text2,
            cursorColor = Pal.accent,
            focusedTextColor = Pal.text,
            unfocusedTextColor = Pal.text,
            focusedContainerColor = Pal.bg,
            unfocusedContainerColor = Pal.bg,
        ),
        modifier = modifier.testTag(tag),
    )
}
