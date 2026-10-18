package dev.ahmed.iptv.ui

import android.text.format.DateFormat
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.ahmed.iptv.App
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Tab { LIVE, GUIDE, PLAYLISTS }

private val fmt12 = DateTimeFormatter.ofPattern("h:mm")
private val fmt24 = DateTimeFormatter.ofPattern("HH:mm")

fun clock(ms: Long): String {
    val f = if (DateFormat.is24HourFormat(App.instance)) fmt24 else fmt12
    return f.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

fun span(start: Long, stop: Long) = "${clock(start)}–${clock(stop)}"

private val tints = listOf(
    Color(0xFF2A3A55), Color(0xFF2E4A3C), Color(0xFF4A3326), Color(0xFF45283A),
    Color(0xFF33364A), Color(0xFF2C4440), Color(0xFF4A4026), Color(0xFF3A2C4A),
)

fun initials(name: String): String {
    val words = name.replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "TV"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
fun ChannelLogo(name: String, logo: String, size: Dp = 48.dp) {
    val shape = RoundedCornerShape(size / 4)
    Box(
        Modifier.size(size).clip(shape).background(tints[(name.hashCode() and 0x7fffffff) % tints.size]),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Pal.text, fontWeight = FontWeight.Bold, fontSize = (size.value / 3.3f).sp)
        if (logo.isNotBlank()) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(Color.Transparent).padding(size / 10),
            )
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Pal.raised) {
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(Pal.accent))
    }
}

fun progressOf(start: Long, stop: Long, now: Long): Float =
    if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Pal.surface)
            .border(1.dp, Pal.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, null, tint = Pal.muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Pal.muted, fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Pal.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Pal.accent),
                modifier = Modifier.fillMaxWidth().testTag("search"),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Close, "Clear search", tint = Pal.muted,
                modifier = Modifier.size(20.dp).clickable { onChange("") },
            )
        }
    }
}

@Composable
fun StatusBanner(text: String?, refreshing: Boolean, onDismiss: () -> Unit) {
    if (text == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (refreshing) Pal.surface2 else Pal.accentDim)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Pal.text, fontSize = 13.sp, modifier = Modifier.weight(1f).testTag("status"))
        if (!refreshing) {
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss", tint = Pal.muted) }
        } else Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val holder = App.instance.player
    val ch = holder.channel ?: return
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.surface2)
            .clickable(onClick = onOpen)
            .padding(8.dp)
            .testTag("miniPlayer"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChannelLogo(ch.name, ch.logo, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = Pal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                holder.error ?: holder.cast.deviceName?.let { "Playing on $it" } ?: holder.catchupTitle ?: ch.nowTitle ?: "Live",
                color = Pal.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Pal.accent)
                .clickable { holder.togglePause() }
                .semantics { contentDescription = if (holder.isPlaying) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            if (holder.isPlaying) PauseGlyph(Pal.onAccent) else Icon(Icons.Filled.PlayArrow, null, tint = Pal.onAccent)
        }
    }
}

/** Two bars; material-icons-core has no pause icon and the extended set is large. */
@Composable
fun PauseGlyph(color: Color, size: Dp = 18.dp) {
    Row(Modifier.size(size), horizontalArrangement = Arrangement.spacedBy(size / 5, Alignment.CenterHorizontally)) {
        Box(Modifier.width(size / 4.5f).fillMaxHeightBar().background(color, RoundedCornerShape(1.dp)))
        Box(Modifier.width(size / 4.5f).fillMaxHeightBar().background(color, RoundedCornerShape(1.dp)))
    }
}

private fun Modifier.fillMaxHeightBar() = this.then(Modifier.padding(vertical = 2.dp).fillMaxSize())

@Composable
fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Pal.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp)) {
            BarItem("Live", Icons.Filled.List, tab == Tab.LIVE) { onTab(Tab.LIVE) }
            BarItem("Guide", Icons.Filled.DateRange, tab == Tab.GUIDE) { onTab(Tab.GUIDE) }
            BarItem("Playlists", Icons.Filled.Menu, tab == Tab.PLAYLISTS) { onTab(Tab.PLAYLISTS) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Pal.accent else Pal.muted
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp).testTag("tab_$label"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (subtitle != null) Text(subtitle, color = Pal.muted, fontSize = 12.sp, letterSpacing = 0.5.sp)
        Text(title, color = Pal.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Pal.text2, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action, onAction)
        }
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Pal.accent else Pal.raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Pal.onAccent else Pal.muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Pal.surface2)
            .border(1.dp, Pal.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
