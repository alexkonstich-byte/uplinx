@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.myproxy.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import dev.myproxy.AppController
import dev.myproxy.core.XrayConfig
import dev.myproxy.data.*
import dev.myproxy.parse.LinkExporter
import dev.myproxy.parse.WgConfParser

@Composable
fun ServersScreen(app: AppController) {
    val state by app.state.collectAsState()
    val pings by app.pings.collectAsState()
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<ProxyNode?>(null) }
    var qrFor by remember { mutableStateOf<ProxyNode?>(null) }
    var configFor by remember { mutableStateOf<ProxyNode?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val groups = remember(state.nodes, state.subscriptions, query) {
        val q = query.trim().lowercase()
        val filtered = state.nodes.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.address.lowercase().contains(q) || it.protocol.title.lowercase().contains(q) }
        val bySub = filtered.groupBy { it.subscriptionId }
        buildList {
            state.subscriptions.forEach { s -> bySub[s.id]?.let { add(Triple(s.id, s.name, it)) } }
            bySub[null]?.let { add(Triple("manual", "Добавленные вручную", it)) }
            // nodes whose subscription was removed outside the app
            bySub.filterKeys { k -> k != null && state.subscriptions.none { it.id == k } }.values.flatten().takeIf { it.isNotEmpty() }
                ?.let { add(Triple("orphan", "Без подписки", it)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ------------------------------------------------ toolbar
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Серверы", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Поиск") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null) } } },
                shape = CircleShape, modifier = Modifier.width(260.dp),
            )
            Spacer(Modifier.width(8.dp))
            TooltipIcon("Пинг всех", Icons.Rounded.NetworkPing) { app.pingAll() }
            TooltipIcon("Сортировать по пингу", Icons.Rounded.Sort) { app.sortByPing() }
            TooltipIcon("Импорт из буфера (Ctrl+V)", Icons.Rounded.ContentPaste) { app.importFromClipboard() }
            TooltipIcon("Открыть файл…", Icons.Rounded.FileOpen) { pickFiles()?.let(app::importFiles) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить") }
        }

        if (state.nodes.isEmpty()) {
            EmptyState(
                Icons.Rounded.Dns, "Пока нет серверов",
                "Вставьте ссылку (vless, vmess, trojan, ss, hy2, wireguard), ключ Amnezia vpn://, " +
                    "перетащите .conf / .json файл в окно или добавьте подписку.",
            ) {
                Button(onClick = app::importFromClipboard) { Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Вставить из буфера") }
                OutlinedButton(onClick = { showAdd = true }) { Text("Ввести вручную") }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (gid, title, nodes) ->
                val isCollapsed = gid in collapsed
                item(key = "h-$gid") {
                    GroupHeader(title, nodes.size, isCollapsed, Modifier.animateItem(),
                        onToggle = { collapsed = if (isCollapsed) collapsed - gid else collapsed + gid },
                        onPing = { app.pingAll(nodes.map { it.id }) })
                }
                if (!isCollapsed) items(nodes, key = { it.id }) { n ->
                    NodeRow(
                        n, selected = n.id == state.settings.selectedNodeId, ping = pings[n.id],
                        modifier = Modifier.animateItem(),
                        onClick = { app.select(n) },
                        onEdit = { editing = n }, onQr = { qrFor = n }, onConfig = { configFor = n },
                        onCopy = { app.copyToClipboard(if (n.protocol == Protocol.WIREGUARD && n.awg.isNotEmpty()) WgConfParser.toConf(n) else LinkExporter.toLink(n), "Ссылка скопирована") },
                        onDuplicate = { app.upsertNode(n.copy(id = java.util.UUID.randomUUID().toString(), name = n.name + " (копия)", subscriptionId = null)) },
                        onDelete = { app.deleteNode(n) },
                    )
                }
            }
        }
    }

    if (showAdd) AddLinkDialog(onDismiss = { showAdd = false }, onImport = { text -> showAdd = false; if (app.importText(text) == 0) app.notify("Не удалось распознать ссылку") },
        onManual = { showAdd = false; editing = ProxyNode(name = "Новый сервер", protocol = Protocol.VLESS, address = "", port = 443) })
    editing?.let { n -> NodeEditorDialog(n, onDismiss = { editing = null }, onSave = { app.upsertNode(it); editing = null }) }
    qrFor?.let { n -> QrDialog(n.name, if (n.protocol == Protocol.WIREGUARD && n.awg.isNotEmpty()) WgConfParser.toConf(n) else LinkExporter.toLink(n)) { qrFor = null } }
    configFor?.let { n ->
        val text = if (n.needsAmneziaEngine) WgConfParser.toConf(n) else XrayConfig.build(n, state.settings, state.routing)
        TextViewerDialog("Конфиг · ${n.name}", text, onCopy = { app.copyToClipboard(text) }) { configFor = null }
    }
}

@Composable
fun TooltipIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
    ) { IconButton(onClick = onClick) { Icon(icon, label) } }
}

