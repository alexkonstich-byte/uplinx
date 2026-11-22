package dev.uplinx

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.uplinx.core.CoreStatus
import dev.uplinx.core.WinUtil
import dev.uplinx.ui.AppRoot
import dev.uplinx.ui.UplinxTheme
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

private const val INSTANCE_PORT = 47821

/** Second launch (e.g. an uplinx:// link click) hands its arguments to the running instance and exits. */
private fun forwardToRunningInstance(args: Array<String>): Boolean = runCatching {
    Socket(InetAddress.getLoopbackAddress(), INSTANCE_PORT).use { s ->
        s.getOutputStream().bufferedWriter().use { it.write(args.joinToString("\n").ifEmpty { "--show" }); it.newLine() }
    }
    true
}.getOrDefault(false)

fun main(args: Array<String>) {
    val server = runCatching { ServerSocket(INSTANCE_PORT, 8, InetAddress.getLoopbackAddress()) }.getOrNull()
    if (server == null) {
        if (forwardToRunningInstance(args)) exitProcess(0)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val app = AppController(scope)
    Runtime.getRuntime().addShutdownHook(Thread { app.shutdown() })
    WinUtil.registerUrlScheme()
    WinUtil.cleanupLegacy()

    val incoming = MutableStateFlowCompat()
    server?.let { ss ->
        Thread({
            while (true) {
                val text = runCatching { ss.accept().use { it.getInputStream().bufferedReader().readText() } }.getOrNull() ?: continue
                incoming.emit(text)
            }
        }, "instance-listener").apply { isDaemon = true }.start()
    }

    val startMinimized = "--minimized" in args
    val deepLinks = args.filterNot { it.startsWith("--") }
    deepLinks.forEach { app.requestImport(it) }
    if ("--connect" in args || app.state.value.settings.autoConnect) app.connect()

    application {
        var visible by remember { mutableStateOf(!startMinimized) }
        val windowState = rememberWindowState(size = DpSize(1180.dp, 780.dp), position = WindowPosition(androidx.compose.ui.Alignment.Center))
        val settings by app.state.collectAsState()
        val status by app.core.status.collectAsState()
        val exit = { app.shutdown(); scope.cancel(); exitApplication() }
        val mini by app.miniWindow.collectAsState()

        LaunchedEffect(Unit) {
            incoming.flow.collect { text ->
                text.lines().filter { it.isNotBlank() && !it.startsWith("--") }.forEach { app.requestImport(it) }
                visible = true
                windowState.isMinimized = false
            }
        }

        UplinxTheme(settings.settings) {
            val cs = androidx.compose.material3.MaterialTheme.colorScheme
            val trayIcon = remember(status, cs.primary) {
                when (status) {
                    is CoreStatus.Running -> dev.uplinx.ui.UplinxIcon(badge = androidx.compose.ui.graphics.Color(0xFF2EC27E))
                    is CoreStatus.Failed -> dev.uplinx.ui.UplinxIcon(badge = cs.error)
                    else -> dev.uplinx.ui.UplinxIcon()
                }
            }
            Tray(
                icon = trayIcon,
                tooltip = "Uplinx — " + if (status is CoreStatus.Running) L("подключено") else L("отключено"),
                onAction = { visible = true; windowState.isMinimized = false },
                menu = {
                    Item(if (status is CoreStatus.Running) L("Отключиться") else L("Подключиться"), onClick = app::toggle)
                    Item(L("Открыть"), onClick = { visible = true; windowState.isMinimized = false })
                    Item(if (mini) L("Скрыть мини-окно") else L("Мини-окно"), onClick = { app.miniWindow.value = !mini })
                    Separator()
                    Item(L("Выход"), onClick = exit)
                },
            )
            Window(
                onCloseRequest = { if (settings.settings.minimizeToTray) visible = false else exit() },
                visible = visible,
                state = windowState,
                title = "Uplinx",
                icon = remember { dev.uplinx.ui.UplinxIcon() },
                onKeyEvent = { e ->
                    // Ctrl+V anywhere outside a text field imports links / configs
                    if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.V) { app.importFromClipboard(); true } else false
                },
            ) {
                window.minimumSize = java.awt.Dimension(900, 620)
                val caption = cs.surfaceContainer.toArgb(); val text = cs.onSurface.toArgb(); val border = cs.outlineVariant.toArgb()
                val darkUi = cs.surface.luminance() < 0.5f
                // the title bar follows the theme, including the animated palette changes
                LaunchedEffect(caption, text, border, darkUi) { dev.uplinx.core.WinUtil.styleTitleBar(window, caption, text, border, darkUi) }
                AppRoot(app, onExit = exit)
            }
            if (mini) dev.uplinx.ui.MiniWindow(app, onOpen = { visible = true; windowState.isMinimized = false }, onClose = { app.miniWindow.value = false })
        }
    }
}

/** Tiny bridge from a plain thread into compose: a hot flow without needing a scope at emit time. */
private class MutableStateFlowCompat {
    private val _flow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    val flow: kotlinx.coroutines.flow.SharedFlow<String> = _flow
    fun emit(s: String) { _flow.tryEmit(s) }
}
