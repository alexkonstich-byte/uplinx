@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uplinx.AppController
import dev.uplinx.core.WinUtil
import dev.uplinx.data.Subscription
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SubscriptionsScreen(app: AppController) {
    val state by app.state.collectAsState()
    val updating by app.updatingSubs.collectAsState()
    var edit by remember { mutableStateOf<Subscription?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("Подписки"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (state.subscriptions.isNotEmpty()) TextButton(onClick = app::updateAllSubscriptions) {
                Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(6.dp)); Text(L("Обновить все"))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(L("Добавить")) }
        }
        if (state.subscriptions.isEmpty()) {
            EmptyState(Icons.Rounded.CloudSync, L("Нет подписок"),
                L("Подписка — это ссылка от вашего VPN-сервиса. Сервера и остаток трафика будут обновляться автоматически.")) {
                Button(onClick = { adding = true }) { Text(L("Добавить подписку")) }
                OutlinedButton(onClick = app::importFromClipboard) { Text(L("Из буфера")) }
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(380.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.subscriptions.sortedByDescending { it.pinned }, key = { it.id }) { s ->
                SubscriptionCard(s, count = state.nodes.count { it.subscriptionId == s.id }, isUpdating = s.id in updating,
                    modifier = Modifier.animateItem(placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
                    onUpdate = { app.updateSubscription(s.id) }, onEdit = { edit = s }, onDelete = { app.deleteSubscription(s) },
                    onPin = { app.togglePin(s) },
                    onCopy = { app.copyToClipboard(s.url, L("Ссылка скопирована")) })
            }
        }
    }

    if (adding) SubscriptionDialog(null, onDismiss = { adding = false }) { name, url, hours -> adding = false; app.addSubscription(name, url); }
    edit?.let { s ->
        SubscriptionDialog(s, onDismiss = { edit = null }) { name, url, hours ->
            app.editSubscription(s.copy(name = name, url = url, autoUpdateHours = hours)); edit = null
        }
    }
}

@Composable
private fun SubscriptionCard(
    s: Subscription, count: Int, isUpdating: Boolean, modifier: Modifier,
    onUpdate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit, onPin: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val spin = if (isUpdating) loopingFloat(900, "sync") * 360f else 0f
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubscriptionLogo(s.logoFile, 44.dp, spin = if (isUpdating) spin else 0f, selected = s.pinned)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlagText(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        if (s.pinned) Icon(Icons.Rounded.PushPin, L("Закреплена"), Modifier.padding(start = 6.dp).size(16.dp), tint = cs.primary)
                    }
                    Text(L("%1 серв. · обновлено %2", count, formatAgo(s.updatedAt)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onUpdate, enabled = !isUpdating) {
                    Icon(Icons.Rounded.Sync, L("Обновить"), Modifier.rotate(if (isUpdating) -spin else 0f))
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                        DropdownMenuItem({ Text(if (s.pinned) L("Открепить") else L("Закрепить")) }, { menu = false; onPin() }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                        DropdownMenuItem({ Text(L("Изменить")) }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        DropdownMenuItem({ Text(L("Копировать ссылку")) }, { menu = false; onCopy() }, leadingIcon = { Icon(Icons.Rounded.Link, null) })
                        if (s.webPageUrl.isNotBlank()) DropdownMenuItem({ Text(L("Сайт сервиса")) }, { menu = false; WinUtil.openUrl(s.webPageUrl) }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text(L("Удалить"), color = cs.error) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
                    }
                }
            }
            AnimatedVisibility(isUpdating) { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) }
            if (s.expired || s.usedUp) {
                Surface(color = cs.errorContainer, contentColor = cs.onErrorContainer, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 10.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (s.expired) L("Подписка истекла") else L("Трафик исчерпан"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (s.total > 0) {
                Spacer(Modifier.height(14.dp))
                val used = s.upload + s.download
                val frac by animateFloatAsState((used.toFloat() / s.total).coerceIn(0f, 1f), MaterialTheme.motionScheme.slowSpatialSpec(), label = "q")
                LinearWavyProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(),
                    color = if (frac > 0.9f) cs.error else cs.primary)
                Spacer(Modifier.height(6.dp))
                Text(L("%1 из %2", formatBytes(used), formatBytes(s.total)), style = MaterialTheme.typography.bodySmall)
            } else if (s.upload + s.download > 0) {
                Spacer(Modifier.height(10.dp))
                Text(L("Использовано %1 · безлимит", formatBytes(s.upload + s.download)), style = MaterialTheme.typography.bodySmall)
            }
            if (s.expire > 0) {
                val days = (s.expire * 1000 - System.currentTimeMillis()) / 86_400_000
                val date = DateTimeFormatter.ofPattern("d MMMM yyyy", dev.uplinx.I18n.locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(s.expire))
                AssistChip(onClick = {}, label = { Text(if (days >= 0) L("До %1 · %2 дн.", date, days) else L("Истекла %1", date)) },
                    leadingIcon = { Icon(Icons.Rounded.Event, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(top = 8.dp),
                    colors = if (days < 3) AssistChipDefaults.assistChipColors(labelColor = cs.error, leadingIconContentColor = cs.error) else AssistChipDefaults.assistChipColors())
            }
            if (s.announce.isNotBlank()) {
                Surface(color = cs.tertiaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(Icons.Rounded.Campaign, null, tint = cs.onTertiaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(s.announce, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
                    }
                }
            }
            if (s.lastError.isNotBlank()) {
                Text(L("Ошибка: %1", s.lastError), style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 8.dp))
            }
            if (s.supportUrl.isNotBlank()) {
                TextButton(onClick = { WinUtil.openUrl(s.supportUrl) }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Rounded.SupportAgent, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L("Поддержка"))
                }
            }
        }
    }
}

@Composable
private fun SubscriptionDialog(initial: Subscription?, onDismiss: () -> Unit, onSave: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var hours by remember { mutableStateOf((initial?.autoUpdateHours ?: 12).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CloudSync, null) },
        title = { Text(if (initial == null) L("Новая подписка") else L("Изменить подписку")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(480.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text(L("URL подписки")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(L("Название (необязательно)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(L("Если пусто — возьмём из заголовка profile-title")) })
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit) }, label = { Text(L("Автообновление, часов (0 — выкл.)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(name, url.trim(), hours.toIntOrNull() ?: 12) }, enabled = url.startsWith("http")) { Text(L("Сохранить")) } },
        dismissButton = { TextButton(onDismiss) { Text(L("Отмена")) } },
    )
}
