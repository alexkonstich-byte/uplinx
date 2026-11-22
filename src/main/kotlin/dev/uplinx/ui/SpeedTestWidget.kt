@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uplinx.AppController
import dev.uplinx.core.SpeedPhase
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** 0..1000 Mbps on a log-ish scale, so 5 Mbps and 500 Mbps both move the needle. */
private fun gaugeFraction(mbps: Double): Float = (ln(1 + mbps.coerceAtLeast(0.0)) / ln(1 + 1000.0)).toFloat().coerceIn(0f, 1f)

private val ticks = listOf(0, 5, 10, 25, 50, 100, 250, 500, 1000)

private fun fmtMbps(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

/** Speedometer: 240° arc, filled to the current value, with a springy needle. */
@Composable
fun SpeedGauge(mbps: Double, uploading: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val target = gaugeFraction(mbps)
    val f by animateFloatAsState(target, spring(dampingRatio = 0.55f, stiffness = 90f), label = "needle")
    val color by animateColorAsState(if (uploading) cs.tertiary else cs.primary, label = "gaugeColor")
    val track = cs.surfaceContainerHighest
    val tickColor = cs.onSurfaceVariant
    val start = 150f; val sweep = 240f
    Canvas(modifier) {
        val stroke = size.minDimension * 0.075f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(track, start, sweep, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color.copy(alpha = 0.55f))), start, sweep * f, false, tl, Size(d, d),
            style = Stroke(stroke, cap = StrokeCap.Round))
        val c = Offset(size.width / 2, size.height / 2)
        val r = d / 2
        // ticks
        ticks.forEach { t ->
            val a = Math.toRadians((start + sweep * gaugeFraction(t.toDouble())).toDouble())
            val p1 = Offset(c.x + cos(a).toFloat() * (r - stroke * 0.9f), c.y + sin(a).toFloat() * (r - stroke * 0.9f))
            val p2 = Offset(c.x + cos(a).toFloat() * (r - stroke * 1.6f), c.y + sin(a).toFloat() * (r - stroke * 1.6f))
            drawLine(tickColor.copy(alpha = 0.6f), p1, p2, 2.dp.toPx(), StrokeCap.Round)
        }
        // needle
        rotate(start + sweep * f, c) {
            drawLine(color, c, Offset(c.x + r * 0.5f, c.y), stroke * 0.32f, StrokeCap.Round)
        }
        drawCircle(color, stroke * 0.55f, c)
        drawCircle(track, stroke * 0.25f, c)
    }
}

@Composable
fun SpeedTestWidget(app: AppController, forNode: dev.uplinx.data.ProxyNode? = null) {
    val state by app.state.collectAsState()
    val progress by app.speedProgress.collectAsState()
    val testing by app.speedTestingNode.collectAsState()
    val node = forNode ?: state.nodes.firstOrNull { it.id == state.settings.selectedNodeId } ?: state.nodes.firstOrNull()
    val last = state.settings.speedResults.lastOrNull { it.nodeId == node?.id } ?: state.settings.speedResults.lastOrNull()
    val cs = MaterialTheme.colorScheme
    val running = testing != null
    val p = progress
    val live = if (running && p != null && (p.phase == SpeedPhase.DOWNLOAD || p.phase == SpeedPhase.UPLOAD)) p.mbps else last?.downMbps ?: 0.0

    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainer.copy(alpha = 0.94f), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(L("Тест скорости"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                FlagText(node?.name ?: "—", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.widthIn(max = 160.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                SpeedGauge(live, uploading = running && p?.phase == SpeedPhase.UPLOAD, modifier = Modifier.fillMaxSize().padding(6.dp))
                // the number sits in the open bottom of the dial, clear of the needle
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)) {
                    RollingText(fmtMbps(live), MaterialTheme.typography.headlineMedium, if (running && p?.phase == SpeedPhase.UPLOAD) cs.tertiary else cs.primary)
                    Text(L("Мбит/с"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    AnimatedContent(if (running) p?.phase else null, label = "phase") { ph ->
                        Text(when (ph) {
                            SpeedPhase.PREPARING -> L("Подготовка…"); SpeedPhase.PING -> L("Пинг…")
                            SpeedPhase.DOWNLOAD -> L("Загрузка ↓"); SpeedPhase.UPLOAD -> L("Отдача ↑")
                            SpeedPhase.DONE, null -> ""
                        }, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
            // results row
            val down = if (running) p?.down else last?.downMbps
            val up = if (running) p?.up else last?.upMbps
            val ping = if (running) p?.ping else last?.pingMs
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ResultCell("↓", down?.let { fmtMbps(it) } ?: "—", cs.primary)
                ResultCell("↑", up?.let { fmtMbps(it) } ?: "—", cs.tertiary)
                ResultCell(L("пинг"), ping?.takeIf { it >= 0 }?.let { "$it" } ?: "—", cs.secondary)
            }
            Spacer(Modifier.height(8.dp))
            if (running) {
                LinearWavyProgressIndicator(progress = { p?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
            } else {
                FilledTonalButton({ app.runSpeedTest(node) }, enabled = node != null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (last == null) L("Запустить тест") else L("Ещё раз"))
                }
            }
        }
    }
}

@Composable
private fun ResultCell(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Clip)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
