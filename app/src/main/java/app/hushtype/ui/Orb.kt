package app.hushtype.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/** The bubble, big and listening: the hero on the welcome screen. */
@Composable
fun ListeningOrb(size: Dp = 180.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val t by transition.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "t")
    val breathe by transition.animateFloat(0.3f, 0.85f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension * 0.3f
        val time = t
        for (i in 0 until 3) {
            val phase = ((time * 0.55f + i / 3f) % 1f)
            val grow = 1f + phase * (0.45f + breathe * 0.25f)
            drawCircle(Hush.mint.copy(alpha = (1f - phase) * 0.35f), radius = r * grow, center = c, style = Stroke(width = r * 0.04f))
        }
        drawCircle(
            Brush.radialGradient(listOf(Color(0x556B5CFF), Color.Transparent), center = c, radius = r * 1.6f),
            radius = r * 1.6f, center = c,
        )
        drawCircle(Brush.linearGradient(listOf(Color(0xFF6B5CFF), Color(0xFF3B2EA8)), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)), radius = r, center = c)
        drawCircle(Color(0xFFA08CFF).copy(alpha = 0.25f + breathe * 0.2f), radius = r * (0.7f + breathe * 0.12f), center = c)
        val bars = 5
        val barW = r * 0.13f
        val gap = r * 0.09f
        val total = bars * barW + (bars - 1) * gap
        val shapes = floatArrayOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
        for (i in 0 until bars) {
            val wobble = 0.55f + 0.45f * sin(time * 9f + i * 1.3f)
            val h = r * (0.16f + 0.78f * shapes[i] * breathe * wobble)
            val x = c.x - total / 2 + i * (barW + gap)
            drawRoundRect(Color.White, topLeft = Offset(x, c.y - h / 2), size = Size(barW, h), cornerRadius = CornerRadius(barW / 2))
        }
    }
}
