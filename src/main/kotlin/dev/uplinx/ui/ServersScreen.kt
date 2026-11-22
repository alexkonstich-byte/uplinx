@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.uplinx.AppController
import dev.uplinx.core.XrayConfig
import dev.uplinx.data.*
import dev.uplinx.parse.LinkExporter
import dev.uplinx.parse.WgConfParser

@Composable
fun ServersScreen(app: AppController) {
    val state by app.state.collectAsState()
    val pings by app.pings.collectAsState()
    val testing by app.testing.collectAsState()
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<ProxyNode?>(null) }
    var qrFor by remember { mutableStateOf<ProxyNode?>(null) }
    var configFor by remember { mutableStateOf<ProxyNode?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var announce by remember { mutableStateOf<Subscription?>(null) }
    var speedFor by remember { mutableStateOf<ProxyNode?>(null) }
    val s = state.settings
    val mask = s.maskAddresses

    val visibleNodes = remember(state.nodes, state.hidden, s.showHidden) { state.nodes.filter { s.showHidden || it.identity !in state.hidden } }
    val hiddenCount = remember(state.nodes, state.hidden) { state.nodes.count { it.identity in state.hidden } }
    val groups = remember(visibleNodes, state.subscriptions, query, s.serverSort, pings) {
        val q = query.trim().lowercase()
        val filtered = visibleNodes.filter { q.isEmpty() || it.name.lowercase().contains(q) || (!mask && it.address.lowercase().contains(q)) || it.protocol.title.lowercase().contains(q) }
        val sorted = when (s.serverSort) {
            ServerSort.MANUAL -> filtered
            ServerSort.NAME -> filtered.sortedBy { it.name.lowercase() }
            ServerSort.PING -> filtered.sortedBy { n -> pings[n.id]?.takeIf { it >= 0 } ?: Int.MAX_VALUE }
            ServerSort.LAST_USED -> filtered.sortedByDescending { it.lastUsedAt }
        }
        val bySub = sorted.groupBy { it.subscriptionId }
        buildList {
            state.subscriptions.sortedByDescending { it.pinned }.forEach { sub -> bySub[sub.id]?.let { add(Triple(sub.id, sub, it)) } }
            bySub[null]?.let { add(Triple("manual", null, it)) }
            bySub.filterKeys { k -> k != null && state.subscriptions.none { it.id == k } }.values.flatten().takeIf { it.isNotEmpty() }
                ?.let { add(Triple("orphan", null, it)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ------------------------------------------------ header
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("Серверы"), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(10.dp))
            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Text("${state.nodes.size}", Modifier.padding(horizontal = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text(L("Поиск")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null) } } },
                shape = CircleShape, modifier = Modifier.width(240.dp),
            )
            Spacer(Modifier.width(8.dp))
            TooltipIcon(L("Пинг всех"), Icons.Rounded.NetworkPing) { app.pingAll() }
            TooltipIcon(L("Вставить (Ctrl+V)"), Icons.Rounded.ContentPaste) { app.importFromClipboard() }
            TooltipIcon(L("QR-код с экрана"), Icons.Rounded.QrCodeScanner) { app.scanScreenQr() }
            TooltipIcon(L("Открыть файл…"), Icons.Rounded.FileOpen) { pickFiles()?.let(app::importFiles) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(L("Добавить")) }
        }
        // ------------------------------------------------ chips: sort, hidden, subscription state/links/announcements
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                FilterChip(sortMenu || s.serverSort != ServerSort.MANUAL, { sortMenu = true }, label = { Text(s.serverSort.title) },
                    leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                DropdownMenu(sortMenu, { sortMenu = false }, shape = RoundedCornerShape(16.dp)) {
                    ServerSort.entries.forEach { o ->
                        DropdownMenuItem({ Text(o.title) }, { app.updateSettings { it.copy(serverSort = o) }; sortMenu = false },
                            leadingIcon = { if (o == s.serverSort) Icon(Icons.Rounded.Check, null) })
                    }
                }
            }
            AnimatedVisibility(hiddenCount > 0, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                FilterChip(s.showHidden, { app.updateSettings { it.copy(showHidden = !it.showHidden) } },
                    label = { Text(if (s.showHidden) L("Скрытые показаны: %1", hiddenCount) else L("Скрыто: %1", hiddenCount)) },
                    leadingIcon = { Icon(if (s.showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null, Modifier.size(18.dp)) })
            }
            state.subscriptions.forEach { sub ->
                if (sub.expired || sub.usedUp) AssistChip({}, label = { Text(if (sub.expired) L("%1: истекла", sub.name) else L("%1: трафик исчерпан", sub.name)) },
                    leadingIcon = { Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer, leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer), border = null)
                if (sub.announce.isNotBlank()) AssistChip({ announce = sub }, label = { Text(sub.name) },
                    leadingIcon = { Icon(Icons.Rounded.Campaign, null, Modifier.size(18.dp)) })
                sub.links.forEach { (label, url) ->
                    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), { PlainTooltip { Text("$label · $url") } }, rememberTooltipState()) {
                        AssistChip({ dev.uplinx.core.WinUtil.openUrl(url) }, label = { Text(when (label) { "support" -> L("Поддержка"); "website" -> L("Сайт"); else -> label }) },
                            leadingIcon = { Icon(if (label == "support") Icons.Rounded.SupportAgent else Icons.Rounded.Language, null, Modifier.size(18.dp)) })
                    }
                }
            }
        }

        if (state.nodes.isEmpty()) {
            EmptyState(
                Icons.Rounded.Dns, L("Пока нет серверов"),
                L("Вставьте ссылку (vless, vmess, trojan, ss, hy2, wireguard), ключ Amnezia vpn://, конфиг Clash/sing-box/Xray, ") +
                    L("перетащите файл или картинку с QR в окно, или добавьте подписку."),
            ) {
                Button(onClick = app::importFromClipboard) { Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text(L("Вставить")) }
                OutlinedButton(onClick = { app.scanScreenQr() }) { Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(L("QR с экрана")) }
                OutlinedButton(onClick = { showAdd = true }) { Text(L("Вручную")) }
            }
            return@Column
        }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 24.dp, end = 32.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (gid, sub, nodes) ->
                val isCollapsed = gid in collapsed
                // group headers stay pinned while their servers scroll under them
                stickyHeader(key = "h-$gid") {
                    GroupHeader(sub?.name ?: if (gid == "manual") L("Добавленные вручную") else L("Без подписки"), sub?.logoFile.orEmpty(), sub?.pinned == true,
                        nodes.size, isCollapsed, Modifier.animateItem(),
                        onToggle = { collapsed = if (isCollapsed) collapsed - gid else collapsed + gid },
                        onPing = { app.pingAll(nodes.map { it.id }) })
                }
                if (!isCollapsed) items(nodes, key = { it.id }) { n ->
                    val isHidden = n.identity in state.hidden
                    NodeRow(
                        n, selected = n.id == s.selectedNodeId, ping = pings[n.id], testing = n.id in testing, hidden = isHidden, mask = mask,
                        modifier = Modifier.animateItem(placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
                        onClick = { app.select(n) },
                        onPing = { app.pingNode(n.id) },
                        onSpeed = { speedFor = n; app.runSpeedTest(n) },
                        onEdit = { editing = n }, onQr = { qrFor = n }, onConfig = { configFor = n },
                        onCopy = { app.copyToClipboard(exportText(n), L("Ссылка скопирована")) },
                        onDuplicate = { app.upsertNode(n.copy(id = java.util.UUID.randomUUID().toString(), name = n.name + L(" (копия)"), subscriptionId = null, rawLink = "")) },
                        onHide = { app.setHidden(n, !isHidden) },
                        onDelete = { app.deleteNode(n) },
                    )
                }
            }
        }
        androidx.compose.foundation.VerticalScrollbar(
            androidx.compose.foundation.rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 8.dp, top = 4.dp, bottom = 12.dp),
            style = androidx.compose.foundation.ScrollbarStyle(
                minimalHeight = 48.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 250,
                unhoverColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
        )
        }
    }

    if (showAdd) AddLinkDialog(onDismiss = { showAdd = false }, onImport = { text -> showAdd = false; if (app.importText(text) == 0) app.notify(L("Не удалось распознать")) },
        onManual = { showAdd = false; editing = ProxyNode(name = L("Новый сервер"), protocol = Protocol.VLESS, address = "", port = 443) })
    editing?.let { n -> NodeEditorDialog(n, onDismiss = { editing = null }, onSave = { app.upsertNode(it); editing = null }) }
    qrFor?.let { n -> QrDialog(n.name, exportText(n)) { qrFor = null } }
    configFor?.let { n ->
        val text = if (n.needsAmneziaEngine) WgConfParser.toConf(n) else XrayConfig.build(n, state.settings, state.routing)
        TextViewerDialog(L("Конфиг · %1", n.name), text, onCopy = { app.copyToClipboard(text) }) { configFor = null }
    }
    speedFor?.let { n ->
        AlertDialog({ speedFor = null }, title = { FlagText(n.name, style = MaterialTheme.typography.titleLarge) },
            text = { Box(Modifier.size(380.dp, 400.dp)) { SpeedTestWidget(app, n) } },
            confirmButton = { TextButton({ speedFor = null }) { Text(L("Закрыть")) } })
    }
    announce?.let { sub ->
        AlertDialog({ announce = null }, icon = { Icon(Icons.Rounded.Campaign, null) }, title = { Text(sub.name) },
            text = { Text(sub.announce) }, confirmButton = { TextButton({ announce = null }) { Text(L("Понятно")) } })
    }
}