@Composable
private fun GroupHeader(title: String, count: Int, collapsed: Boolean, modifier: Modifier, onToggle: () -> Unit, onPing: () -> Unit) {
    val rot by animateFloatAsState(if (collapsed) -90f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chev")
    Surface(onClick = onToggle, shape = CircleShape, color = Color.Transparent, modifier = modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rot), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Text("$count") }
            IconButton(onClick = onPing) { Icon(Icons.Rounded.NetworkPing, "Пинг группы", Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun NodeRow(
    n: ProxyNode, selected: Boolean, ping: Int?, modifier: Modifier,
    onClick: () -> Unit, onEdit: () -> Unit, onQr: () -> Unit, onConfig: () -> Unit, onCopy: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val container by androidx.compose.animation.animateColorAsState(if (selected) cs.secondaryContainer else cs.surfaceContainerLow, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "row")
    val corner by androidx.compose.animation.core.animateDpAsState(if (selected) 28.dp else 16.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "corner")
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onClick, shape = RoundedCornerShape(corner), color = container,
        border = if (selected) BorderStroke(2.dp, cs.primary) else null, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProtocolAvatar(n.protocol, selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(n.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                Text("${n.endpoint} · ${n.protocol.title} · ${n.transportLabel}", style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (n.allowInsecure && n.pinnedPeerCertSha256.isBlank()) {
                TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    { PlainTooltip { Text("В ссылке allowInsecure — Xray его больше не поддерживает. Сертификат будет проверяться.") } }, rememberTooltipState()) {
                    Icon(Icons.Rounded.GppMaybe, null, tint = cs.error, modifier = Modifier.padding(end = 8.dp))
                }
            }
            PingChip(ping)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Меню") }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                    DropdownMenuItem({ Text("Изменить") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text("QR-код") }, { menu = false; onQr() }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) })
                    DropdownMenuItem({ Text("Копировать ссылку") }, { menu = false; onCopy() }, leadingIcon = { Icon(Icons.Rounded.Link, null) })
                    DropdownMenuItem({ Text("Показать конфиг") }, { menu = false; onConfig() }, leadingIcon = { Icon(Icons.Rounded.DataObject, null) })
                    DropdownMenuItem({ Text("Дублировать") }, { menu = false; onDuplicate() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Удалить", color = cs.error) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, actions: @Composable RowScope.() -> Unit) {
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "empty")
    val rot by inf.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(30000, easing = androidx.compose.animation.core.LinearEasing)), label = "r")
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
        title = { Text("Добавить сервер") },
        text = {
            Column {
                Text("Ссылки vless:// vmess:// trojan:// ss:// hy2:// wireguard://, ключ Amnezia vpn://, текст .conf WireGuard/AmneziaWG, JSON Xray или URL подписки.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), placeholder = { Text("Вставьте сюда…") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), shape = RoundedCornerShape(16.dp))
            }
        },
        confirmButton = { Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Импорт") } },
        dismissButton = { Row { TextButton(onManual) { Text("Вручную") }; TextButton(onDismiss) { Text("Отмена") } } },
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
                if (matrix == null) Text("Слишком длинно для QR-кода")
                else Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Canvas(Modifier.padding(16.dp).size(300.dp)) {
                        val cell = size.width / matrix.width
                        for (x in 0 until matrix.width) for (y in 0 until matrix.height) if (matrix[x, y])
                            drawRoundRect(fg, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f), androidx.compose.ui.geometry.CornerRadius(cell * 0.3f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Готово") } },
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
        confirmButton = { TextButton(onDismiss) { Text("Закрыть") } },
        dismissButton = { TextButton(onCopy) { Text("Копировать") } },
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
        title = { Text(if (initial.address.isBlank()) "Новый сервер" else "Изменить сервер") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                field("Название", n.name) { n = n.copy(name = it) }
                Dropdown("Протокол", n.protocol, Protocol.entries, { it.title }) { n = n.copy(protocol = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    field("Адрес", n.address, Modifier.weight(1f)) { n = n.copy(address = it.trim()) }
                    field("Порт", n.port.toString(), Modifier.width(120.dp)) { v -> v.toIntOrNull()?.let { n = n.copy(port = it) } }
                }
                when (n.protocol) {
                    Protocol.VLESS -> {
                        field("UUID", n.uuid, mono = true) { n = n.copy(uuid = it.trim()) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Dropdown("Flow", n.flow, listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443"), { it.ifBlank { "нет" } }, Modifier.weight(1f)) { n = n.copy(flow = it) }
                            field("Encryption", n.encryption, Modifier.weight(1f), mono = true) { n = n.copy(encryption = it) }
                        }
                    }
                    Protocol.VMESS -> {
                        field("UUID", n.uuid, mono = true) { n = n.copy(uuid = it.trim()) }
                        Dropdown("Шифрование", n.vmessSecurity, listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) { n = n.copy(vmessSecurity = it) }
                    }
                    Protocol.SHADOWSOCKS -> {
                        Dropdown("Метод", n.method, listOf("2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "none")) { n = n.copy(method = it) }
                        field("Пароль", n.password, mono = true) { n = n.copy(password = it) }
                    }
                    Protocol.TROJAN, Protocol.HYSTERIA2 -> field("Пароль", n.password, mono = true) { n = n.copy(password = it) }
                    Protocol.SOCKS, Protocol.HTTP -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        field("Логин", n.username, Modifier.weight(1f)) { n = n.copy(username = it) }
                        field("Пароль", n.password, Modifier.weight(1f)) { n = n.copy(password = it) }
                    }
                    Protocol.WIREGUARD -> {
                        field("Приватный ключ", n.wgPrivateKey, mono = true) { n = n.copy(wgPrivateKey = it.trim(), wgConfText = "") }
                        field("Публичный ключ сервера", n.wgPublicKey, mono = true) { n = n.copy(wgPublicKey = it.trim(), wgConfText = "") }
                        field("PresharedKey", n.wgPreSharedKey, mono = true) { n = n.copy(wgPreSharedKey = it.trim(), wgConfText = "") }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            field("Адреса", n.wgAddresses, Modifier.weight(1f)) { n = n.copy(wgAddresses = it, wgConfText = "") }
                            field("MTU", n.wgMtu.toString(), Modifier.width(100.dp)) { v -> v.toIntOrNull()?.let { n = n.copy(wgMtu = it, wgConfText = "") } }
                        }
                        field("Reserved (a,b,c)", n.wgReserved) { n = n.copy(wgReserved = it) }
                        if (n.awg.isNotEmpty()) {
                            SectionTitle("AmneziaWG")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                n.awg.forEach { (k, v) -> field(k, v, Modifier.width(120.dp), mono = true) { nv -> n = n.copy(awg = n.awg + (k to nv), wgConfText = "") } }
                            }
                            Text(if (n.needsAmneziaEngine) "Используются S/H/I-параметры — подключение через официальный движок AmneziaWG (полный туннель)."
                                else "Только Jc/Jmin/Jmax — работает прямо в Xray с маршрутизацией.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (n.protocol !in setOf(Protocol.WIREGUARD, Protocol.HYSTERIA2, Protocol.SOCKS, Protocol.HTTP, Protocol.SHADOWSOCKS)) {
                    SectionTitle("Транспорт")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dropdown("Сеть", n.network, listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "kcp"), modifier = Modifier.weight(1f)) { n = n.copy(network = it) }
                        Dropdown("Безопасность", n.security, listOf("none", "tls", "reality"), modifier = Modifier.weight(1f)) { n = n.copy(security = it) }
                    }
                    AnimatedVisibility(n.network != "tcp" || n.headerType == "http") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                field("Host", n.host, Modifier.weight(1f)) { n = n.copy(host = it) }
                                if (n.network == "grpc") field("serviceName", n.serviceName, Modifier.weight(1f)) { n = n.copy(serviceName = it) }
                                else field("Path", n.path, Modifier.weight(1f)) { n = n.copy(path = it) }
                            }
                            if (n.network == "xhttp") Dropdown("Режим xhttp", n.xhttpMode, listOf("auto", "packet-up", "stream-up", "stream-one")) { n = n.copy(xhttpMode = it) }
                        }
                    }
                }
                AnimatedVisibility(n.security == "tls" || n.security == "reality" || n.protocol == Protocol.HYSTERIA2) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(if (n.security == "reality") "REALITY" else "TLS")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            field("SNI", n.sni, Modifier.weight(1f)) { n = n.copy(sni = it) }
                            Dropdown("Fingerprint", n.fingerprint, listOf("", "chrome", "firefox", "safari", "edge", "ios", "android", "random", "randomized"), { it.ifBlank { "по умолчанию" } }, Modifier.weight(1f)) { n = n.copy(fingerprint = it) }
                        }
                        if (n.security == "reality") {
                            field("Public key", n.publicKey, mono = true) { n = n.copy(publicKey = it.trim()) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                field("Short ID", n.shortId, Modifier.weight(1f), mono = true) { n = n.copy(shortId = it.trim()) }
                                field("SpiderX", n.spiderX, Modifier.weight(1f)) { n = n.copy(spiderX = it) }
                            }
                        } else {
                            field("ALPN", n.alpn) { n = n.copy(alpn = it) }
                            field("SHA-256 сертификата (pinnedPeerCertSha256)", n.pinnedPeerCertSha256, mono = true) { n = n.copy(pinnedPeerCertSha256 = it.trim()) }
                        }
                    }
                }
                if (n.protocol == Protocol.HYSTERIA2) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dropdown("Obfs", n.obfs, listOf("", "salamander"), { it.ifBlank { "нет" } }, Modifier.weight(1f)) { n = n.copy(obfs = it) }
                    if (n.obfs.isNotBlank()) field("Пароль obfs", n.obfsPassword, Modifier.weight(1f)) { n = n.copy(obfsPassword = it) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(n.copy(rawLink = "")) }, enabled = n.address.isNotBlank() && n.port in 1..65535) { Text("Сохранить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

fun pickFiles(): List<java.io.File>? {
    val fd = java.awt.FileDialog(null as java.awt.Frame?, "Импорт конфигурации", java.awt.FileDialog.LOAD)
    fd.isMultipleMode = true
    fd.isVisible = true
    return fd.files?.toList()?.takeIf { it.isNotEmpty() }
}
