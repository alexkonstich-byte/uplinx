@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.uplinx.AppController
import dev.uplinx.core.CoreStatus
import dev.uplinx.data.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ======================================================================== grid model

private const val COLS = 12
private val CELL_H = 68.dp
private val GAP = 8.dp

private fun HomeWidget.overlaps(o: HomeWidget) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h

/** Puts [moved] where it was dropped and pushes anything it lands on further down — launcher style. */
private fun resolve(list: List<HomeWidget>, moved: HomeWidget): List<HomeWidget> {
    val placed = mutableListOf(moved)
    list.filter { it.id != moved.id }.sortedWith(compareBy({ it.y }, { it.x })).forEach { w ->
        var c = w
        while (placed.any { it.overlaps(c) }) c = c.copy(y = placed.filter { it.overlaps(c) }.maxOf { it.y + it.h })
        placed += c
    }
    return placed
}

private fun firstFree(list: List<HomeWidget>, w: Int, h: Int): Pair<Int, Int> {
    for (y in 0..200) for (x in 0..COLS - w) {
        val c = HomeWidget(type = WidgetType.PING, x = x, y = y, w = w, h = h)
        if (list.none { it.overlaps(c) }) return x to y
    }
    return 0 to 0
}

private val WidgetType.title: String
    get() = when (this) {
        WidgetType.CONNECT -> L("Кнопка подключения")
        WidgetType.SERVER -> L("Текущий сервер")
        WidgetType.MODES -> L("Режимы")
        WidgetType.SPEED -> L("Скорость")
        WidgetType.PING -> L("Пинг")
        WidgetType.IP -> L("Мой IP")
        WidgetType.QUOTA -> L("Подписка")
        WidgetType.SERVERS -> L("Быстрый выбор сервера")
        WidgetType.SESSION -> L("Сессия")
        WidgetType.SITES -> L("Доступность сайтов")
        WidgetType.USAGE -> L("Использование интернета")
    }

private val WidgetType.icon
    get() = when (this) {
        WidgetType.CONNECT -> Icons.Rounded.PowerSettingsNew
        WidgetType.SERVER -> Icons.Rounded.Dns
        WidgetType.MODES -> Icons.Rounded.Tune
        WidgetType.SPEED -> Icons.Rounded.ShowChart
        WidgetType.PING -> Icons.Rounded.Speed
        WidgetType.IP -> Icons.Rounded.Public
        WidgetType.QUOTA -> Icons.Rounded.DataUsage
        WidgetType.SERVERS -> Icons.Rounded.FormatListBulleted
        WidgetType.SESSION -> Icons.Rounded.Timer
        WidgetType.SITES -> Icons.Rounded.FactCheck
        WidgetType.USAGE -> Icons.Rounded.BarChart
    }

// ======================================================================== screen

/** Lets the offscreen screenshot renderer open Home straight in edit mode. */
internal object HomePreview { var startInEdit = false }

