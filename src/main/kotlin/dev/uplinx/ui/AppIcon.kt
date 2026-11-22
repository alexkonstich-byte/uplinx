package dev.uplinx.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter

/** Tonal roles the mark is painted with — always taken from a Material You colour scheme. */
data class MarkColors(
    val container: Color,      // tile background
    val containerEdge: Color,  // tile gradient end
    val front: Color,          // glyph face, top-left
    val frontEnd: Color,       // glyph face, bottom-right
    val side: Color,           // extrusion (the "3D" depth)
    val shine: Color,          // highlight on the face
)

/** Maps an M3 scheme onto the mark: container tile, primary→tertiary face, deep tone for depth. */
fun ColorScheme.markColors() = MarkColors(
    container = primaryContainer,
    containerEdge = lerp(primaryContainer, tertiaryContainer, 0.55f),
    front = primary,
    frontEnd = tertiary,
    // depth is always a deep shade of primary, in light and dark themes alike
    side = lerp(primary, Color.Black, 0.55f),
    shine = Color.White,
)

/** Centre line of a lowercase "u" with a tail flicking right, on a 256 grid. */
private fun glyphPath() = Path().apply {
    // left stem + bowl
    moveTo(78f, 54f)
    lineTo(78f, 126f)
    cubicTo(78f, 170f, 102f, 192f, 130f, 192f)
    cubicTo(158f, 192f, 178f, 170f, 178f, 126f)
    // right stem, running down into the tail
    moveTo(178f, 54f)
    lineTo(178f, 178f)
    quadraticTo(178f, 214f, 222f, 214f)
}

/**
 * The Uplinx mark: a chunky, extruded lowercase "u" with a tail, on a soft M3 tile.
 * Depth comes from stacking the glyph in a deep tone; the face carries a primary→tertiary gradient
 * and a soft highlight. Drawn on a 256 grid and scaled to the canvas.
 */
fun DrawScope.drawUplinxMark(c: MarkColors, tile: Boolean = true) {
    val k = size.minDimension / 256f
    scale(k, k, pivot = Offset.Zero) {
        if (tile) {
            drawRoundRect(
                Brush.linearGradient(listOf(c.container, c.containerEdge), Offset(0f, 0f), Offset(256f, 256f)),
                Offset(8f, 8f), Size(240f, 240f), CornerRadius(68f, 68f),
            )
        }
        val g = if (tile) 0.74f else 0.98f
        scale(g, g, pivot = Offset(128f, 128f)) {
            val path = glyphPath()
            val w = 56f
            val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
            // extrusion: the glyph repeated down-right in the deep tone
            val depth = 9
            for (i in depth downTo 1) {
                translate(i * 1.1f, i * 1.5f) {
                    drawPath(path, lerp(c.side, Color.Black, 0.18f * i / depth), style = stroke)
                }
            }
            // face
            drawPath(path, Brush.linearGradient(listOf(c.front, c.frontEnd), Offset(70f, 40f), Offset(220f, 220f)), style = stroke)
            // glossy light from the top-left, fading out before the middle
            drawPath(path, Brush.linearGradient(
                0f to c.shine.copy(alpha = 0.38f), 0.42f to c.shine.copy(alpha = 0.06f), 0.6f to Color.Transparent,
                start = Offset(60f, 30f), end = Offset(200f, 210f)), style = stroke)
        }
    }
}

/** Window / tray icon in the given scheme; [badge] adds a status dot. */
class UplinxIcon(private val colors: MarkColors, private val badge: Color? = null, private val badgeRing: Color = Color.White) : Painter() {
    override val intrinsicSize = Size(256f, 256f)
    override fun DrawScope.onDraw() {
        drawUplinxMark(colors)
        badge?.let {
            val r = size.minDimension * 0.16f
            val c = Offset(size.width - r * 1.1f, size.height - r * 1.1f)
            drawCircle(badgeRing, r * 1.3f, c)
            drawCircle(it, r, c)
        }
    }
}
