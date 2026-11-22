@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.myproxy.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import dev.myproxy.PING_FAILED
import dev.myproxy.PING_TESTING
import dev.myproxy.data.Protocol
import kotlin.math.max

// ==================================================================== shapes

/** A Compose [Shape] drawing a graphics-shapes [Morph] at [progress], rotated by [rotation] degrees. */
class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        val m = Matrix()
        m.translate(size.width / 2f, size.height / 2f)
        m.rotateZ(rotation)
        m.scale(size.width, size.height)
        m.translate(-0.5f, -0.5f)
        path.transform(m)
        return Outline.Generic(path)
    }
}

class PolygonShape(polygon: RoundedPolygon, private val rotation: Float = 0f) : Shape {
    // a morph between a shape and itself is the cheapest non-composable way to get a Compose Path
    private val morph = Morph(polygon, polygon)
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(0f)
        val m = Matrix()
        m.translate(size.width / 2f, size.height / 2f)
        m.rotateZ(rotation)
        m.scale(size.width, size.height)
        m.translate(-0.5f, -0.5f)
        path.transform(m)
        return Outline.Generic(path)
    }
}

// ==================================================================== connect button

enum class ConnState { OFF, BUSY, ON, ERROR }

/**
 * The hero button. Off: a soft circle. Busy: morphs and spins with an expressive loading indicator.
 * On: settles into a slowly turning 9-sided cookie with breathing halos — Android 16 style.
 */
@Composable
fun ConnectButton(state: ConnState, onClick: () -> Unit, modifier: Modifier = Modifier, size: Float = 220f) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val morphProgress by animateFloatAsState(
        when (state) { ConnState.OFF, ConnState.ERROR -> 0f; ConnState.BUSY -> 0.55f; ConnState.ON -> 1f },
        motion.slowSpatialSpec(), label = "morph",
    )
    val inf = rememberInfiniteTransition(label = "conn")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == ConnState.BUSY) 1400 else 24000, easing = LinearEasing)), label = "spin")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "breathe")

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else if (hovered) 1.04f else 1f, motion.fastSpatialSpec(), label = "scale")

    val container by animateColorAsState(
        when (state) { ConnState.OFF -> cs.surfaceContainerHighest; ConnState.BUSY -> cs.secondaryContainer; ConnState.ON -> cs.primary; ConnState.ERROR -> cs.errorContainer },
        motion.defaultEffectsSpec(), label = "container",
    )
    val content by animateColorAsState(
        when (state) { ConnState.OFF -> cs.onSurfaceVariant; ConnState.BUSY -> cs.onSecondaryContainer; ConnState.ON -> cs.onPrimary; ConnState.ERROR -> cs.onErrorContainer },
        motion.defaultEffectsSpec(), label = "content",
    )
    val haloAlpha by animateFloatAsState(if (state == ConnState.ON) 1f else 0f, tween(600), label = "halo")
    val haloColor = cs.primary

    Box(modifier.size((size * 1.45f).dp), contentAlignment = Alignment.Center) {
        // breathing halos
        Canvas(Modifier.matchParentSize()) {
            if (haloAlpha > 0f) {
                val base = this.size.minDimension / 2f / 1.45f
                for (i in 0..1) {
                    val t = (breathe + i * 0.5f) % 1f
                    drawCircle(haloColor.copy(alpha = (1f - t) * 0.22f * haloAlpha), radius = base * (1f + t * 0.42f))
                }
            }
        }
        Box(
            Modifier
                .size(size.dp)
                .scale(scale)
                .clip(MorphShape(morph, morphProgress, if (state == ConnState.OFF || state == ConnState.ERROR) 0f else spin))
                .background(container)
                .hoverable(interaction)
                .clickable(interaction, indication = ripple(color = content), enabled = state != ConnState.BUSY, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = state == ConnState.BUSY,
                transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(150)) + scaleOut(targetScale = 0.6f)) },
                label = "icon",
            ) { busy ->
                if (busy) LoadingIndicator(Modifier.size((size * 0.42f).dp), color = content)
                else Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size((size * 0.32f).dp), tint = content)
            }
        }
    }
}

// ==================================================================== small pieces

/** Odometer-style text: every changed character rolls in from below. */
@Composable
fun RollingText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            key(i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically { h -> if (up) h else -h } + fadeIn()) togetherWith (slideOutVertically { h -> if (up) -h else h } + fadeOut()) using SizeTransform(clip = false)
                    },
                    label = "roll",
                ) { c -> Text(c.toString(), style = style, color = color) }
            }
        }
    }
}

