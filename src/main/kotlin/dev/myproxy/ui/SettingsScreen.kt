@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.myproxy.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
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
import dev.myproxy.AppController
import dev.myproxy.core.WinUtil
import dev.myproxy.data.*

@Composable
fun SettingsScreen(app: AppController) {
    val state by app.state.collectAsState()
    val version by app.core.version.collectAsState()
    val s = state.settings
    fun set(block: (AppSettings) -> AppSettings) = app.updateSettings(block)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)

        // ------------------------------------------------ look
        SettingsGroup("Оформление") {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Тема", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.themeMode == m, { set { it.copy(themeMode = m) } }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(m.title) }
                    }
                }
            }
            SwitchRow(Icons.Rounded.Palette, "Цвет из акцента Windows", "Палитра Material You строится из цвета, выбранного в Персонализации", s.colorSource == ColorSource.WINDOWS_ACCENT) { v ->
                set { it.copy(colorSource = if (v) ColorSource.WINDOWS_ACCENT else ColorSource.CUSTOM) }
            }
            AnimatedVisibility(s.colorSource == ColorSource.CUSTOM, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SeedPresets.forEach { c -> ColorSwatch(Color(c), s.seedColor == c) { set { it.copy(seedColor = c) } } }
                }
            }
            Text("Стиль палитры", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaletteStyle.entries.forEach { p ->
                    FilterChip(s.paletteStyle == p.name, { set { it.copy(paletteStyle = p.name) } }, label = { Text(p.name) },
                        leadingIcon = { AnimatedVisibility(s.paletteStyle == p.name) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } })
                }
            }
            SwitchRow(Icons.Rounded.Contrast, "Чёрная тема (AMOLED)", "Чистый чёрный фон в тёмном режиме", s.amoled) { v -> set { it.copy(amoled = v) } }
        }

        // ------------------------------------------------ connection
        SettingsGroup("Подключение") {
            SwitchRow(Icons.Rounded.Lan, "Режим TUN", "Весь трафик системы через виртуальный адаптер. Нужны права администратора.", s.connectMode == ConnectMode.TUN) { v ->
                app.setConnectMode(if (v) ConnectMode.TUN else ConnectMode.SYSTEM_PROXY)
            }
            AnimatedVisibility(s.connectMode == ConnectMode.TUN) {
                SwitchRow(Icons.Rounded.Security, "Защита от утечек DNS", "Фильтры WFP: DNS и трафик мимо туннеля блокируются", s.tunStrictDns) { v -> set { it.copy(tunStrictDns = v) } }
            }
            NumberRow(Icons.Rounded.SettingsEthernet, "Порт SOCKS5", s.socksPort) { v -> set { it.copy(socksPort = v) } }
            NumberRow(Icons.Rounded.Http, "Порт HTTP", s.httpPort) { v -> set { it.copy(httpPort = v) } }
            SwitchRow(Icons.Rounded.Devices, "Доступ из локальной сети", "Слушать 0.0.0.0 — другие устройства смогут использовать прокси", s.allowLan) { v -> set { it.copy(allowLan = v) } }
            SwitchRow(Icons.Rounded.ManageSearch, "Sniffing", "Определять домен по TLS/HTTP для точной маршрутизации", s.sniffing) { v -> set { it.copy(sniffing = v) } }
            SwitchRow(Icons.Rounded.CallSplit, "Mux", "Мультиплексирование (не для Vision и xhttp)", s.mux) { v -> set { it.copy(mux = v) } }
            TextRow(Icons.Rounded.Dns, "Удалённый DNS", s.remoteDns) { v -> set { it.copy(remoteDns = v) } }
            TextRow(Icons.Rounded.Dns, "Прямой DNS (для РФ-сайтов)", s.directDns) { v -> set { it.copy(directDns = v) } }
            TextRow(Icons.Rounded.Speed, "URL проверки задержки", s.pingUrl) { v -> set { it.copy(pingUrl = v) } }
        }

        // ------------------------------------------------ core
        SettingsGroup("Ядро") {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Xray-core", style = MaterialTheme.typography.bodyLarge)
                    Text(version?.let { "Версия $it" } ?: "Не установлено — скачается при первом подключении", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton({ app.updateCore() }) { Text(if (version == null) "Скачать" else "Обновить") }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Гео-базы", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    GeoSource.entries.forEachIndexed { i, g ->
                        SegmentedButton(s.geoSource == g, { set { it.copy(geoSource = g) }; app.updateGeo() }, SegmentedButtonDefaults.itemShape(i, GeoSource.entries.size)) { Text(g.title) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton({ app.updateGeo() }) { Icon(Icons.Rounded.Refresh, "Обновить гео-базы") }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BugReport, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Уровень логов", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    val lv = listOf("debug", "info", "warning", "error")
                    lv.forEachIndexed { i, l -> SegmentedButton(s.logLevel == l, { set { it.copy(logLevel = l) } }, SegmentedButtonDefaults.itemShape(i, lv.size)) { Text(l) } }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ WinUtil.openFolder(Paths.core) }) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Папка ядра") }
                OutlinedButton({ WinUtil.openFolder(Paths.home) }) { Icon(Icons.Rounded.Folder, null); Spacer(Modifier.width(6.dp)); Text("Данные приложения") }
            }
        }

        // ------------------------------------------------ app
        SettingsGroup("Приложение") {
            var auto by remember { mutableStateOf(WinUtil.autostart) }
            SwitchRow(Icons.Rounded.RocketLaunch, "Запуск вместе с Windows", null, auto) { v -> WinUtil.autostart = v; auto = WinUtil.autostart }
            SwitchRow(Icons.Rounded.PowerSettingsNew, "Подключаться при запуске", null, s.autoConnect) { v -> set { it.copy(autoConnect = v) } }
            SwitchRow(Icons.Rounded.Minimize, "Сворачивать в трей при закрытии", null, s.minimizeToTray) { v -> set { it.copy(minimizeToTray = v) } }
            TextRow(Icons.Rounded.Badge, "User-Agent подписок", s.userAgent) { v -> set { it.copy(userAgent = v) } }
        }
        Text("MyProxy 1.0 · Xray ${version ?: "—"} · Material You", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
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
                    IconButton({ onCommit(text.trim()) }) { Icon(Icons.Rounded.Check, "Применить") }
                }
            })
    }
}

@Composable
private fun NumberRow(icon: ImageVector, label: String, value: Int, onCommit: (Int) -> Unit) =
    TextRow(icon, label, value.toString()) { v -> v.toIntOrNull()?.takeIf { it in 1..65535 }?.let(onCommit) }
