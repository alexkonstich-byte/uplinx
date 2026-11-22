@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uplinx.AppController
import dev.uplinx.PING_UNRELIABLE
import dev.uplinx.core.CoreStatus
import dev.uplinx.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ======================================================================== charts

/** Smooth line with a soft fill; negative values are gaps (failed samples). */
@Composable
fun LineChart(values: List<Float>, color: Color, modifier: Modifier = Modifier, minRange: Float = 1f) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ok = values.filter { it >= 0 }
    val top by animateFloatAsState((ok.maxOrNull() ?: minRange).coerceAtLeast(minRange) * 1.15f, tween(500), label = "top")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (i in 1..2) drawLine(grid.copy(alpha = 0.35f), Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1f)
        if (values.size < 2) return@Canvas
        val step = w / (values.size - 1)
        fun y(v: Float) = h - (v / top).coerceIn(0f, 1f) * h
        var path: Path? = null
        var fill: Path? = null
        var startX = 0f
        var lastX = 0f
        fun flush() {
            path?.let { drawPath(it, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
            fill?.let { f -> f.lineTo(lastX, h); f.lineTo(startX, h); f.close(); drawPath(f, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent))) }
            path = null; fill = null
        }
        values.forEachIndexed { i, v ->
            val x = i * step
            if (v < 0) { flush(); drawCircle(color.copy(alpha = 0.5f), 3.dp.toPx(), Offset(x, h - 3.dp.toPx())); return@forEachIndexed }
            if (path == null) { path = Path().apply { moveTo(x, y(v)) }; fill = Path().apply { moveTo(x, y(v)) }; startX = x }
            else {
                val px = x - step; val pv = values[i - 1]
                path!!.cubicTo((px + x) / 2, y(pv), (px + x) / 2, y(v), x, y(v))
                fill!!.cubicTo((px + x) / 2, y(pv), (px + x) / 2, y(v), x, y(v))
            }
            lastX = x
        }
        flush()
        values.lastOrNull()?.takeIf { it >= 0 }?.let { drawCircle(color, 4.dp.toPx(), Offset((values.size - 1) * step, y(it))) }
    }
}

/** Stacked bars (download + upload) per day, today highlighted. */
@Composable
fun UsageBars(days: List<Pair<Long, Long>>, modifier: Modifier = Modifier) {
    val down = MaterialTheme.colorScheme.primary
    val up = MaterialTheme.colorScheme.tertiary
    val max = days.maxOfOrNull { it.first + it.second }?.coerceAtLeast(1) ?: 1
    // bars grow from the baseline when the widget appears
    val growAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { growAnim.animateTo(1f, tween(700, easing = EaseOutCubic)) }
    val grow = growAnim.value
    Canvas(modifier) {
        val n = days.size.coerceAtLeast(1)
        val slot = size.width / n
        val bw = (slot * 0.62f).coerceAtMost(26.dp.toPx())
        days.forEachIndexed { i, (d, u) ->
            val x = i * slot + (slot - bw) / 2
            val hd = size.height * d / max * grow
            val hu = size.height * u / max * grow
            val alpha = if (i == days.lastIndex) 1f else 0.7f
            if (hd > 0) drawRoundRect(down.copy(alpha = alpha), Offset(x, size.height - hd), Size(bw, hd), CornerRadius(6.dp.toPx()))
            if (hu > 0) drawRoundRect(up.copy(alpha = alpha), Offset(x, size.height - hd - hu - 2.dp.toPx()), Size(bw, hu), CornerRadius(6.dp.toPx()))
        }
    }
}

// ======================================================================== widgets

@Composable
private fun WidgetHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = tint, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun Card(color: Color = MaterialTheme.colorScheme.surfaceContainer, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val inner: @Composable () -> Unit = { Column(Modifier.fillMaxSize().padding(18.dp), content = content) }
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = color.copy(alpha = 0.94f), modifier = Modifier.fillMaxSize()) { inner() }
    else Surface(shape = shape, color = color.copy(alpha = 0.94f), modifier = Modifier.fillMaxSize()) { inner() }
}

