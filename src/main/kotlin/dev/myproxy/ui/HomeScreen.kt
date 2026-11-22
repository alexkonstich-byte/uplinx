@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.myproxy.ui

import dev.myproxy.L

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.myproxy.AppController
import dev.myproxy.core.CoreStatus
import dev.myproxy.data.ConnectMode
import dev.myproxy.data.RoutingMode
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(app: AppController, onOpenServers: () -> Unit) {
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 980.dp
        val hero: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ConnectButton(conn, onClick = app::toggle, size = if (wide) 230f else 200f)
                StatusLine(status)
                Spacer(Modifier.height(20.dp))
                ModeSwitchers(state.settings.connectMode, state.routing.mode, app)
            }
        }
        val cards: @Composable ColumnScope.() -> Unit = {
            CurrentServerCard(node?.name, node?.let { (if (state.settings.maskAddresses) "" else it.endpoint + " · ") + "${it.protocol.title} · ${it.transportLabel}" }, node?.id?.let { pings[it] }, node?.id in testing, node?.needsAmneziaEngine == true,
                onClick = onOpenServers, onPing = app::pingCurrentReal)
            TrafficCard(traffic, conn == ConnState.ON)
            val sub = state.subscriptions.firstOrNull { it.id == node?.subscriptionId }
            if (sub != null && (sub.expired || sub.usedUp)) AssistChip({}, label = { Text(if (sub.expired) L("Подписка «%1» истекла", sub.name) else L("Трафик подписки «%1» исчерпан", sub.name)) },
                leadingIcon = { Icon(Icons.Rounded.ErrorOutline, null) }, border = null,
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer, labelColor = MaterialTheme.colorScheme.onErrorContainer, leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer))
            AnimatedVisibility(sub != null && (sub.total > 0 || sub.expire > 0), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                sub?.let { SubscriptionQuotaCard(it.name, it.upload + it.download, it.total, it.expire) }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { hero() }
                Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) { cards() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                hero()
                Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { cards() }
            }
        }
    }
}

@Composable
private fun StatusLine(status: CoreStatus) {
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
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (status is CoreStatus.Running) {
                RollingText(formatDuration(now - status.since), MaterialTheme.typography.titleMedium.copy(fontFamily = Mono), MaterialTheme.colorScheme.primary)
            } else if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
            }
            if (status is CoreStatus.Preparing) {
                Spacer(Modifier.height(8.dp))
                val p = status.progress
                if (p != null) LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.width(220.dp))
                else LinearWavyProgressIndicator(modifier = Modifier.width(220.dp))
            }
        }
    }
}

@Composable
private fun ModeSwitchers(connectMode: ConnectMode, routingMode: RoutingMode, app: AppController) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val (flagId, title) = remember(name) { name?.let(::splitLeadingFlag) ?: (null to null) }
            AnimatedContent(flagId, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f)) }, label = "flag") { f ->
                if (f != null) NodeAvatar(f, dev.myproxy.data.Protocol.VLESS, selected = true, modifier = Modifier.size(48.dp))
                else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = PolygonShape(MaterialShapes.Cookie7Sided), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxSize()) {}
                    Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
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
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrafficCard(t: dev.myproxy.core.Traffic, active: Boolean) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedValue(Icons.Rounded.ArrowDownward, L("Загрузка"), formatSpeed(t.down), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                SpeedValue(Icons.Rounded.ArrowUpward, L("Отдача"), formatSpeed(t.up), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            val alpha by animateFloatAsState(if (active) 1f else 0.35f, label = "chartA")
            SpeedChart(t.history, Modifier.fillMaxWidth().height(96.dp).graphicsAlpha(alpha))
            Spacer(Modifier.height(10.dp))
            Text(L("За сессию: ↓ %1 · ↑ %2", formatBytes(t.totalDown), formatBytes(t.totalUp)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a }

@Composable
private fun SpeedValue(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
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
private fun SubscriptionQuotaCard(name: String, used: Long, total: Long, expire: Long) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (total > 0) {
                Spacer(Modifier.height(10.dp))
                val frac by animateFloatAsState((used.toFloat() / total).coerceIn(0f, 1f), MaterialTheme.motionScheme.slowSpatialSpec(), label = "quota")
                LinearWavyProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(L("%1 из %2", formatBytes(used), formatBytes(total)), style = MaterialTheme.typography.bodySmall)
            }
            if (expire > 0) {
                val days = (expire * 1000 - System.currentTimeMillis()) / 86_400_000
                val date = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(expire))
                Text(if (days >= 0) L("Действует до %1 · осталось %2 дн.", date, days) else L("Истекла %1", date), style = MaterialTheme.typography.bodySmall,
                    color = if (days < 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
