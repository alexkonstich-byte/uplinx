@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.myproxy.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.myproxy.AppController
import dev.myproxy.core.CoreStatus

/** The desktop take on a home-screen widget: a small always-on-top orb with the server and live speed. */
@Composable
fun MiniWindow(app: AppController, onOpen: () -> Unit, onClose: () -> Unit) {
    val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
    val state = rememberWindowState(size = DpSize(250.dp, 320.dp),
        position = WindowPosition((screen.width / 1.25f - 290).dp.coerceAtLeast(0.dp), (screen.height / 1.25f - 400).dp.coerceAtLeast(0.dp)))
    val st by app.state.collectAsState()
    val status by app.core.status.collectAsState()
    val traffic by app.core.traffic.collectAsState()
    val node = st.nodes.firstOrNull { it.id == st.settings.selectedNodeId } ?: st.nodes.firstOrNull()
    val conn = when (status) {
        is CoreStatus.Running -> ConnState.ON
        is CoreStatus.Starting, is CoreStatus.Stopping, is CoreStatus.Preparing -> ConnState.BUSY
        is CoreStatus.Failed -> ConnState.ERROR
        CoreStatus.Stopped -> ConnState.OFF
    }
    Window(onCloseRequest = onClose, state = state, title = "MyProxy mini", undecorated = true, transparent = true,
        alwaysOnTop = true, resizable = false) {
        MyProxyTheme(st.settings) {
            Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 6.dp,
                modifier = Modifier.fillMaxSize().padding(6.dp)) {
                WindowDraggableArea {
                    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onOpen, Modifier.size(32.dp)) { Icon(Icons.Rounded.OpenInFull, "Открыть", Modifier.size(18.dp)) }
                            IconButton(onClose, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Закрыть", Modifier.size(18.dp)) }
                        }
                        ConnectButton(conn, onClick = app::toggle, size = 120f)
                        FlagText(node?.name ?: "Нет сервера", style = MaterialTheme.typography.titleSmall)
                        AnimatedVisibility(conn == ConnState.ON) {
                            Text("↓ ${formatSpeed(traffic.down)}  ↑ ${formatSpeed(traffic.up)}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