private fun exportText(n: ProxyNode) = if (n.protocol == Protocol.WIREGUARD && n.awg.isNotEmpty()) WgConfParser.toConf(n) else LinkExporter.toLink(n)

@Composable
fun TooltipIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
    ) { IconButton(onClick = onClick) { Icon(icon, label) } }
}

@Composable
private fun GroupHeader(title: String, logo: String, pinned: Boolean, count: Int, collapsed: Boolean, modifier: Modifier, onToggle: () -> Unit, onPing: () -> Unit) {
    val rot by animateFloatAsState(if (collapsed) -90f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chev")
    Surface(onClick = onToggle, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f), modifier = modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rot), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            if (logo.isNotBlank()) { SubscriptionLogo(logo, 24.dp); Spacer(Modifier.width(8.dp)) }
            FlagText(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(max = 600.dp))
            if (pinned) Icon(Icons.Rounded.PushPin, L("Закреплена"), Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Text("$count") }
            IconButton(onClick = onPing) { Icon(Icons.Rounded.NetworkPing, L("Пинг группы"), Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun NodeRow(
    n: ProxyNode, selected: Boolean, ping: Int?, testing: Boolean, hidden: Boolean, mask: Boolean, modifier: Modifier,
    onClick: () -> Unit, onPing: () -> Unit, onSpeed: () -> Unit, onEdit: () -> Unit, onQr: () -> Unit, onConfig: () -> Unit, onCopy: () -> Unit,
    onDuplicate: () -> Unit, onHide: () -> Unit, onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) cs.secondaryContainer else cs.surfaceContainerLow, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "row")
    val corner by androidx.compose.animation.core.animateDpAsState(if (selected) 28.dp else 16.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "corner")
    val alpha by animateFloatAsState(if (hidden) 0.5f else 1f, label = "hidden")
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onClick, shape = RoundedCornerShape(corner), color = container,
        border = if (selected) BorderStroke(2.dp, cs.primary) else null, modifier = modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val (flagId, title) = remember(n.name) { splitLeadingFlag(n.name) }
            NodeAvatar(flagId, n.protocol, selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlagText(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f, fill = false))
                    if (hidden) Icon(Icons.Rounded.VisibilityOff, L("Скрыт"), Modifier.padding(start = 6.dp).size(16.dp), tint = cs.onSurfaceVariant)
                }
                val extra = when {
                    n.protocol == Protocol.XRAY_CONFIG && n.balancerMembers.isNotEmpty() -> L(" · балансировщик: %1", n.balancerMembers.size)
                    n.protocol == Protocol.XRAY_CONFIG -> L(" · цепочка")
                    else -> " · ${n.transportLabel}"
                }
                Text(n.displayEndpoint(mask) + extra, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (n.allowInsecure && n.pinnedPeerCertSha256.isBlank()) {
                TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    { PlainTooltip { Text(L("В ссылке allowInsecure — Xray его больше не поддерживает. Сертификат будет проверяться.")) } }, rememberTooltipState()) {
                    Icon(Icons.Rounded.GppMaybe, null, tint = cs.error, modifier = Modifier.padding(end = 8.dp))
                }
            }
            PingChip(ping, testing)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, L("Меню")) }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                    DropdownMenuItem({ Text(L("Пинг")) }, { menu = false; onPing() }, leadingIcon = { Icon(Icons.Rounded.NetworkPing, null) })
                    DropdownMenuItem({ Text(L("Тест скорости")) }, { menu = false; onSpeed() }, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                    DropdownMenuItem({ Text(L("Изменить")) }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text(L("QR-код")) }, { menu = false; onQr() }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) })
                    DropdownMenuItem({ Text(L("Копировать ссылку")) }, { menu = false; onCopy() }, leadingIcon = { Icon(Icons.Rounded.Link, null) })
                    DropdownMenuItem({ Text(L("Показать конфиг")) }, { menu = false; onConfig() }, leadingIcon = { Icon(Icons.Rounded.DataObject, null) })
                    DropdownMenuItem({ Text(L("Дублировать")) }, { menu = false; onDuplicate() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    DropdownMenuItem({ Text(if (hidden) L("Показать") else L("Скрыть")) }, { menu = false; onHide() },
                        leadingIcon = { Icon(if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) })
                    HorizontalDivider()
                    DropdownMenuItem({ Text(L("Удалить"), color = cs.error) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, actions: @Composable RowScope.() -> Unit) {
    val rot = loopingFloat(30000, "empty") * 360f
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer, PolygonShape(MaterialShapes.SoftBurst, rot)))
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 460.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
    }
}

