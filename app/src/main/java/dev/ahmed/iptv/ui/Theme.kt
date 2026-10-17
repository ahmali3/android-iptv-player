package dev.ahmed.iptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

object Pal {
    val bg = Color(0xFF0D0F12)
    val surface = Color(0xFF16191E)
    val surface2 = Color(0xFF1E2228)
    val raised = Color(0xFF262B32)
    val line = Color(0xFF262B32)
    val text = Color(0xFFEDEFF2)
    val text2 = Color(0xFFB4BCC6)
    val muted = Color(0xFF9AA3AE)
    val accent = Color(0xFFF5B544)
    val onAccent = Color(0xFF0D0F12)
    val accentDim = Color(0xFF3A2B12)
    val error = Color(0xFFFF8A80)
    val mono = FontFamily.Monospace
}

private val scheme = darkColorScheme(
    primary = Pal.accent,
    onPrimary = Pal.onAccent,
    secondary = Pal.accent,
    background = Pal.bg,
    onBackground = Pal.text,
    surface = Pal.surface,
    onSurface = Pal.text,
    surfaceVariant = Pal.surface2,
    onSurfaceVariant = Pal.text2,
    surfaceContainer = Pal.surface,
    surfaceContainerHigh = Pal.surface2,
    surfaceContainerHighest = Pal.raised,
    surfaceContainerLow = Pal.surface,
    outline = Pal.line,
    outlineVariant = Pal.line,
    error = Pal.error,
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
