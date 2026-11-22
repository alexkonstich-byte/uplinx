@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import dev.uplinx.AppController
import dev.uplinx.core.CoreStatus
import java.awt.Cursor
import java.awt.Rectangle

/** Maximise that respects the taskbar (an undecorated window would otherwise cover it). */
class WindowMaximizer(private val window: java.awt.Window) {
    var maximized by mutableStateOf(false); private set
    private var restore: Rectangle? = null
    fun toggle() {
        if (maximized) {
            restore?.let { window.bounds = it }
            maximized = false
        } else {
            restore = window.bounds
            val gc = window.graphicsConfiguration
            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val b = gc.bounds
            window.bounds = Rectangle(b.x + insets.left, b.y + insets.top, b.width - insets.left - insets.right, b.height - insets.top - insets.bottom)
            maximized = true
        }
    }
}

/** The app's own title bar: logo, name, live connection status and window buttons, in the M3 scheme. */
@Composable
fun FrameWindowScope.UplinxTitleBar(app: AppController, maximizer: WindowMaximizer, onMinimize: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by app.core.status.collectAsState()
    val state by app.state.collectAsState()
    val node = state.nodes.firstOrNull { it.id == state.settings.selectedNodeId } ?: state.nodes.firstOrNull()
    val on = status is CoreStatus.Running
    Row(Modifier.fillMaxWidth().height(40.dp).background(cs.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
        WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()
            // double-click toggles maximise without stealing the drag
            .pointerInput(Unit) {
                var last = 0L
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) {
                            val now = System.currentTimeMillis()
                            if (now - last < 350) { maximizer.toggle(); last = 0 } else last = now
                        }
                    }
                }
            }) {
            Row(Modifier.fillMaxSize().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                val mc = cs.markColors()
                Canvas(Modifier.size(22.dp)) { drawUplinxMark(mc) }
                Spacer(Modifier.width(10.dp))
                Text("Uplinx", style = MaterialTheme.typography.titleSmall.copy(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.width(14.dp))
                // status pill
                val dot by animateColorAsState(when (status) {
                    is CoreStatus.Running -> Color(0xFF2EC27E); is CoreStatus.Failed -> cs.error
                    is CoreStatus.Starting, is CoreStatus.Stopping, is CoreStatus.Preparing -> cs.tertiary
                    else -> cs.outline
                }, label = "dot")
                // the pulse only exists while connected: an idle loop would redraw the window every frame
                val pulse = if (on) { val phase = loopingFloat(2200, "titlePulse"); 1f + 0.6f * (1f - kotlin.math.abs(phase * 2f - 1f)) } else 1f
                Surface(shape = CircleShape, color = cs.surfaceContainerHighest) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size((8 * pulse).dp).background(dot.copy(alpha = if (on) 0.35f else 0f), CircleShape))
                            Box(Modifier.size(8.dp).background(dot, CircleShape))
                        }
                        Spacer(Modifier.width(8.dp))
                        AnimatedContent(on to node?.name, label = "titleStatus") { (connected, name) ->
                            FlagText(if (connected) L("Подключено") + (name?.let { " · $it" } ?: "") else L("Не подключено"),
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 360.dp))
                        }
                    }
                }
            }
        }
        WindowButton(Icons.Rounded.Remove, L("Свернуть"), onMinimize)
        WindowButton(if (maximizer.maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
            if (maximizer.maximized) L("Восстановить") else L("Развернуть"), maximizer::toggle)
        WindowButton(Icons.Rounded.Close, L("Закрыть"), onClose, danger = true)
    }
}

@Composable
private fun WindowButton(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(when { hovered && danger -> Color(0xFFC42B1C); hovered -> cs.onSurface.copy(alpha = 0.08f); else -> Color.Transparent }, tween(120), label = "wb")
    val fg by animateColorAsState(if (hovered && danger) Color.White else cs.onSurface, tween(120), label = "wf")
    Box(Modifier.size(width = 46.dp, height = 40.dp).background(bg).hoverable(src).clickable(src, null, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(if (icon == Icons.Rounded.CropSquare || icon == Icons.Rounded.FilterNone) 14.dp else 18.dp), tint = fg)
    }
}

/** Invisible grips on the window edges and corners: resizing for an undecorated window. */
@Composable
fun ResizeGrips(window: java.awt.Window, enabled: Boolean) {
    if (!enabled) return
    val edge = 6.dp; val corner = 12.dp
    Box(Modifier.fillMaxSize()) {
        @Composable
        fun grip(align: Alignment, w: androidx.compose.ui.unit.Dp?, h: androidx.compose.ui.unit.Dp?, cursor: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
            var m = Modifier.align(align)
            m = if (w != null) m.width(w) else m.fillMaxWidth()
            m = if (h != null) m.height(h) else m.fillMaxHeight()
            Box(m.pointerHoverIcon(PointerIcon(Cursor(cursor))).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.type != PointerEventType.Press) continue
                        val start = java.awt.MouseInfo.getPointerInfo().location
                        val b0 = window.bounds
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Release) break
                            val p = java.awt.MouseInfo.getPointerInfo().location
                            val ddx = p.x - start.x; val ddy = p.y - start.y
                            val nw = (b0.width + dw * ddx).coerceAtLeast(window.minimumSize.width)
                            val nh = (b0.height + dh * ddy).coerceAtLeast(window.minimumSize.height)
                            val nx = if (dx != 0) b0.x + (b0.width - nw) else b0.x
                            val ny = if (dy != 0) b0.y + (b0.height - nh) else b0.y
                            window.setBounds(nx, ny, nw, nh)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            })
        }
        grip(Alignment.CenterStart, edge, null, Cursor.W_RESIZE_CURSOR, 1, 0, -1, 0)
        grip(Alignment.CenterEnd, edge, null, Cursor.E_RESIZE_CURSOR, 0, 0, 1, 0)
        grip(Alignment.TopCenter, null, edge, Cursor.N_RESIZE_CURSOR, 0, 1, 0, -1)
        grip(Alignment.BottomCenter, null, edge, Cursor.S_RESIZE_CURSOR, 0, 0, 0, 1)
        grip(Alignment.TopStart, corner, corner, Cursor.NW_RESIZE_CURSOR, 1, 1, -1, -1)
        grip(Alignment.TopEnd, corner, corner, Cursor.NE_RESIZE_CURSOR, 0, 1, 1, -1)
        grip(Alignment.BottomStart, corner, corner, Cursor.SW_RESIZE_CURSOR, 1, 0, -1, 1)
        grip(Alignment.BottomEnd, corner, corner, Cursor.SE_RESIZE_CURSOR, 0, 0, 1, 1)
    }
}