// ==================================================================== dialogs

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onImport: (String) -> Unit, onManual: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AddLink, null) },
        title = { Text(L("Добавить сервер")) },
        text = {
            Column {
                Text(L("Ссылки vless/vmess/trojan/ss/hy2/wireguard/socks/http, ключ Amnezia vpn://, .conf WireGuard/AmneziaWG, Clash/Mihomo YAML, sing-box или Xray JSON, список прокси host:port:user:pass или URL подписки."),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), placeholder = { Text(L("Вставьте сюда…")) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), shape = RoundedCornerShape(16.dp))
            }
        },
        confirmButton = { Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text(L("Импорт")) } },
        dismissButton = { Row { TextButton(onManual) { Text(L("Вручную")) }; TextButton(onDismiss) { Text(L("Отмена")) } } },
    )
}

@Composable
fun QrDialog(title: String, payload: String, onDismiss: () -> Unit) {
    val matrix = remember(payload) {
        runCatching { QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }.getOrNull()
    }
    val fg = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (matrix == null) Text(L("Слишком длинно для QR-кода"))
                else Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Canvas(Modifier.padding(16.dp).size(300.dp)) {
                        val cell = size.width / matrix.width
                        for (x in 0 until matrix.width) for (y in 0 until matrix.height) if (matrix[x, y])
                            drawRoundRect(fg, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f), androidx.compose.ui.geometry.CornerRadius(cell * 0.3f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(L("Готово")) } },
    )
}

