@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package dev.myproxy.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.myproxy.AppController
import dev.myproxy.core.CoreStatus
import java.io.File
import java.net.URI

enum class Screen(val title: String, val icon: ImageVector, val iconSelected: ImageVector) {
    HOME("Главная", Icons.Outlined.Home, Icons.Rounded.Home),
    SERVERS("Серверы", Icons.Outlined.Dns, Icons.Rounded.Dns),
    SUBS("Подписки", Icons.Outlined.CloudSync, Icons.Rounded.CloudSync),
    ROUTING("Маршруты", Icons.Outlined.AltRoute, Icons.Rounded.AltRoute),
    LOGS("Логи", Icons.Outlined.Terminal, Icons.Rounded.Terminal),
    SETTINGS("Настройки", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
fun AppRoot(app: AppController, onExit: () -> Unit, initial: Screen = Screen.HOME) {
    var screen by remember { mutableStateOf(initial) }
    val snackbar = remember { SnackbarHostState() }
    val status by app.core.status.collectAsState()
    val needAdmin by app.needAdmin.collectAsState()
    val needAmnezia by app.needAmnezia.collectAsState()
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, m.action, withDismissAction = m.action == null, duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }
    LaunchedEffect(Unit) { app.openLogs.collect { screen = Screen.LOGS } }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { dragging = true }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                return when (val d = event.dragData()) {
                    is DragData.FilesList -> { app.importFiles(d.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }); true }
                    is DragData.Text -> { app.importText(d.readText()) > 0 }
                    else -> false
                }
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()
        .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, header = {
                Spacer(Modifier.height(8.dp))
                RailLogo(status)
                Spacer(Modifier.height(16.dp))
            }) {
                val iconSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
                Screen.entries.forEach { s ->
                    if (s == Screen.SETTINGS) Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = screen == s, onClick = { screen = s },
                        icon = {
                            AnimatedContent(screen == s, transitionSpec = { scaleIn(iconSpec) togetherWith scaleOut() }, label = "ri") { sel ->
                                Icon(if (sel) s.iconSelected else s.icon, s.title)
                            }
                        },
                        label = { Text(s.title) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Scaffold(snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, shape = RoundedCornerShape(16.dp)) } },
                containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) { pad ->
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // M3 "fade through" with a little expressive vertical drift
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(tween(260, delayMillis = 70)) + scaleIn(tween(320, delayMillis = 70, easing = EaseOutCubic), initialScale = 0.94f) +
                            slideInVertically(tween(320, delayMillis = 70, easing = EaseOutCubic)) { dir * it / 24 }) togetherWith
                            (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.98f))
                    },
                    modifier = Modifier.padding(pad).fillMaxSize(),
                    label = "screens",
                ) { s ->
                    when (s) {
                        Screen.HOME -> HomeScreen(app, onOpenServers = { screen = Screen.SERVERS })
                        Screen.SERVERS -> ServersScreen(app)
                        Screen.SUBS -> SubscriptionsScreen(app)
                        Screen.ROUTING -> RoutingScreen(app)
                        Screen.LOGS -> LogsScreen(app)
                        Screen.SETTINGS -> SettingsScreen(app)
                    }
                }
            }
        }
        // drop overlay
        AnimatedVisibility(dragging, enter = fadeIn() + scaleIn(initialScale = 1.04f), exit = fadeOut() + scaleOut(targetScale = 1.04f)) {
            Box(Modifier.fillMaxSize().padding(16.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f), RoundedCornerShape(32.dp))
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FileDownload, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(12.dp))
                    Text("Отпустите, чтобы импортировать", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(".conf (WireGuard / AmneziaWG), .json (Xray), .vpn / .txt со ссылками", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }

    if (needAdmin) AlertDialog(
        onDismissRequest = { app.needAdmin.value = false },
        icon = { Icon(Icons.Rounded.AdminPanelSettings, null) },
        title = { Text("Нужны права администратора") },
        text = { Text("Для TUN и AmneziaWG Windows требует запуск от администратора. Перезапустить MyProxy с правами администратора и сразу подключиться?") },
        confirmButton = { Button({ app.relaunchAsAdmin(onExit) }) { Text("Перезапустить") } },
        dismissButton = { TextButton({ app.needAdmin.value = false }) { Text("Отмена") } },
    )
    if (needAmnezia) AlertDialog(
        onDismissRequest = { app.needAmnezia.value = false },
        icon = { Icon(Icons.Rounded.Extension, null) },
        title = { Text("Нужен клиент AmneziaWG") },
        text = { Text("Этот конфиг использует обфускацию AmneziaWG (S1/S2/H1–H4), которую умеет только официальный движок. Установите AmneziaWG для Windows — MyProxy будет управлять туннелем сам.") },
        confirmButton = { Button({ app.needAmnezia.value = false; dev.myproxy.core.WinUtil.openUrl("https://github.com/amnezia-vpn/amneziawg-windows-client/releases") }) { Text("Скачать") } },
        dismissButton = { TextButton({ app.needAmnezia.value = false }) { Text("Закрыть") } },
    )
}

@Composable
private fun RailLogo(status: CoreStatus) {
    val on = status is CoreStatus.Running
    val morph = remember { androidx.graphics.shapes.Morph(MaterialShapes.Cookie4Sided, MaterialShapes.Cookie12Sided) }
    val p by animateFloatAsState(if (on) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "logo")
    val inf = rememberInfiniteTransition(label = "logoSpin")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (on) 9000 else 40000, easing = LinearEasing)), label = "lr")
    val color by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, label = "lc")
    val icon by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer, label = "li")
    Box(Modifier.size(52.dp).background(color, MorphShape(morph, p, rot)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Shield, null, tint = icon)
    }
}
