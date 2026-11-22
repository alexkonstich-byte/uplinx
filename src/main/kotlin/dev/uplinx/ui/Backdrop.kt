@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class Tone { PRIMARY, SECONDARY, TERTIARY, OUTLINE }

private data class Floater(
    val from: RoundedPolygon, val to: RoundedPolygon,
    val x: Float, val y: Float,          // position as a fraction of the area
    val size: Float,                     // dp
    val spin: Float,                     // turns per minute, sign = direction
    val drift: Float,                    // dp
    val depth: Float,                    // parallax factor
    val tone: Tone,
    val outlined: Boolean = false,
    val phase: Float = 0f,
)

/**
 * Living background: big M3 Expressive shapes that slowly turn, breathe between two forms and drift,
 * with parallax when the user moves between screens. Colours come from the dynamic scheme, so they
 * follow the Windows accent / chosen seed and light/dark mode.
 */
@Composable
fun ShapesBackdrop(screenIndex: Int, modifier: Modifier = Modifier) {
    val floaters = remember {
        listOf(
            Floater(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie12Sided, 0.92f, 0.10f, 420f, 0.6f, 26f, 1.0f, Tone.PRIMARY),
            Floater(MaterialShapes.Clover8Leaf, MaterialShapes.Flower, 0.06f, 0.88f, 360f, -0.45f, 30f, 0.7f, Tone.TERTIARY, phase = 0.3f),
            Floater(MaterialShapes.SoftBurst, MaterialShapes.Sunny, 0.55f, 0.95f, 230f, 0.8f, 22f, 1.4f, Tone.SECONDARY, phase = 0.6f),
            Floater(MaterialShapes.Pill, MaterialShapes.Gem, 0.46f, 0.55f, 150f, -1.1f, 18f, 1.8f, Tone.TERTIARY, phase = 0.15f),
            Floater(MaterialShapes.Cookie4Sided, MaterialShapes.Clover4Leaf, 0.80f, 0.62f, 110f, 1.4f, 14f, 2.2f, Tone.PRIMARY, outlined = true, phase = 0.45f),
            Floater(MaterialShapes.Pentagon, MaterialShapes.Puffy, 0.18f, 0.42f, 90f, -1.6f, 16f, 2.6f, Tone.OUTLINE, outlined = true, phase = 0.8f),
            Floater(MaterialShapes.Burst, MaterialShapes.SoftBoom, 0.66f, 0.30f, 70f, 2.0f, 12f, 3.0f, Tone.SECONDARY, phase = 0.9f),
        )
    }
    val morphs = remember(floaters) { floaters.map { Morph(it.from, it.to) } }
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val fillAlpha = if (dark) 0.16f else 0.5f
    val inf = rememberInfiniteTransition(label = "backdrop")
    // one shared clock: 60 s loop
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")
    val parallax by animateFloatAsState(screenIndex.toFloat(), MaterialTheme.motionScheme.slowSpatialSpec(), label = "parallax")

    Canvas(modifier) {
        val m = Matrix()
        floaters.forEachIndexed { i, f ->
            val phase = (t + f.phase) % 1f
            val angle = phase * 360f * f.spin
            val breathe = (sin(2 * PI * (phase * 2)).toFloat() + 1f) / 2f
            val px = size.width * f.x + cos(2 * PI * phase).toFloat() * f.drift.dp.toPx() - parallax * f.depth * 28.dp.toPx()
            val py = size.height * f.y + sin(2 * PI * phase).toFloat() * f.drift.dp.toPx() + parallax * f.depth * 6.dp.toPx()
            val s = f.size.dp.toPx() * (0.94f + breathe * 0.08f)
            val path = morphs[i].toPath(breathe)
            m.reset()
            m.translate(px, py)
            m.rotateZ(angle)
            m.scale(s, s)
            m.translate(-0.5f, -0.5f)
            path.transform(m)
            val base = when (f.tone) {
                Tone.PRIMARY -> cs.primaryContainer
                Tone.SECONDARY -> cs.secondaryContainer
                Tone.TERTIARY -> cs.tertiaryContainer
                Tone.OUTLINE -> cs.outlineVariant
            }
            if (f.outlined) drawPath(path, base.copy(alpha = if (dark) 0.6f else 0.9f), style = Stroke(width = 3.dp.toPx()))
            else drawPath(path, base.copy(alpha = fillAlpha))
        }
    }
}

/** Small shapes orbiting the connect button while connected. */
@Composable
fun OrbitShapes(active: Boolean, radiusFraction: Float, modifier: Modifier = Modifier) {
    val shapes = remember { listOf(MaterialShapes.Cookie4Sided, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Gem, MaterialShapes.Pentagon, MaterialShapes.Flower) }
    val paths = remember { shapes.map { Morph(it, it) } }
    val cs = MaterialTheme.colorScheme
    val colors = listOf(cs.primary, cs.tertiary, cs.secondary, cs.primary, cs.tertiary, cs.secondary)
    val visible by animateFloatAsState(if (active) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "orbitVis")
    val inf = rememberInfiniteTransition(label = "orbit")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "orbitT")
    if (visible <= 0.01f) return
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2
        val r = size.minDimension / 2 * radiusFraction * (0.8f + 0.2f * visible)
        val m = Matrix()
        paths.forEachIndexed { i, mo ->
            val a = 2 * PI * (t + i / paths.size.toFloat())
            val wobble = sin(2 * PI * (t * 3 + i * 0.37)).toFloat() * 6.dp.toPx()
            val x = cx + cos(a).toFloat() * (r + wobble)
            val y = cy + sin(a).toFloat() * (r + wobble)
            val s = (12 + (i % 3) * 5).dp.toPx() * visible
            val path = mo.toPath(0f)
            m.reset(); m.translate(x, y); m.rotateZ(t * 360f * (if (i % 2 == 0) 3 else -2)); m.scale(s, s); m.translate(-0.5f, -0.5f)
            path.transform(m)
            drawPath(path, colors[i].copy(alpha = 0.85f * visible))
        }
    }
}