@Composable
fun TextViewerDialog(title: String, text: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.width(640.dp).heightIn(max = 480.dp)) {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(L("Закрыть")) } },
        dismissButton = { TextButton(onCopy) { Text(L("Копировать")) } },
    )
}

@Composable
private fun <T> Dropdown(label: String, value: T, options: List<T>, display: (T) -> String = { it.toString() }, modifier: Modifier = Modifier, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(display(value), {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem({ Text(display(o)) }, { onChange(o); open = false }) }
        }
    }
}

@Composable
private fun NodeEditorDialog(initial: ProxyNode, onDismiss: () -> Unit, onSave: (ProxyNode) -> Unit) {
    var n by remember { mutableStateOf(initial) }
    @Composable fun field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), mono: Boolean = false, onChange: (String) -> Unit) =
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
            textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono) else MaterialTheme.typography.bodyLarge)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.address.isBlank()) L("Новый сервер") else L("Изменить сервер")) },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                field(L("Название"), n.name) { n = n.copy(name = it) }
                Dropdown(L("Протокол"), n.protocol, Protocol.entries, { it.title }) { n = n.copy(protocol = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    field(L("Адрес"), n.address, Modifier.weight(1f)) { n = n.copy(address = it.trim()) }
                    field(L("Порт"), n.port.toString(), Modifier.width(120.dp)) { v -> v.toIntOrNull()?.let { n = n.copy(port = it) } }
                }
                when (n.protocol) {
                    Protocol.VLESS -> {
                        field("UUID", n.uuid, mono = true) { n = n.copy(uuid = it.trim()) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Dropdown("Flow", n.flow, listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443"), { it.ifBlank { L("нет") } }, Modifier.weight(1f)) { n = n.copy(flow = it) }
                            field("Encryption", n.encryption, Modifier.weight(1f), mono = true) { n = n.copy(encryption = it) }
                        }
                    }
                    Protocol.VMESS -> {
                        field("UUID", n.uuid, mono = true) { n = n.copy(uuid = it.trim()) }
                        Dropdown(L("Шифрование"), n.vmessSecurity, listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) { n = n.copy(vmessSecurity = it) }
                    }
                    Protocol.SHADOWSOCKS -> {
                        Dropdown(L("Метод"), n.method, listOf("2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "none")) { n = n.copy(method = it) }
                        field(L("Пароль"), n.password, mono = true) { n = n.copy(password = it) }
                    }
                    Protocol.TROJAN, Protocol.HYSTERIA2 -> field(L("Пароль"), n.password, mono = true) { n = n.copy(password = it) }
                    Protocol.SOCKS, Protocol.HTTP -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        field(L("Логин"), n.username, Modifier.weight(1f)) { n = n.copy(username = it) }
                        field(L("Пароль"), n.password, Modifier.weight(1f)) { n = n.copy(password = it) }
                    }
                    Protocol.XRAY_CONFIG -> OutlinedTextField(n.rawConfig, { n = n.copy(rawConfig = it) }, label = { Text(L("Конфиг Xray (JSON)")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        isError = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(n.rawConfig) }.isFailure,
                        supportingText = { Text(L("Входящие подключения и статистику Uplinx добавит сам")) })
                    Protocol.WIREGUARD -> {
                        field(L("Приватный ключ"), n.wgPrivateKey, mono = true) { n = n.copy(wgPrivateKey = it.trim(), wgConfText = "") }
                        field(L("Публичный ключ сервера"), n.wgPublicKey, mono = true) { n = n.copy(wgPublicKey = it.trim(), wgConfText = "") }
                        field("PresharedKey", n.wgPreSharedKey, mono = true) { n = n.copy(wgPreSharedKey = it.trim(), wgConfText = "") }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            field(L("Адреса"), n.wgAddresses, Modifier.weight(1f)) { n = n.copy(wgAddresses = it, wgConfText = "") }
                            field("MTU", n.wgMtu.toString(), Modifier.width(100.dp)) { v -> v.toIntOrNull()?.let { n = n.copy(wgMtu = it, wgConfText = "") } }
                        }
                        field("Reserved (a,b,c)", n.wgReserved) { n = n.copy(wgReserved = it) }
                        if (n.awg.isNotEmpty()) {
                            SectionTitle("AmneziaWG")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                n.awg.forEach { (k, v) -> field(k, v, Modifier.width(120.dp), mono = true) { nv -> n = n.copy(awg = n.awg + (k to nv), wgConfText = "") } }
                            }
                            Text(if (n.needsAmneziaEngine) L("Используются S/H/I-параметры — подключение через официальный движок AmneziaWG (полный туннель).")
                                else L("Только Jc/Jmin/Jmax — работает прямо в Xray с маршрутизацией."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (n.protocol !in setOf(Protocol.WIREGUARD, Protocol.HYSTERIA2, Protocol.SOCKS, Protocol.HTTP, Protocol.SHADOWSOCKS, Protocol.XRAY_CONFIG)) {
                    SectionTitle(L("Транспорт"))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dropdown(L("Сеть"), n.network, listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "kcp"), modifier = Modifier.weight(1f)) { n = n.copy(network = it) }
                        Dropdown(L("Безопасность"), n.security, listOf("none", "tls", "reality"), modifier = Modifier.weight(1f)) { n = n.copy(security = it) }
                    }
                    AnimatedVisibility(n.network != "tcp" || n.headerType == "http") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                field("Host", n.host, Modifier.weight(1f)) { n = n.copy(host = it) }
                                if (n.network == "grpc") field("serviceName", n.serviceName, Modifier.weight(1f)) { n = n.copy(serviceName = it) }
                                else field("Path", n.path, Modifier.weight(1f)) { n = n.copy(path = it) }
                            }
                            if (n.network == "xhttp") Dropdown(L("Режим xhttp"), n.xhttpMode, listOf("auto", "packet-up", "stream-up", "stream-one")) { n = n.copy(xhttpMode = it) }
                        }
                    }
                }
                AnimatedVisibility(n.security == "tls" || n.security == "reality" || n.protocol == Protocol.HYSTERIA2) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(if (n.security == "reality") "REALITY" else "TLS")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            field("SNI", n.sni, Modifier.weight(1f)) { n = n.copy(sni = it) }
                            Dropdown("Fingerprint", n.fingerprint, listOf("", "chrome", "firefox", "safari", "edge", "ios", "android", "random", "randomized"), { it.ifBlank { L("по умолчанию") } }, Modifier.weight(1f)) { n = n.copy(fingerprint = it) }
                        }
                        if (n.security == "reality") {
                            field("Public key", n.publicKey, mono = true) { n = n.copy(publicKey = it.trim()) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                field("Short ID", n.shortId, Modifier.weight(1f), mono = true) { n = n.copy(shortId = it.trim()) }
                                field("SpiderX", n.spiderX, Modifier.weight(1f)) { n = n.copy(spiderX = it) }
                            }
                        } else {
                            field("ALPN", n.alpn) { n = n.copy(alpn = it) }
                            field(L("SHA-256 сертификата (pinnedPeerCertSha256)"), n.pinnedPeerCertSha256, mono = true) { n = n.copy(pinnedPeerCertSha256 = it.trim()) }
                        }
                    }
                }
                if (n.protocol == Protocol.HYSTERIA2) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dropdown("Obfs", n.obfs, listOf("", "salamander"), { it.ifBlank { L("нет") } }, Modifier.weight(1f)) { n = n.copy(obfs = it) }
                    if (n.obfs.isNotBlank()) field(L("Пароль obfs"), n.obfsPassword, Modifier.weight(1f)) { n = n.copy(obfsPassword = it) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(n.copy(rawLink = "")) }, enabled = n.address.isNotBlank() && n.port in 1..65535) { Text(L("Сохранить")) } },
        dismissButton = { TextButton(onDismiss) { Text(L("Отмена")) } },
    )
}

fun pickFiles(): List<java.io.File>? {
    val fd = java.awt.FileDialog(null as java.awt.Frame?, L("Импорт конфигурации"), java.awt.FileDialog.LOAD)
    fd.isMultipleMode = true
    fd.isVisible = true
    return fd.files?.toList()?.takeIf { it.isNotEmpty() }
}
