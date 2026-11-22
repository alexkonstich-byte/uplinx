@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.myproxy.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.myproxy.AppController
import dev.myproxy.data.*

@Composable
fun RoutingScreen(app: AppController) {
    val state by app.state.collectAsState()
    val r = state.routing
    var editRule by remember { mutableStateOf<RouteRule?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Маршрутизация", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoutingMode.entries.forEach { m ->
                    ModeCard(m.title, m.subtitle, when (m) { RoutingMode.RULE -> Icons.Rounded.AltRoute; RoutingMode.GLOBAL -> Icons.Rounded.TravelExplore; RoutingMode.DIRECT -> Icons.Rounded.ArrowOutward },
                        selected = r.mode == m, modifier = Modifier.weight(1f)) { app.setRoutingMode(m) }
                }
            }
        }
        item {
            AnimatedVisibility(r.mode == RoutingMode.RULE, enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut()) {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SwitchRow(Icons.Rounded.Block, "Блокировать рекламу", "geosite:category-ads-all", r.blockAds) { v -> app.updateRouting { it.copy(blockAds = v) } }
                        SwitchRow(Icons.Rounded.Flag, "Российские сайты напрямую", "geosite:category-ru, .ru/.su/.рф, geoip:ru", r.bypassRu) { v -> app.updateRouting { it.copy(bypassRu = v) } }
                        SwitchRow(Icons.Rounded.Shield, "Через прокси только заблокированное", "geosite:ru-blocked (нужны гео-базы runetfreedom)", r.proxyBlockedRu) { v ->
                            app.updateRouting { it.copy(proxyBlockedRu = v) }
                            if (v && state.settings.geoSource != GeoSource.RUNETFREEDOM) {
                                app.updateSettings { it.copy(geoSource = GeoSource.RUNETFREEDOM) }
                                app.updateGeo()
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SwitchRow(Icons.Rounded.Router, "Локальная сеть напрямую", "geoip:private — роутер, принтеры, NAS", r.bypassLan) { v -> app.updateRouting { it.copy(bypassLan = v) } }
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Стратегия доменов", style = MaterialTheme.typography.bodyLarge)
                            Text("Как сопоставлять домены с IP-правилами", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SingleChoiceSegmentedButtonRow {
                            val opts = listOf("AsIs", "IPIfNonMatch", "IPOnDemand")
                            opts.forEachIndexed { i, o ->
                                SegmentedButton(r.domainStrategy == o, { app.updateRouting { it.copy(domainStrategy = o) } }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(o) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Свои правила", style = MaterialTheme.typography.titleLarge)
                    Text("Проверяются сверху вниз и важнее пресетов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { editRule = RouteRule(kind = RuleKind.DOMAIN, value = "", action = RouteAction.PROXY) }) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Правило")
                }
            }
        }
        if (r.rules.isEmpty()) item {
            Text("Например: telegram.exe → прокси, example.org → напрямую, порты 6881-6889 → блок.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        items(r.rules, key = { it.id }) { rule ->
            RuleRow(rule, Modifier.animateItem(),
                onToggle = { v -> app.upsertRule(rule.copy(enabled = v)) },
                onEdit = { editRule = rule }, onUp = { app.moveRule(rule, -1) }, onDown = { app.moveRule(rule, 1) }, onDelete = { app.deleteRule(rule) })
        }
    }
    editRule?.let { rule -> RuleDialog(rule, onDismiss = { editRule = null }) { app.upsertRule(it); editRule = null } }
}

@Composable
private fun ModeCard(title: String, subtitle: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerLow, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "mc")
    val corner by androidx.compose.animation.core.animateDpAsState(if (selected) 32.dp else 20.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "mcc")
    Surface(onClick = onClick, shape = RoundedCornerShape(corner), color = bg, border = if (selected) null else BorderStroke(1.dp, cs.outlineVariant), modifier = modifier) {
        Column(Modifier.padding(20.dp)) {
            Icon(icon, null, tint = if (selected) cs.onPrimaryContainer else cs.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(icon: ImageVector?, title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, enabled = enabled, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onChange, enabled = enabled, thumbContent = {
                AnimatedContent(checked, label = "sw") { c -> Icon(if (c) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize)) }
            })
        }
    }
}

@Composable
private fun RuleRow(rule: RouteRule, modifier: Modifier, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (rule.action) {
        RouteAction.PROXY -> cs.primaryContainer to cs.onPrimaryContainer
        RouteAction.DIRECT -> cs.tertiaryContainer to cs.onTertiaryContainer
        RouteAction.BLOCK -> cs.errorContainer to cs.onErrorContainer
    }
    Surface(onClick = onEdit, shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = bg, contentColor = fg) { Text(rule.action.title, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(rule.value, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rule.kind.title, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            IconButton(onUp) { Icon(Icons.Rounded.KeyboardArrowUp, "Выше") }
            IconButton(onDown) { Icon(Icons.Rounded.KeyboardArrowDown, "Ниже") }
            IconButton(onDelete) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
            Switch(rule.enabled, onToggle)
        }
    }
}

@Composable
private fun RuleDialog(initial: RouteRule, onDismiss: () -> Unit, onSave: (RouteRule) -> Unit) {
    var r by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AltRoute, null) },
        title = { Text("Правило") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Что проверяем", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleKind.entries.forEach { k ->
                        FilterChip(r.kind == k, { r = r.copy(kind = k) }, label = { Text(k.title) },
                            leadingIcon = { AnimatedVisibility(r.kind == k) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } })
                    }
                }
                OutlinedTextField(r.value, { r = r.copy(value = it) }, label = { Text("Значения через запятую") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(when (r.kind) {
                            RuleKind.DOMAIN -> "youtube.com, full:api.x.com, keyword:google, regexp:\\.ir$"
                            RuleKind.IP -> "1.1.1.1, 10.0.0.0/8, 2001:db8::/32"
                            RuleKind.GEOSITE -> "google, telegram, category-ru, netflix"
                            RuleKind.GEOIP -> "ru, us, telegram, private"
                            RuleKind.PROCESS -> "telegram.exe, chrome.exe (точнее всего работает в TUN)"
                            RuleKind.PORT -> "443, 1000-2000"
                        })
                    })
                Text("Куда отправить", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    RouteAction.entries.forEachIndexed { i, a ->
                        ToggleButton(r.action == a, { r = r.copy(action = a) }, Modifier.weight(1f),
                            shapes = when (i) { 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(); RouteAction.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes(); else -> ButtonGroupDefaults.connectedMiddleButtonShapes() }) {
                            Text(a.title)
                        }
                    }
                }
            }
        },
        confirmButton = { Button({ onSave(r.copy(value = r.value.trim())) }, enabled = r.value.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
