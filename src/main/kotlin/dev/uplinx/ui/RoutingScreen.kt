@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import dev.uplinx.AppController
import dev.uplinx.data.*

@Composable
fun RoutingScreen(app: AppController) {
    val state by app.state.collectAsState()
    val r = state.routing
    var editRule by remember { mutableStateOf<RouteRule?>(null) }
    var pickApps by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(L("Маршрутизация"), style = MaterialTheme.typography.headlineMedium) }
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
                        SwitchRow(Icons.Rounded.Block, L("Блокировать рекламу"), "geosite:category-ads-all", r.blockAds) { v -> app.updateRouting { it.copy(blockAds = v) } }
                        SwitchRow(Icons.Rounded.Flag, L("Российские сайты напрямую"), L("geosite:category-ru, .ru/.su/.рф, geoip:ru"), r.bypassRu) { v -> app.updateRouting { it.copy(bypassRu = v) } }
                        SwitchRow(Icons.Rounded.Shield, L("Через прокси только заблокированное"), L("geosite:ru-blocked (нужны гео-базы runetfreedom)"), r.proxyBlockedRu) { v ->
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
                    SwitchRow(Icons.Rounded.Router, L("Локальная сеть напрямую"), L("geoip:private — роутер, принтеры, NAS"), r.bypassLan) { v -> app.updateRouting { it.copy(bypassLan = v) } }
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(L("Стратегия доменов"), style = MaterialTheme.typography.bodyLarge)
                            Text(L("Как сопоставлять домены с IP-правилами"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(L("Свои правила"), style = MaterialTheme.typography.titleLarge)
                    Text(L("Проверяются сверху вниз и важнее пресетов"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { pickApps = true }) {
                    Icon(Icons.Rounded.Apps, null); Spacer(Modifier.width(6.dp)); Text(L("Приложения"))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { editRule = RouteRule(kind = RuleKind.DOMAIN, value = "", action = RouteAction.PROXY) }) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(L("Правило"))
                }
            }
        }
        if (r.rules.isEmpty()) item {
            Text(L("Например: telegram.exe → прокси, example.org → напрямую, порты 6881-6889 → блок."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        items(r.rules, key = { it.id }) { rule ->
            RuleRow(rule, Modifier.animateItem(),
                onToggle = { v -> app.upsertRule(rule.copy(enabled = v)) },
                onEdit = { editRule = rule }, onUp = { app.moveRule(rule, -1) }, onDown = { app.moveRule(rule, 1) }, onDelete = { app.deleteRule(rule) })
        }
    }
    editRule?.let { rule -> RuleDialog(rule, onDismiss = { editRule = null }) { app.upsertRule(it); editRule = null } }
    if (pickApps) AppPickerDialog(onDismiss = { pickApps = false }) { apps, action ->
        pickApps = false
        app.upsertRule(RouteRule(kind = RuleKind.PROCESS, value = apps.joinToString(", "), action = action))
        if (state.settings.connectMode != ConnectMode.TUN) app.notify(L("Правила по приложениям надёжнее всего работают в режиме TUN"))
    }
}

/** Windows' answer to Android's per-app VPN: pick running programs and send them through proxy, direct or block. */
@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (List<String>, RouteAction) -> Unit) {
    val all = remember { dev.uplinx.core.Processes.running() }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var action by remember { mutableStateOf(RouteAction.PROXY) }
    var manual by remember { mutableStateOf("") }
    val shown = all.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Apps, null) },
        title = { Text(L("Приложения")) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text(L("Поиск по запущенным программам")) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = CircleShape, modifier = Modifier.fillMaxWidth())
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(280.dp)) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(shown, key = { it }) { exe ->
                            Row(Modifier.fillMaxWidth().clickable { picked = if (exe in picked) picked - exe else picked + exe }.padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(exe in picked, { picked = if (it) picked + exe else picked - exe })
                                Text(exe, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono))
                            }
                        }
                    }
                }
                OutlinedTextField(manual, { manual = it }, label = { Text(L("Или вписать вручную: game.exe, steam.exe")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    RouteAction.entries.forEachIndexed { i, a ->
                        ToggleButton(action == a, { action = a }, Modifier.weight(1f),
                            shapes = when (i) { 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(); RouteAction.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes(); else -> ButtonGroupDefaults.connectedMiddleButtonShapes() }) {
                            Text(a.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val list = (picked + manual.split(',').map { it.trim() }.filter { it.isNotEmpty() }).toList()
            Button({ onPick(list, action) }, enabled = list.isNotEmpty()) { Text(L("Добавить правило")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(L("Отмена")) } },
    )
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
            IconButton(onUp) { Icon(Icons.Rounded.KeyboardArrowUp, L("Выше")) }
            IconButton(onDown) { Icon(Icons.Rounded.KeyboardArrowDown, L("Ниже")) }
            IconButton(onDelete) { Icon(Icons.Rounded.DeleteOutline, L("Удалить")) }
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
        title = { Text(L("Правило")) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(L("Что проверяем"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleKind.entries.forEach { k ->
                        FilterChip(r.kind == k, { r = r.copy(kind = k) }, label = { Text(k.title) },
                            leadingIcon = { AnimatedVisibility(r.kind == k) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } })
                    }
                }
                OutlinedTextField(r.value, { r = r.copy(value = it) }, label = { Text(L("Значения через запятую")) }, modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(when (r.kind) {
                            RuleKind.DOMAIN -> "youtube.com, full:api.x.com, keyword:google, regexp:\\.ir$"
                            RuleKind.IP -> "1.1.1.1, 10.0.0.0/8, 2001:db8::/32"
                            RuleKind.GEOSITE -> "google, telegram, category-ru, netflix"
                            RuleKind.GEOIP -> "ru, us, telegram, private"
                            RuleKind.PROCESS -> L("telegram.exe, chrome.exe (точнее всего работает в TUN)")
                            RuleKind.PORT -> "443, 1000-2000"
                        })
                    })
                Text(L("Куда отправить"), style = MaterialTheme.typography.labelLarge)
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
        confirmButton = { Button({ onSave(r.copy(value = r.value.trim())) }, enabled = r.value.isNotBlank()) { Text(L("Сохранить")) } },
        dismissButton = { TextButton(onDismiss) { Text(L("Отмена")) } },
    )
}