@Composable
fun HomeScreen(app: AppController, onOpenServers: () -> Unit) {
    val state by app.state.collectAsState()
    val widgets = state.settings.homeWidgets
    var edit by remember { mutableStateOf(HomePreview.startInEdit) }
    var picker by remember { mutableStateOf(false) }
    var ghost by remember { mutableStateOf<HomeWidget?>(null) }
    var optionsFor by remember { mutableStateOf<HomeWidget?>(null) }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize()) {
        // ------------------------------------------------ edit toolbar
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            AnimatedVisibility(edit, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L("Перетаскивайте виджеты, тяните за уголок, чтобы изменить размер"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                    TextButton({ app.updateWidgets(defaultHomeWidgets()) }) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text(L("Сбросить")) }
                    FilledTonalButton({ picker = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(L("Виджет")) }
                }
            }
            if (edit) Button({ edit = false }) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text(L("Готово")) }
            else TooltipIcon(L("Настроить главный экран"), Icons.Rounded.DashboardCustomize) { edit = true }
        }

        BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val cellW = maxWidth / COLS
            val rows = ((widgets.maxOfOrNull { it.y + it.h } ?: 0) + if (edit) 3 else 0).coerceAtLeast(if (edit) 9 else 1)
            Box(Modifier.fillMaxWidth().height(CELL_H * rows)) {
                // snap zones
                val dots = MaterialTheme.colorScheme.outlineVariant
                val ghostColor = MaterialTheme.colorScheme.primary
                val gridAlpha by animateFloatAsState(if (edit) 1f else 0f, label = "grid")
                run {
                    Canvas(Modifier.matchParentSize().graphicsLayer { alpha = gridAlpha }) {
                        val cw = cellW.toPx(); val ch = CELL_H.toPx()
                        for (r in 0..rows) for (c in 0..COLS) drawCircle(dots, 2.5f, Offset(c * cw, r * ch))
                        ghost?.let { g ->
                            val tl = Offset(g.x * cw + 4, g.y * ch + 4)
                            val sz = Size(g.w * cw - 8, g.h * ch - 8)
                            drawRoundRect(ghostColor.copy(alpha = 0.12f), tl, sz, CornerRadius(28.dp.toPx()))
                            drawRoundRect(ghostColor, tl, sz, CornerRadius(28.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                        }
                    }
                }
                widgets.forEachIndexed { i, w ->
                    key(w.id) {
                        WidgetFrame(
                            w, cellW, edit, i,
                            onPreview = { ghost = it },
                            onCommit = { moved -> ghost = null; app.updateWidgets(resolve(widgets, moved)) },
                            onRemove = { app.updateWidgets(widgets.filterNot { it.id == w.id }) },
                            onOptions = if (w.type.hasOptions) ({ optionsFor = w }) else null,
                        ) { WidgetContent(w, app, onOpenServers) { o -> app.updateWidgets(widgets.map { if (it.id == w.id) it.copy(opts = o) else it }) } }
                    }
                }
                if (widgets.isEmpty() && !edit) Box(Modifier.fillMaxWidth().height(CELL_H * 4), contentAlignment = Alignment.Center) {
                    OutlinedButton({ edit = true; picker = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(L("Добавить виджеты")) }
                }
            }
        }
    }

    optionsFor?.let { w ->
        WidgetOptionsDialog(w, onDismiss = { optionsFor = null }) { o -> optionsFor = null; app.updateWidgets(widgets.map { if (it.id == w.id) it.copy(opts = o) else it }) }
    }
    if (picker) WidgetPicker(onDismiss = { picker = false }) { type ->
        picker = false
        val (x, y) = firstFree(widgets, type.defW, type.defH)
        app.updateWidgets(widgets + HomeWidget(type = type, x = x, y = y, w = type.defW, h = type.defH))
    }
}

// ======================================================================== widget frame: drag, resize, wiggle