@Composable
fun PingChip(ping: Int?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when {
        ping == null -> cs.surfaceContainerHigh to cs.onSurfaceVariant
        ping == PING_TESTING -> cs.secondaryContainer to cs.onSecondaryContainer
        ping < 0 -> cs.errorContainer to cs.onErrorContainer
        ping < 150 -> cs.tertiaryContainer to cs.onTertiaryContainer
        ping < 400 -> cs.primaryContainer to cs.onPrimaryContainer
        else -> cs.surfaceContainerHighest to cs.error
    }
    val bgA by animateColorAsState(bg, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "pbg")
    val fgA by animateColorAsState(fg, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "pfg")
    Surface(color = bgA, contentColor = fgA, shape = CircleShape, modifier = modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).heightIn(min = 18.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(ping, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "ping") { p ->
                when {
                    p == PING_TESTING -> LoadingIndicator(Modifier.size(18.dp), color = fgA)
                    p == null -> Text("—", style = MaterialTheme.typography.labelMedium)
                    p == PING_FAILED || p < 0 -> Text("таймаут", style = MaterialTheme.typography.labelMedium)
                    else -> Text("$p мс", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val avatarShapes = listOf(
    MaterialShapes.Cookie4Sided, MaterialShapes.Sunny, MaterialShapes.Pentagon, MaterialShapes.Clover4Leaf,
    MaterialShapes.Gem, MaterialShapes.Cookie6Sided, MaterialShapes.Puffy, MaterialShapes.Arch,
)

@Composable
fun ProtocolAvatar(protocol: Protocol, selected: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = remember(protocol) { PolygonShape(avatarShapes[protocol.ordinal % avatarShapes.size]) }
    val bg by animateColorAsState(if (selected) cs.primary else cs.secondaryContainer, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "av")
    val fg by animateColorAsState(if (selected) cs.onPrimary else cs.onSecondaryContainer, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "avf")
    val rot by animateFloatAsState(if (selected) 45f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "rot")
    Box(modifier.size(44.dp).clip(PolygonShape(avatarShapes[protocol.ordinal % avatarShapes.size], rot)).background(bg), contentAlignment = Alignment.Center) {
        Text(
            when (protocol) {
                Protocol.VLESS -> "VL"; Protocol.VMESS -> "VM"; Protocol.TROJAN -> "TR"; Protocol.SHADOWSOCKS -> "SS"
                Protocol.SOCKS -> "S5"; Protocol.HTTP -> "HT"; Protocol.HYSTERIA2 -> "H2"; Protocol.WIREGUARD -> "WG"
            },
            color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
}

/** Rolling speed chart: download filled with a gradient, upload as a line. */
@Composable
fun SpeedChart(history: List<Pair<Long, Long>>, modifier: Modifier = Modifier) {
    val down = MaterialTheme.colorScheme.primary
    val up = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val peak = history.maxOfOrNull { max(it.first, it.second) }?.coerceAtLeast(16 * 1024) ?: (16 * 1024)
    val scaleMax by animateFloatAsState(peak.toFloat(), tween(600), label = "max")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (i in 1..3) drawLine(grid.copy(alpha = 0.4f), Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 1f)
        if (history.size < 2) return@Canvas
        val n = 60
        val step = w / (n - 1)
        val offset = (n - history.size) * step
        fun pathOf(sel: (Pair<Long, Long>) -> Long): Path = Path().apply {
            history.forEachIndexed { i, p ->
                val x = offset + i * step
                val y = h - (sel(p) / scaleMax).coerceIn(0f, 1f) * h * 0.92f
                if (i == 0) moveTo(x, y) else {
                    val px = offset + (i - 1) * step
                    val py = h - (sel(history[i - 1]) / scaleMax).coerceIn(0f, 1f) * h * 0.92f
                    cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
                }
            }
        }
        val dp = pathOf { it.second }
        val fill = Path().apply { addPath(dp); lineTo(w, h); lineTo(offset, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(down.copy(alpha = 0.35f), down.copy(alpha = 0.02f))))
        drawPath(dp, down, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(pathOf { it.first }, up, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
    }
}

// ==================================================================== formatting

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

fun formatSpeed(bps: Long) = formatBytes(bps) + "/с"

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

fun formatAgo(millis: Long): String {
    if (millis <= 0) return "никогда"
    val d = (System.currentTimeMillis() - millis) / 1000
    return when {
        d < 60 -> "только что"
        d < 3600 -> "${d / 60} мин назад"
        d < 86400 -> "${d / 3600} ч назад"
        else -> "${d / 86400} дн назад"
    }
}

val Mono = FontFamily.Monospace
