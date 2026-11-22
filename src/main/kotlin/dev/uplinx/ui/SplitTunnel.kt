@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.uplinx.AppController
import dev.uplinx.core.Processes
import dev.uplinx.data.ConnectMode
import dev.uplinx.data.SplitMode

/** Remembers exe paths we have seen (running or picked), so chosen apps keep their icons. */
private object KnownApps {
    val paths = java.util.concurrent.ConcurrentHashMap<String, String>()
}

@Composable
fun AppIcon(exe: String, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val bmp = remember(exe) { Processes.icon(KnownApps.paths[exe.lowercase()])?.toComposeImageBitmap() }
    if (bmp != null) Image(bmp, null, Modifier.size(size))
    else Box(Modifier.size(size), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Apps, null, Modifier.size(size * 0.8f), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Per-app split tunnelling: only chosen apps through the VPN, or everything except them. */
@Composable
fun SplitTunnelCard(app: AppController) {
    val s = app.state.collectAsState().value.settings
    val split = s.split
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { KnownApps.paths.putAll(Processes.runningWithPaths()) }
    fun set(block: (dev.uplinx.data.SplitTunnel) -> dev.uplinx.data.SplitTunnel) = app.updateSettings { it.copy(split = block(it.split)) }

    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            SwitchRow(Icons.Rounded.CallSplit, L("Раздельное туннелирование"), L("Выберите, какие программы идут через VPN"), split.enabled) { v -> set { it.copy(enabled = v) } }
            AnimatedVisibility(split.enabled) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        SplitMode.entries.forEachIndexed { i, m ->
                            ToggleButton(split.mode == m, { set { it.copy(mode = m) } }, Modifier.weight(1f),
                                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                                Icon(if (m == SplitMode.INCLUDE) Icons.Rounded.Login else Icons.Rounded.Logout, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text(m.title)
                            }
                        }
                    }
                    if (s.connectMode != ConnectMode.TUN) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(10.dp))
                                Text(L("Точнее всего работает в режиме TUN: в режиме системного прокси программы, которые игнорируют прокси, всё равно пойдут напрямую."),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
                                TextButton({ app.setConnectMode(ConnectMode.TUN) }) { Text(L("Включить TUN")) }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        split.apps.forEach { exe ->
                            InputChip(true, {}, label = { Text(exe) }, leadingIcon = { AppIcon(exe, 20.dp) },
                                trailingIcon = { Icon(Icons.Rounded.Close, L("Удалить"), Modifier.size(16.dp).clickable { set { it.copy(apps = it.apps - exe) } }) })
                        }
                        AssistChip({ picking = true }, label = { Text(L("Добавить приложения")) }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
                    }
                    if (split.apps.isEmpty()) Text(
                        if (split.mode == SplitMode.INCLUDE) L("Пока список пуст, через VPN не пойдёт ничего.") else L("Пока список пуст, через VPN идёт всё."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (picking) SplitAppPicker(split.apps, onDismiss = { picking = false }) { list -> picking = false; set { it.copy(apps = list) } }
}

@Composable
private fun SplitAppPicker(current: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val running = remember { Processes.runningWithPaths().also { KnownApps.paths.putAll(it) } }
    var picked by remember { mutableStateOf(current.toSet()) }
    var query by remember { mutableStateOf("") }
    val all = (running.keys + current.map { it.lowercase() }).distinct().sorted()
    val shown = all.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Apps, null) },
        title = { Text(L("Приложения")) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text(L("Поиск по запущенным программам")) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = CircleShape, modifier = Modifier.fillMaxWidth())
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(shown, key = { it }) { exe ->
                            val on = picked.any { it.equals(exe, ignoreCase = true) }
                            Row(Modifier.fillMaxWidth().clickable { picked = if (on) picked.filterNot { it.equals(exe, true) }.toSet() else picked + exe }
                                .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, null)
                                Spacer(Modifier.width(4.dp))
                                AppIcon(exe, 24.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(exe, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                OutlinedButton({
                    val fd = java.awt.FileDialog(null as java.awt.Frame?, L("Выберите программу"), java.awt.FileDialog.LOAD)
                    fd.file = "*.exe"; fd.isMultipleMode = true; fd.isVisible = true
                    fd.files?.forEach { f -> KnownApps.paths[f.name.lowercase()] = f.path; picked = picked + f.name }
                }) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(L("Выбрать .exe…")) }
            }
        },
        confirmButton = { Button({ onDone(picked.toList().sortedBy { it.lowercase() }) }) { Text(L("Готово")) } },
        dismissButton = { TextButton(onDismiss) { Text(L("Отмена")) } },
    )
}