@Composable
private fun WidgetFrame(
    w: HomeWidget, cellW: Dp, edit: Boolean, index: Int,
    onPreview: (HomeWidget?) -> Unit, onCommit: (HomeWidget) -> Unit, onRemove: () -> Unit,
    onOptions: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cw = with(density) { cellW.toPx() }; val ch = with(density) { CELL_H.toPx() }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var resize by remember { mutableStateOf(Offset.Zero) }
    var moving by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(w)

    fun movedTarget() = current.copy(
        x = ((current.x * cw + drag.x) / cw).roundToInt().coerceIn(0, COLS - current.w),
        y = ((current.y * ch + drag.y) / ch).roundToInt().coerceAtLeast(0),
    )
    fun sizedTarget() = current.copy(
        w = ((current.w * cw + resize.x) / cw).roundToInt().coerceIn(current.type.minW, minOf(current.type.maxW, COLS - current.x)),
        h = ((current.h * ch + resize.y) / ch).roundToInt().coerceIn(current.type.minH, current.type.maxH),
    )

    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val ax by animateFloatAsState(w.x * cw, spec, label = "wx")
    val ay by animateFloatAsState(w.y * ch, spec, label = "wy")
    val aw by animateFloatAsState(w.w * cw, spec, label = "ww")
    val ah by animateFloatAsState(w.h * ch, spec, label = "wh")
    val lift by animateFloatAsState(if (moving || sizing) 1.03f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lift")

    // gentle wiggle in edit mode, each widget out of phase
    val inf = rememberInfiniteTransition(label = "wiggle")
    val wig by inf.animateFloat(-0.5f, 0.5f, infiniteRepeatable(tween(260 + index * 23, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wig")

    val x = if (moving) w.x * cw + drag.x else ax
    val y = if (moving) w.y * ch + drag.y else ay
    val width = if (sizing) (w.w * cw + resize.x).coerceAtLeast(w.type.minW * cw) else aw
    val height = if (sizing) (w.h * ch + resize.y).coerceAtLeast(w.type.minH * ch) else ah

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .zIndex(if (moving || sizing) 10f else 0f)
            .padding(GAP / 2)
            .scale(lift)
            .rotate(if (edit && !moving && !sizing) wig else 0f)
            .then(if (moving || sizing) Modifier.shadow(16.dp, RoundedCornerShape(28.dp)) else Modifier)
            .then(if (edit) Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(28.dp)) else Modifier)
            .then(if (edit) Modifier.pointerInput(w.id) {
                detectDragGestures(
                    onDragStart = { moving = true; drag = Offset.Zero },
                    onDragEnd = { val t = movedTarget(); moving = false; drag = Offset.Zero; onCommit(t) },
                    onDragCancel = { moving = false; drag = Offset.Zero; onPreview(null) },
                    onDrag = { change, amount -> change.consume(); drag += amount; onPreview(movedTarget()) },
                )
            } else Modifier),
    ) {
        // content is inert while editing so drags don't press buttons
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (edit) 0.92f else 1f }) { content() }
        if (edit) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        AnimatedVisibility(edit, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            FilledTonalIconButton(onRemove, Modifier.padding(6.dp).size(30.dp)) { Icon(Icons.Rounded.Close, L("Удалить"), Modifier.size(16.dp)) }
        }
        if (onOptions != null) AnimatedVisibility(edit, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            FilledTonalIconButton(onOptions, Modifier.padding(6.dp).size(30.dp)) { Icon(Icons.Rounded.Settings, L("Настройки виджета"), Modifier.size(16.dp)) }
        }
        AnimatedVisibility(edit, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(6.dp).size(30.dp).pointerInput(w.id) {
                    detectDragGestures(
                        onDragStart = { sizing = true; resize = Offset.Zero },
                        onDragEnd = { val t = sizedTarget(); sizing = false; resize = Offset.Zero; onCommit(t) },
                        onDragCancel = { sizing = false; resize = Offset.Zero; onPreview(null) },
                        onDrag = { change, amount -> change.consume(); resize += amount; onPreview(sizedTarget()) },
                    )
                }) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.OpenInFull, L("Размер"), Modifier.size(15.dp).rotate(90f)) }
            }
        }
    }
}

