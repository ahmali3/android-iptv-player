package dev.ahmed.iptv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahmed.iptv.App
import kotlinx.coroutines.delay

/** Pick a Chromecast / Android TV on the Wi-Fi, or stop casting. */
@Composable
fun CastDialog(onDismiss: () -> Unit) {
    val cast = App.instance.player.cast
    var waited by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        cast.startScan()
        onDispose { cast.stopScan() }
    }
    LaunchedEffect(Unit) {
        delay(8_000)
        waited = true
    }
    // Close by itself once a TV is connected.
    var wasCasting by remember { mutableStateOf(cast.casting) }
    LaunchedEffect(cast.casting) {
        if (cast.casting && !wasCasting) onDismiss()
        wasCasting = cast.casting
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("castDialog"),
        title = { Text(if (cast.casting) "Casting" else "Cast to a TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val name = cast.deviceName
                when {
                    name != null -> {
                        Text("Playing on $name.", color = Pal.text2, fontSize = 14.sp)
                        if (cast.viaPhone) Text("This channel is sent through your phone, so keep it on the same Wi-Fi.", color = Pal.muted, fontSize = 13.sp)
                    }
                    cast.connecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Pal.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text("Connecting…", color = Pal.text2, fontSize = 14.sp)
                    }
                    cast.devices.isEmpty() -> {
                        if (!waited) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = Pal.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Text("Looking for TVs on your Wi-Fi…", color = Pal.text2, fontSize = 14.sp)
                        } else Text(
                            "No TVs found. Make sure your phone and your Chromecast or Android TV are on the same Wi-Fi.",
                            color = Pal.text2, fontSize = 14.sp, modifier = Modifier.testTag("castNone"),
                        )
                    }
                    else -> cast.devices.forEach { d ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Pal.surface2)
                                .clickable { cast.connect(d) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(d.name, color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (d.description.isNotBlank()) Text(d.description, color = Pal.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (cast.casting) TextButton(onClick = { cast.disconnect(); onDismiss() }) { Text("Stop casting") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** Opens the stream in another video player app installed on the device. */
fun openInOtherApp(context: Context, url: String, title: String) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(url), "video/*")
        .putExtra("title", title) // most players show this as the title
    val chooser = Intent.createChooser(view, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No other video player app is installed.", Toast.LENGTH_LONG).show()
    }
}
