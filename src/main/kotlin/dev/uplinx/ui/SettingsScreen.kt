@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import dev.uplinx.AppController
import dev.uplinx.core.WinUtil
import dev.uplinx.data.*

@Composable
fun SettingsScreen(app: AppController) {
    val state by app.state.collectAsState()
    val version by app.core.version.collectAsState()
    val s = state.settings
    fun set(block: (AppSettings) -> AppSettings) = app.updateSettings(block)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Настройки"), style = MaterialTheme.typography.headlineMedium)

        // ------------------------------------------------ look
        SettingsGroup(L("Оформление")) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(L("Тема"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.themeMode == m, { set { it.copy(themeMode = m) } }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(m.title) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Translate, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(L("Язык"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    val langs = listOf("system" to L("Системный"), "ru" to "Русский", "en" to "English")
                    langs.forEachIndexed { i, (code, label) ->
                        SegmentedButton(s.language == code, { set { it.copy(language = code) } }, SegmentedButtonDefaults.itemShape(i, langs.size)) { Text(label) }
                    }
                }
            }
            SwitchRow(Icons.Rounded.Palette, L("Цвет из акцента Windows"), L("Палитра Material You строится из цвета, выбранного в Персонализации"), s.colorSource == ColorSource.WINDOWS_ACCENT) { v ->
                set { it.copy(colorSource = if (v) ColorSource.WINDOWS_ACCENT else ColorSource.CUSTOM) }
            }
            AnimatedVisibility(s.colorSource == ColorSource.CUSTOM, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SeedPresets.forEach { c -> ColorSwatch(Color(c), s.seedColor == c) { set { it.copy(seedColor = c) } } }
                }
            }
            Text(L("Стиль палитры"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaletteStyle.entries.forEach { p ->
                    FilterChip(s.paletteStyle == p.name, { set { it.copy(paletteStyle = p.name) } }, label = { Text(p.name) },
                        leadingIcon = { AnimatedVisibility(s.paletteStyle == p.name) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } })
                }
            }
            SwitchRow(Icons.Rounded.Contrast, L("Чёрная тема (AMOLED)"), L("Чистый чёрный фон в тёмном режиме"), s.amoled) { v -> set { it.copy(amoled = v) } }
            SwitchRow(Icons.Rounded.VisibilityOff, L("Скрывать адреса"), L("В списке, на главной и в трее видны только названия и протокол — удобно для стримов и скриншотов"), s.maskAddresses) { v -> set { it.copy(maskAddresses = v) } }
            val mini by app.miniWindow.collectAsState()
            SwitchRow(Icons.Rounded.PictureInPicture, L("Мини-окно"), L("Маленькая кнопка подключения поверх всех окон"), mini) { v -> app.miniWindow.value = v }
        }

        // ------------------------------------------------ connection
        SettingsGroup(L("Подключение")) {
            SwitchRow(Icons.Rounded.Lan, L("Режим TUN"), L("Весь трафик системы через виртуальный адаптер. Нужны права администратора."), s.connectMode == ConnectMode.TUN) { v ->
                app.setConnectMode(if (v) ConnectMode.TUN else ConnectMode.SYSTEM_PROXY)
            }
            AnimatedVisibility(s.connectMode == ConnectMode.TUN) {
                SwitchRow(Icons.Rounded.Security, L("Защита от утечек DNS"), L("Фильтры WFP: DNS и трафик мимо туннеля блокируются"), s.tunStrictDns) { v -> set { it.copy(tunStrictDns = v) } }
            }
            NumberRow(Icons.Rounded.SettingsEthernet, L("Порт SOCKS5"), s.socksPort) { v -> set { it.copy(socksPort = v) } }
            NumberRow(Icons.Rounded.Http, L("Порт HTTP"), s.httpPort) { v -> set { it.copy(httpPort = v) } }
            SwitchRow(Icons.Rounded.Devices, L("Доступ из локальной сети"), L("Слушать 0.0.0.0 — другие устройства смогут использовать прокси"), s.allowLan) { v -> set { it.copy(allowLan = v) } }
            SwitchRow(Icons.Rounded.ManageSearch, "Sniffing", L("Определять домен по TLS/HTTP для точной маршрутизации"), s.sniffing) { v -> set { it.copy(sniffing = v) } }
            SwitchRow(Icons.Rounded.Language, "IPv6", L("Пускать IPv6 через туннель и резолвить AAAA-записи"), s.ipv6) { v -> set { it.copy(ipv6 = v) } }
            SwitchRow(Icons.Rounded.CallSplit, "Mux", L("Мультиплексирование (не для Vision и xhttp)"), s.mux) { v -> set { it.copy(mux = v) } }
            TextRow(Icons.Rounded.Dns, L("Удалённый DNS"), s.remoteDns) { v -> set { it.copy(remoteDns = v) } }
            TextRow(Icons.Rounded.Dns, L("Прямой DNS (для РФ-сайтов)"), s.directDns) { v -> set { it.copy(directDns = v) } }
            TextRow(Icons.Rounded.Speed, L("URL проверки задержки"), s.pingUrl) { v -> set { it.copy(pingUrl = v) } }
        }

        // ------------------------------------------------ core
        SettingsGroup(L("Ядро")) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Xray-core", style = MaterialTheme.typography.bodyLarge)
                    Text(version?.let { L("Версия %1", it) } ?: L("Не установлено — скачается при первом подключении"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton({ app.updateCore() }) { Text(if (version == null) L("Скачать") else L("Обновить")) }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(L("Гео-базы"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    GeoSource.entries.forEachIndexed { i, g ->
                        SegmentedButton(s.geoSource == g, { set { it.copy(geoSource = g) }; app.updateGeo() }, SegmentedButtonDefaults.itemShape(i, GeoSource.entries.size)) { Text(g.title) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton({ app.updateGeo() }) { Icon(Icons.Rounded.Refresh, L("Обновить гео-базы")) }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BugReport, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(L("Уровень логов"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    val lv = listOf("debug", "info", "warning", "error")
                    lv.forEachIndexed { i, l -> SegmentedButton(s.logLevel == l, { set { it.copy(logLevel = l) } }, SegmentedButtonDefaults.itemShape(i, lv.size)) { Text(l) } }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ WinUtil.openFolder(Paths.core) }) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(L("Папка ядра")) }
                OutlinedButton({ WinUtil.openFolder(Paths.home) }) { Icon(Icons.Rounded.Folder, null); Spacer(Modifier.width(6.dp)); Text(L("Данные приложения")) }
            }
        }

        // ------------------------------------------------ app
        SettingsGroup(L("Приложение")) {
            var auto by remember { mutableStateOf(WinUtil.autostart) }
            SwitchRow(Icons.Rounded.RocketLaunch, L("Запуск вместе с Windows"), null, auto) { v -> WinUtil.autostart = v; auto = WinUtil.autostart }
            SwitchRow(Icons.Rounded.PowerSettingsNew, L("Подключаться при запуске"), null, s.autoConnect) { v -> set { it.copy(autoConnect = v) } }
            SwitchRow(Icons.Rounded.Minimize, L("Сворачивать в трей при закрытии"), null, s.minimizeToTray) { v -> set { it.copy(minimizeToTray = v) } }
        }

        // ------------------------------------------------ panels
        SettingsGroup(L("Подписки и панели")) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Badge, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L("Представляться клиентом"), style = MaterialTheme.typography.bodyLarge)
                        Text(L("Некоторые панели (Remnawave, Marzban) отдают полный набор функций только знакомым клиентам"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClientSpoof.entries.forEach { c -> SpoofTile(c, s.clientSpoof == c) { set { it.copy(clientSpoof = c) } } }
                }
                AnimatedContent(s.clientSpoof, label = "ua") { c ->
                    Text("User-Agent: ${c.ua ?: s.userAgent}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                }
            }
            AnimatedVisibility(s.clientSpoof == ClientSpoof.NONE) {
                TextRow(Icons.Rounded.Edit, L("Свой User-Agent"), s.userAgent) { v -> set { it.copy(userAgent = v) } }
            }
            SwitchRow(Icons.Rounded.AltRoute, L("Маршрутизация от сервиса"), L("Применять профиль роутинга и правила, которые присылает подписка"), s.applyPanelRouting) { v -> set { it.copy(applyPanelRouting = v) } }
        }

        // ------------------------------------------------ about
        SettingsGroup(L("О программе")) {
            val upd by app.update.collectAsState()
            val progress by app.updateProgress.collectAsState()
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "about")
                val rot by inf.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(20000, easing = androidx.compose.animation.core.LinearEasing)), label = "r")
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, PolygonShape(MaterialShapes.Cookie9Sided, rot)), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.size(32.dp)) { drawUplinxMark() }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Uplinx ${dev.uplinx.core.BuildInfo.version}", style = MaterialTheme.typography.titleMedium)
                    Text(L("Xray %1 · за всё время ↓ %2 · ↑ %3", version ?: "—", formatBytes(s.allTimeDown), formatBytes(s.allTimeUp)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (upd != null) Button({ app.showUpdateDetails.value = true }, enabled = progress == null) { Text(if (progress != null) L("Загрузка…") else L("Обновить до %1", upd!!.version)) }
                else FilledTonalButton({ app.checkForUpdates() }) { Text(L("Проверить обновления")) }
            }
            progress?.let { p -> LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) }
            SwitchRow(Icons.Rounded.Update, L("Проверять обновления при запуске"), null, s.checkUpdates) { v -> set { it.copy(checkUpdates = v) } }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ WinUtil.openUrl("https://github.com/${dev.uplinx.core.BuildInfo.REPO}") }) { Icon(Icons.Rounded.Code, null); Spacer(Modifier.width(6.dp)); Text("GitHub") }
                OutlinedButton({ WinUtil.openUrl("https://github.com/${dev.uplinx.core.BuildInfo.REPO}/issues") }) { Icon(Icons.Rounded.BugReport, null); Spacer(Modifier.width(6.dp)); Text(L("Сообщить об ошибке")) }
            }
            Text(L("Флаги — Twemoji (CC-BY 4.0). Ядро — Xray-core (MPL-2.0)."), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

/** Square tile picker for the client the app presents itself as to panels. */
@Composable
private fun SpoofTile(c: ClientSpoof, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primary else cs.surfaceContainerHighest, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "spoof")
    val fg by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "spooff")
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "spoofc")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = RoundedCornerShape(corner), color = bg, contentColor = fg, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                when (c) {
                    ClientSpoof.NONE -> Icon(Icons.Rounded.Block, null, Modifier.size(32.dp))
                    ClientSpoof.HAPP -> Text("H", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    ClientSpoof.INCY -> Text("INCY", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
        Text(c.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val inner by animateDpAsState(if (selected) 12.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "sw")
    Box(Modifier.size(48.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AnimatedVisibility(selected, enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(Modifier.size(48.dp - inner).border(3.dp, MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun TextRow(icon: ImageVector, label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
            trailingIcon = {
                AnimatedVisibility(text != value, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    IconButton({ onCommit(text.trim()) }) { Icon(Icons.Rounded.Check, L("Применить")) }
                }
            })
    }
}

@Composable
private fun NumberRow(icon: ImageVector, label: String, value: Int, onCommit: (Int) -> Unit) =
    TextRow(icon, label, value.toString()) { v -> v.toIntOrNull()?.takeIf { it in 1..65535 }?.let(onCommit) }
