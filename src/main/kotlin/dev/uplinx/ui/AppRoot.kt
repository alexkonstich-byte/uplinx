@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package dev.uplinx.ui

import dev.uplinx.L

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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.uplinx.AppController
import dev.uplinx.core.CoreStatus
import java.io.File
import java.net.URI

enum class Screen(val title: String, val icon: ImageVector, val iconSelected: ImageVector) {
    HOME(L("Главная"), Icons.Outlined.Home, Icons.Rounded.Home),
    SERVERS(L("Серверы"), Icons.Outlined.Dns, Icons.Rounded.Dns),
    SUBS(L("Подписки"), Icons.Outlined.CloudSync, Icons.Rounded.CloudSync),
    ROUTING(L("Маршруты"), Icons.Outlined.AltRoute, Icons.Rounded.AltRoute),
    LOGS(L("Логи"), Icons.Outlined.Terminal, Icons.Rounded.Terminal),
    SETTINGS(L("Настройки"), Icons.Outlined.Settings, Icons.Rounded.Settings),
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
        ShapesBackdrop(screen.ordinal, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f), header = {
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
                        label = { Text(if (s == Screen.SUBS && dev.uplinx.I18n.lang == dev.uplinx.Lang.EN) "Subs" else s.title, maxLines = 1, softWrap = false) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Scaffold(snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, shape = RoundedCornerShape(16.dp)) } },
                containerColor = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.weight(1f)) { pad ->
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
                    Text(L("Отпустите, чтобы импортировать"), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(L(".conf (WireGuard / AmneziaWG), .json (Xray / sing-box), .yaml (Clash), .txt со ссылками, картинки с QR"), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        UpdateBanner(app, onExit)
    }

    if (needAdmin) AlertDialog(
        onDismissRequest = { app.needAdmin.value = false },
        icon = { Icon(Icons.Rounded.AdminPanelSettings, null) },
        title = { Text(L("Нужны права администратора")) },
        text = { Text(L("Для TUN и AmneziaWG Windows требует запуск от администратора. Перезапустить Uplinx с правами администратора и сразу подключиться?")) },
        confirmButton = { Button({ app.relaunchAsAdmin(onExit) }) { Text(L("Перезапустить")) } },
        dismissButton = { TextButton({ app.needAdmin.value = false }) { Text(L("Отмена")) } },
    )
    val pending by app.pendingImport.collectAsState()
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { app.pendingImport.value = null },
            icon = { Icon(Icons.Rounded.AddLink, null) },
            title = { Text(L("Добавить?")) },
            text = {
                Column {
                    Text(L("Ссылка открыта из другого приложения. Проверьте, что вы ей доверяете."))
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(p.summary, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    }
                }
            },
            confirmButton = { Button({ app.pendingImport.value = null; app.importText(p.text) }) { Text(L("Добавить")) } },
            dismissButton = { TextButton({ app.pendingImport.value = null }) { Text(L("Отмена")) } },
        )
    }
    val showErr by app.showErrorDetails.collectAsState()
    val err by app.lastError.collectAsState()
    if (showErr && err != null) AlertDialog(
        onDismissRequest = { app.showErrorDetails.value = false },
        icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(err!!.title) },
        text = {
            Column(Modifier.width(560.dp)) {
                Text(err!!.hint)
                Spacer(Modifier.height(12.dp))
                Text(L("Технические детали"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(err!!.details, Modifier.padding(12.dp).heightIn(max = 220.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono))
                    }
                }
            }
        },
        confirmButton = { TextButton({ app.showErrorDetails.value = false }) { Text(L("Закрыть")) } },
        dismissButton = {
            Row {
                TextButton({ app.copyToClipboard(err!!.details) }) { Text(L("Копировать")) }
                TextButton({ app.showErrorDetails.value = false; screen = Screen.LOGS }) { Text(L("Логи")) }
            }
        },
    )
    val updInfo by app.update.collectAsState()
    val showUpd by app.showUpdateDetails.collectAsState()
    if (showUpd) updInfo?.let { UpdateDialog(app, it, onExit) { app.showUpdateDetails.value = false } }
    val news by app.whatsNew.collectAsState()
    news?.let { WhatsNewDialog(it) { app.whatsNew.value = null } }
    if (needAmnezia) AlertDialog(
        onDismissRequest = { app.needAmnezia.value = false },
        icon = { Icon(Icons.Rounded.Extension, null) },
        title = { Text(L("Нужен клиент AmneziaWG")) },
        text = { Text(L("Этот конфиг использует обфускацию AmneziaWG (S1/S2/H1–H4), которую умеет только официальный движок. Установите AmneziaWG для Windows — Uplinx будет управлять туннелем сам.")) },
        confirmButton = { Button({ app.needAmnezia.value = false; dev.uplinx.core.WinUtil.openUrl("https://github.com/amnezia-vpn/amneziawg-windows-client/releases") }) { Text(L("Скачать")) } },
        dismissButton = { TextButton({ app.needAmnezia.value = false }) { Text(L("Закрыть")) } },
    )
}

@Composable
private fun RailLogo(status: CoreStatus) {
    // the rail keeps its morphing, slowly turning container; the flat mark sits inside
    val on = status is CoreStatus.Running
    val morph = remember { androidx.graphics.shapes.Morph(MaterialShapes.Cookie4Sided, MaterialShapes.Cookie12Sided) }
    val p by animateFloatAsState(if (on) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "logo")
    val inf = rememberInfiniteTransition(label = "logoSpin")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (on) 9000 else 40000, easing = LinearEasing)), label = "lr")
    val color by animateColorAsState(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, label = "lc")
    val mc = MaterialTheme.colorScheme.markColors()
    Box(Modifier.size(52.dp).background(color, MorphShape(morph, p, rot)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(30.dp)) { drawUplinxMark(mc) }
    }
}

@Composable
private fun UpdateBanner(app: AppController, onExit: () -> Unit) {
    val upd by app.update.collectAsState()
    var updDismissed by remember { mutableStateOf(false) }
    upd?.let { u ->
        if (!updDismissed) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
            ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.width(360.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(L("Доступна версия %1", u.version), style = MaterialTheme.typography.titleMedium)
                    }
                    if (u.notes.isNotBlank()) Text(u.notes.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                        TextButton({ updDismissed = true }) { Text(L("Позже")) }
                        Button({ updDismissed = true; app.showUpdateDetails.value = true }) { Text(L("Подробнее")) }
                    }
                }
            }
        }
    }
}
