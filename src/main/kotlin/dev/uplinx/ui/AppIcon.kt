@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material3.toPath

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

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
private val tileShape = androidx.graphics.shapes.Morph(androidx.compose.material3.MaterialShapes.Clover4Leaf, androidx.compose.material3.MaterialShapes.Clover4Leaf)

/** Centre line of a fully symmetric lowercase "u" (mirror axis x = 128) on a 256 grid. */
private fun glyphPath() = Path().apply {
    moveTo(78f, 60f)
    lineTo(78f, 130f)
    cubicTo(78f, 172f, 100f, 196f, 128f, 196f)
    cubicTo(156f, 196f, 178f, 172f, 178f, 130f)
    lineTo(178f, 60f)
}

// Stroke 56 → the glyph spans x 50..206 and y 32..224: centred on the 256 grid as is.
private const val CENTER_DX = 0f
private const val CENTER_DY = 0f

/**
 * The Uplinx mark: a flat, perfectly symmetric lowercase "u" with a primary→tertiary gradient,
 * centred, on a transparent background (an optional M3 clover tile is kept for previews).
 * Depth comes from stacking the glyph in a deep tone; the face carries a primary→tertiary gradient
 * and a soft highlight. Drawn on a 256 grid and scaled to the canvas.
 */
fun DrawScope.drawUplinxMark(c: MarkColors, tile: Boolean = false) {
    val k = size.minDimension / 256f
    scale(k, k, pivot = Offset.Zero) {
        if (tile) {
            // the M3 Expressive four-leaf clover; everything outside it stays transparent
            val bun = tileShape.toPath(0f)
            val m = androidx.compose.ui.graphics.Matrix()
            m.translate(4f, 4f); m.scale(248f, 248f)
            bun.transform(m)
            drawPath(bun, Brush.linearGradient(listOf(c.container, c.containerEdge), Offset(0f, 0f), Offset(256f, 256f)))
        }
        val g = if (tile) 0.74f else 1.2f // no tile: the glyph (192 tall) fills ~90% of the frame
        scale(g, g, pivot = Offset(128f, 128f)) { translate(CENTER_DX, CENTER_DY) {
            val path = glyphPath()
            val w = 56f
            val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
            // face
            drawPath(path, Brush.linearGradient(listOf(c.front, c.frontEnd), Offset(70f, 40f), Offset(220f, 220f)), style = stroke)
        } }
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