/** Ping of the current server, with a live chart while connected. */
@Composable
fun PingWidget(app: AppController, w: HomeWidget) {
    val state by app.state.collectAsState()
    val pings by app.pings.collectAsState()
    val testing by app.testing.collectAsState()
    val history by app.pingHistory.collectAsState()
    val status by app.core.status.collectAsState()
    val node = state.nodes.firstOrNull { it.id == state.settings.selectedNodeId } ?: state.nodes.firstOrNull()
    val p = node?.id?.let { pings[it] }
    val cs = MaterialTheme.colorScheme
    val color = when { p == null || p < 0 -> cs.onSurfaceVariant; p < 150 -> cs.tertiary; p < 400 -> cs.primary; else -> cs.error }
    val chart = w.opts["chart"] != "off" && w.h >= 3
    Card(onClick = app::pingCurrentReal) {
        WidgetHeader(Icons.Rounded.Speed, L("Пинг")) { AnimatedVisibility(node?.id in testing) { LoadingIndicator(Modifier.size(20.dp)) } }
        Spacer(Modifier.weight(if (chart) 0.1f else 1f))
        Row(verticalAlignment = Alignment.Bottom) {
            RollingText(when { p == null -> "—"; p == PING_UNRELIABLE -> "?"; p < 0 -> "×"; else -> "$p" }, MaterialTheme.typography.displaySmall, color)
            if (p != null && p >= 0) Text(L(" мс"), style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            val ok = history.filter { it >= 0 }
            if (chart && ok.isNotEmpty()) Text("min ${ok.min()} · avg ${ok.average().toInt()} · max ${ok.max()}", style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (chart) {
            Spacer(Modifier.height(8.dp))
            if (status is CoreStatus.Running && history.size >= 2) LineChart(history.map { it.toFloat() }, color, Modifier.fillMaxWidth().weight(1f), minRange = 100f)
            else Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(L("График появится после подключения"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** All servers with pings; sortable; click to switch. */
@Composable
fun ServersWidget(app: AppController, w: HomeWidget, onOptions: (Map<String, String>) -> Unit) {
    val state by app.state.collectAsState()
    val pings by app.pings.collectAsState()
    val testing by app.testing.collectAsState()
    val sort = ServerSort.entries.firstOrNull { it.name == w.opts["sort"] } ?: ServerSort.PING
    val list = remember(state.nodes, state.hidden, pings, sort) {
        val visible = state.nodes.filter { it.identity !in state.hidden }
        when (sort) {
            ServerSort.MANUAL -> visible
            ServerSort.NAME -> visible.sortedBy { splitLeadingFlag(it.name).second.lowercase() }
            ServerSort.PING -> visible.sortedBy { n -> pings[n.id]?.takeIf { it >= 0 } ?: Int.MAX_VALUE }
            ServerSort.LAST_USED -> visible.sortedByDescending { it.lastUsedAt }
        }
    }
    Card {
        WidgetHeader(Icons.Rounded.FormatListBulleted, L("Серверы") + " · ${list.size}") {
            var menu by remember { mutableStateOf(false) }
            Box {
                AssistChip({ menu = true }, label = { Text(sort.title, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(16.dp)) }, modifier = Modifier.height(30.dp))
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                    ServerSort.entries.forEach { o -> DropdownMenuItem({ Text(o.title) }, { menu = false; onOptions(w.opts + ("sort" to o.name)) },
                        leadingIcon = { if (o == sort) Icon(Icons.Rounded.Check, null) }) }
                }
            }
            IconButton({ app.pingAll(list.map { it.id }) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.NetworkPing, L("Пинг всех"), Modifier.size(18.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.id }) { n ->
                val sel = n.id == state.settings.selectedNodeId
                val (flag, title) = remember(n.name) { splitLeadingFlag(n.name) }
                Surface(onClick = { app.select(n) }, shape = RoundedCornerShape(16.dp),
                    color = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier.animateItem(placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec())) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        NodeAvatar(flag, n.protocol, sel, Modifier.scale(0.78f))
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            FlagText(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (sel) FontWeight.SemiBold else null)
                            Text(n.protocol.title.lowercase() + if (state.settings.maskAddresses) "" else " · ${n.endpoint}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PingChip(pings[n.id], n.id in testing)
                    }
                }
            }
        }
    }
}

/** Days and gigabytes left, with an optional chart of how the quota was spent. */
@Composable
fun QuotaWidget(app: AppController, w: HomeWidget) {
    val state by app.state.collectAsState()
    val node = state.nodes.firstOrNull { it.id == state.settings.selectedNodeId } ?: state.nodes.firstOrNull()
    val sub = state.subscriptions.firstOrNull { it.id == node?.subscriptionId } ?: state.subscriptions.firstOrNull()
    val cs = MaterialTheme.colorScheme
    if (sub == null) { Card { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(L("Нет данных о подписке"), color = cs.onSurfaceVariant) } }; return }
    val warn = sub.expired || sub.usedUp
    val used = sub.upload + sub.download
    val daysLeft = if (sub.expire > 0) ((sub.expire * 1000 - System.currentTimeMillis()) / 86_400_000).coerceAtLeast(0) else -1L
    // period length guessed from the first refresh we saw (fallback: a month)
    val periodDays = sub.usageHistory.firstOrNull()?.let { ((sub.expire * 1000 - it.t) / 86_400_000).coerceAtLeast(daysLeft) }?.takeIf { it > 0 } ?: 30L
    val chart = w.opts["chart"] == "on" && w.h >= 3
    Card(color = if (warn) cs.errorContainer else cs.secondaryContainer) {
        WidgetHeader(Icons.Rounded.DataUsage, sub.name, if (warn) cs.onErrorContainer else cs.onSecondaryContainer)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (daysLeft >= 0) {
                val frac by animateFloatAsState((daysLeft.toFloat() / periodDays).coerceIn(0f, 1f), MaterialTheme.motionScheme.slowSpatialSpec(), label = "days")
                Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(progress = { frac }, modifier = Modifier.size(64.dp))
                    Text("$daysLeft", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                if (daysLeft >= 0) Text(L("Осталось %1 дн.", daysLeft), style = MaterialTheme.typography.titleSmall)
                Text(if (sub.total > 0) L("%1 из %2", formatBytes(used), formatBytes(sub.total)) else L("Использовано %1 · безлимит", formatBytes(used)),
                    style = MaterialTheme.typography.bodySmall)
                if (sub.total > 0) {
                    Spacer(Modifier.height(6.dp))
                    val f by animateFloatAsState((used.toFloat() / sub.total).coerceIn(0f, 1f), MaterialTheme.motionScheme.slowSpatialSpec(), label = "gb")
                    LinearWavyProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = if (f > 0.9f) cs.error else cs.primary)
                }
            }
        }
        if (chart) {
            Spacer(Modifier.height(10.dp))
            val pts = sub.usageHistory
            if (pts.size >= 2) LineChart(pts.map { it.used / 1_073_741_824f }, cs.primary, Modifier.fillMaxWidth().weight(1f), minRange = 1f)
            else Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(L("График заполнится после нескольких обновлений подписки"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Internet use per day for the last two weeks. */
@Composable
fun UsageWidget(app: AppController) {
    val s = app.state.collectAsState().value.settings
    val days = remember(s.usageDown, s.usageUp) {
        (13 downTo 0).map { LocalDate.now().minusDays(it.toLong()).toString() }.map { (s.usageDown[it] ?: 0L) to (s.usageUp[it] ?: 0L) }
    }
    val today = days.last().let { it.first + it.second }
    val month = LocalDate.now().toString().take(7).let { m -> s.usageDown.filterKeys { it.startsWith(m) }.values.sum() + s.usageUp.filterKeys { it.startsWith(m) }.values.sum() }
    Card {
        WidgetHeader(Icons.Rounded.BarChart, L("Использование интернета"))
        Spacer(Modifier.height(6.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text(L("Сегодня"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RollingText(formatBytes(today), MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
            }
            Column(Modifier.weight(1f)) {
                Text(L("За месяц"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RollingText(formatBytes(month), MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Spacer(Modifier.height(10.dp))
        UsageBars(days, Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val fmt = DateTimeFormatter.ofPattern("d MMM", dev.uplinx.I18n.locale)
            Text(LocalDate.now().minusDays(13).format(fmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape).align(Alignment.CenterVertically))
            Text(" ↓  ", style = MaterialTheme.typography.labelSmall)
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape).align(Alignment.CenterVertically))
            Text(" ↑  ", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(L("Сегодня"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Sites checked through the VPN: reachable or not, and how fast. */
@Composable
fun SitesWidget(app: AppController) {
    val s = app.state.collectAsState().value.settings
    val results by app.siteResults.collectAsState()
    val checking by app.sitesChecking.collectAsState()
    val connected = app.core.status.collectAsState().value is CoreStatus.Running
    var adding by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    Card {
        WidgetHeader(Icons.Rounded.FactCheck, L("Доступность сайтов")) {
            if (checking) LoadingIndicator(Modifier.size(22.dp))
            else IconButton({ app.checkSites() }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Refresh, L("Проверить"), Modifier.size(18.dp)) }
        }
        Text(if (connected) L("Через VPN · раз в минуту") else L("Без VPN — подключитесь, чтобы проверить через туннель"),
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(s.sites, key = { it }) { site ->
                val r = results[site]
                val okColor = when {
                    r == null -> cs.outline
                    r.ms < 0 -> cs.error
                    r.code in 200..399 -> Color(0xFF2EC27E)
                    else -> cs.tertiary
                }
                Row(Modifier.fillMaxWidth().animateItem().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val pulse by animateFloatAsState(if (checking) 0.4f else 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
                    Box(Modifier.size(10.dp).background(okColor.copy(alpha = if (checking) pulse else 1f), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(site, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when {
                        r == null -> "—"
                        r.ms < 0 -> L("недоступен")
                        r.code in 200..399 -> "${r.ms} ms"
                        else -> "HTTP ${r.code}"
                    }, style = MaterialTheme.typography.labelMedium, color = okColor)
                    IconButton({ app.setSites(s.sites - site) }, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, L("Удалить"), Modifier.size(14.dp)) }
                }
            }
        }
        OutlinedTextField(adding, { adding = it.trim() }, placeholder = { Text(L("Добавить сайт, например github.com")) }, singleLine = true,
            shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            trailingIcon = {
                IconButton({ if (adding.isNotBlank()) { app.setSites(s.sites + adding.removePrefix("https://").removePrefix("http://").trimEnd('/')); adding = "" } },
                    enabled = adding.isNotBlank()) { Icon(Icons.Rounded.Add, L("Добавить")) }
            })
    }
}

/** Which widgets have options, and the options dialog. */
val WidgetType.hasOptions get() = this in setOf(WidgetType.PING, WidgetType.QUOTA, WidgetType.SERVERS)

@Composable
fun WidgetOptionsDialog(w: HomeWidget, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    var opts by remember { mutableStateOf(w.opts) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tune, null) },
        title = { Text(L("Настройки виджета")) },
        text = {
            Column(Modifier.width(420.dp)) {
                when (w.type) {
                    WidgetType.PING -> SwitchRow(Icons.Rounded.ShowChart, L("График пинга"), L("Нужна высота от 3 клеток"), opts["chart"] != "off") { v -> opts = opts + ("chart" to if (v) "on" else "off") }
                    WidgetType.QUOTA -> SwitchRow(Icons.Rounded.ShowChart, L("График расхода трафика"), L("Нужна высота от 3 клеток"), opts["chart"] == "on") { v -> opts = opts + ("chart" to if (v) "on" else "off") }
                    WidgetType.SERVERS -> {
                        Text(L("Сортировка"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ServerSort.entries.forEach { o -> FilterChip((opts["sort"] ?: ServerSort.PING.name) == o.name, { opts = opts + ("sort" to o.name) }, label = { Text(o.title) }) }
                        }
                    }
                    else -> Text(L("У этого виджета нет настроек"))
                }
            }
        },
        confirmButton = { Button({ onSave(opts) }) { Text(L("Сохранить")) } },
        dismissButton = { TextButton(onDismiss) { Text(L("Отмена")) } },
    )
}
