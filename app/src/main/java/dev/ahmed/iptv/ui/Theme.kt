package dev.ahmed.iptv.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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

/**
 * Feedback for everything clickable: a soft flash when pressed, and a clear amber outline
 * when focused with a TV remote or keyboard (invisible on touch).
 */
private object FocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusIndicationNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
}

private class FocusIndicationNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(Color.White.copy(alpha = 0.08f))
        if (focused) {
            drawRect(Pal.accent.copy(alpha = 0.16f))
            drawRect(Pal.accent, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalIndication provides FocusIndication,
            // Material buttons and menus: stronger focus highlight so it's visible from the sofa.
            LocalRippleConfiguration provides RippleConfiguration(
                color = Pal.accent,
                rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.32f, hoveredAlpha = 0.08f, pressedAlpha = 0.20f),
            ),
            content = content,
        )
    }
}