@Composable
private fun WidgetPicker(onDismiss: () -> Unit, onPick: (WidgetType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Widgets, null) },
        title = { Text(L("Добавить виджет")) },
        text = {
            FlowRow(Modifier.width(560.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WidgetType.entries.forEach { t ->
                    Surface(onClick = { onPick(t) }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(170.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Surface(shape = PolygonShape(MaterialShapes.Cookie6Sided), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                                Icon(t.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text("${t.defW}×${t.defH}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(L("Закрыть")) } },
    )
}

// ======================================================================== widgets

@Composable
private fun WidgetCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = color.copy(alpha = 0.94f), modifier = modifier.fillMaxSize()) { Box(Modifier.padding(18.dp), content = content) }
    else Surface(shape = shape, color = color.copy(alpha = 0.94f), modifier = modifier.fillMaxSize()) { Box(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun WidgetContent(w: HomeWidget, app: AppController, onOpenServers: () -> Unit, onOptions: (Map<String, String>) -> Unit) {
    val state by app.state.collectAsState()
    val status by app.core.status.collectAsState()
    val traffic by app.core.traffic.collectAsState()
    val pings by app.pings.collectAsState()
    val testing by app.testing.collectAsState()
    val node = state.nodes.firstOrNull { it.id == state.settings.selectedNodeId } ?: state.nodes.firstOrNull()
    val conn = when (status) {
        is CoreStatus.Running -> ConnState.ON
        is CoreStatus.Starting, is CoreStatus.Stopping, is CoreStatus.Preparing -> ConnState.BUSY
        is CoreStatus.Failed -> ConnState.ERROR
        CoreStatus.Stopped -> ConnState.OFF
    }
    val sub = state.subscriptions.firstOrNull { it.id == node?.subscriptionId }

    when (w.type) {
        WidgetType.CONNECT -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val btn = (minOf(maxWidth.value, maxHeight.value - 90f) / 1.45f).coerceIn(70f, 260f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ConnectButton(conn, onClick = app::toggle, size = btn)
                StatusLine(status, compact = btn < 140f)
            }
        }
        WidgetType.SERVER -> CurrentServerCard(node?.name,
            node?.let { (if (state.settings.maskAddresses) "" else it.endpoint + " · ") + "${it.protocol.title} · ${it.transportLabel}" },
            node?.id?.let { pings[it] }, node?.id in testing, node?.needsAmneziaEngine == true, onClick = onOpenServers, onPing = app::pingCurrentReal)
        WidgetType.MODES -> WidgetCard { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ModeSwitchers(state.settings.connectMode, state.routing.mode, app) } }
        WidgetType.SPEED -> TrafficCard(traffic, conn == ConnState.ON)
        WidgetType.PING -> PingWidget(app, w)
        WidgetType.IP -> IpWidget(app)
        WidgetType.QUOTA -> QuotaWidget(app, w)
        WidgetType.SERVERS -> ServersWidget(app, w, onOptions)
        WidgetType.SITES -> SitesWidget(app)
        WidgetType.USAGE -> UsageWidget(app)
        WidgetType.SESSION -> {
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(status) { while (status is CoreStatus.Running) { now = System.currentTimeMillis(); delay(1000) } }
            WidgetCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(6.dp))
                        Text(L("Сессия"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    val st = status
                    RollingText(if (st is CoreStatus.Running) formatDuration(now - st.since) else "00:00:00",
                        MaterialTheme.typography.headlineSmall.copy(fontFamily = Mono), MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("↓ ${formatBytes(traffic.totalDown)} · ↑ ${formatBytes(traffic.totalUp)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

private fun flagOf(code: String): String =
    if (code.length != 2) "" else code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

@Composable
private fun IpWidget(app: AppController) {
    val ip by app.ipInfo.collectAsState()
    val loading by app.ipLoading.collectAsState()
    LaunchedEffect(Unit) { if (app.ipInfo.value == null) app.refreshIp() }
    val masked = app.state.collectAsState().value.settings.maskAddresses
    WidgetCard(color = MaterialTheme.colorScheme.secondaryContainer, onClick = { app.refreshIp() }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(6.dp))
                Text(L("Мой IP"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                if (loading) LoadingIndicator(Modifier.size(20.dp)) else Icon(Icons.Rounded.Refresh, L("Обновить"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            AnimatedContent(ip, label = "ip") { info ->
                Column {
                    if (info == null) Text(if (loading) L("Определяю…") else L("Не удалось определить"), style = MaterialTheme.typography.titleMedium)
                    else {
                        FlagText(flagOf(info.countryCode) + " " + (if (masked) "•••.•••.•••.•••" else info.ip), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(listOf(info.city, info.country).filter { it.isNotBlank() }.joinToString(", ") + if (info.isp.isNotBlank()) " · ${info.isp}" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ======================================================================== building blocks

@Composable
private fun StatusLine(status: CoreStatus, compact: Boolean = false) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) { while (status is CoreStatus.Running) { now = System.currentTimeMillis(); delay(1000) } }
    AnimatedContent(
        targetState = status::class,
        transitionSpec = { (fadeIn(tween(220, 90)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(90)) + slideOutVertically { -it / 2 }) },
        label = "status",
    ) { _ ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val (title, subtitle) = when (status) {
                is CoreStatus.Running -> L("Подключено") to null
                is CoreStatus.Starting -> L("Подключение…") to null
                is CoreStatus.Stopping -> L("Отключение…") to null
                is CoreStatus.Preparing -> L("Загрузка") to status.what
                is CoreStatus.Failed -> L("Ошибка") to status.message.lineSequence().firstOrNull()
                CoreStatus.Stopped -> L("Не подключено") to L("Нажмите, чтобы подключиться")
            }
            Text(title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (status is CoreStatus.Running) {
                RollingText(formatDuration(now - status.since), MaterialTheme.typography.titleMedium.copy(fontFamily = Mono), MaterialTheme.colorScheme.primary)
            } else if (subtitle != null && !compact) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
            }
            if (status is CoreStatus.Preparing) {
                Spacer(Modifier.height(8.dp))
                val p = status.progress
                if (p != null) LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.width(200.dp))
                else LinearWavyProgressIndicator(modifier = Modifier.width(200.dp))
            }
        }
    }
}

@Composable
private fun ModeSwitchers(connectMode: ConnectMode, routingMode: RoutingMode, app: AppController) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ConnectMode.entries.forEachIndexed { i, m ->
                ToggleButton(
                    checked = connectMode == m,
                    onCheckedChange = { app.setConnectMode(m) },
                    shapes = when (i) { 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(); else -> ButtonGroupDefaults.connectedTrailingButtonShapes() },
                ) {
                    Icon(if (m == ConnectMode.TUN) Icons.Rounded.Lan else Icons.Rounded.Public, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (m == ConnectMode.TUN) "TUN" else L("Системный прокси"))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            RoutingMode.entries.forEachIndexed { i, m ->
                ToggleButton(
                    checked = routingMode == m,
                    onCheckedChange = { app.setRoutingMode(m) },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        RoutingMode.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    Icon(when (m) { RoutingMode.RULE -> Icons.Rounded.AltRoute; RoutingMode.GLOBAL -> Icons.Rounded.TravelExplore; RoutingMode.DIRECT -> Icons.Rounded.ArrowOutward }, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(m.title)
                }
            }
        }
    }
}

@Composable
private fun CurrentServerCard(name: String?, subtitle: String?, ping: Int?, testing: Boolean, amnezia: Boolean, onClick: () -> Unit, onPing: () -> Unit) {
    WidgetCard(onClick = onClick) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val (flagId, title) = remember(name) { name?.let(::splitLeadingFlag) ?: (null to null) }
            AnimatedContent(flagId, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f)) }, label = "flag") { f ->
                NodeAvatar(f, Protocol.VLESS, selected = true, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Сервер"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedContent(title, transitionSpec = { (fadeIn() + slideInHorizontally { it / 3 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 3 }) }, label = "srv") { n ->
                    FlagText(n ?: L("Не выбран"), style = MaterialTheme.typography.titleLarge)
                }
                if (subtitle != null) Text(subtitle + if (amnezia) L(" · движок AmneziaWG") else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (name != null) {
                PingChip(ping, testing)
                IconButton(onClick = onPing) { Icon(Icons.Rounded.Speed, L("Проверить задержку")) }
            }
        }
    }
}

@Composable
private fun TrafficCard(t: dev.uplinx.core.Traffic, active: Boolean) {
    WidgetCard {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedValue(Icons.Rounded.ArrowDownward, L("Загрузка"), formatSpeed(t.down), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                SpeedValue(Icons.Rounded.ArrowUpward, L("Отдача"), formatSpeed(t.up), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            val alpha by animateFloatAsState(if (active) 1f else 0.35f, label = "chartA")
            SpeedChart(t.history, Modifier.fillMaxWidth().weight(1f).graphicsLayer { this.alpha = alpha })
            Spacer(Modifier.height(8.dp))
            Text(L("За сессию: ↓ %1 · ↑ %2", formatBytes(t.totalDown), formatBytes(t.totalUp)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SpeedValue(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RollingText(value, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun SubscriptionQuotaCard(name: String, used: Long, total: Long, expire: Long, warn: Boolean) {
    WidgetCard(color = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (total > 0) {
                Spacer(Modifier.height(8.dp))
                val frac by animateFloatAsState((used.toFloat() / total).coerceIn(0f, 1f), MaterialTheme.motionScheme.slowSpatialSpec(), label = "quota")
                LinearWavyProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(L("%1 из %2", formatBytes(used), formatBytes(total)), style = MaterialTheme.typography.bodySmall)
            }
            if (expire > 0) {
                val days = (expire * 1000 - System.currentTimeMillis()) / 86_400_000
                val date = DateTimeFormatter.ofPattern("d MMM yyyy", dev.uplinx.I18n.locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(expire))
                Text(if (days >= 0) L("Действует до %1 · осталось %2 дн.", date, days) else L("Истекла %1", date), style = MaterialTheme.typography.bodySmall,
                    color = if (days < 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
