@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.myproxy.ui

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
import dev.myproxy.AppController
import dev.myproxy.core.WinUtil
import dev.myproxy.data.Subscription
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
            Text("Подписки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (state.subscriptions.isNotEmpty()) TextButton(onClick = app::updateAllSubscriptions) {
                Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(6.dp)); Text("Обновить все")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить") }
        }
        if (state.subscriptions.isEmpty()) {
            EmptyState(Icons.Rounded.CloudSync, "Нет подписок",
                "Подписка — это ссылка от вашего VPN-сервиса. Сервера и остаток трафика будут обновляться автоматически.") {
                Button(onClick = { adding = true }) { Text("Добавить подписку") }
                OutlinedButton(onClick = app::importFromClipboard) { Text("Из буфера") }
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(380.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.subscriptions, key = { it.id }) { s ->
                SubscriptionCard(s, count = state.nodes.count { it.subscriptionId == s.id }, isUpdating = s.id in updating,
                    modifier = Modifier.animateItem(),
                    onUpdate = { app.updateSubscription(s.id) }, onEdit = { edit = s }, onDelete = { app.deleteSubscription(s) },
                    onCopy = { app.copyToClipboard(s.url, "Ссылка скопирована") })
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
    onUpdate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val inf = rememberInfiniteTransition(label = "sync")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = PolygonShape(MaterialShapes.Clover4Leaf, if (isUpdating) spin else 0f), color = cs.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                    Icon(Icons.Rounded.CloudSync, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("$count серв. · обновлено ${formatAgo(s.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onUpdate, enabled = !isUpdating) {
                    Icon(Icons.Rounded.Sync, "Обновить", Modifier.rotate(if (isUpdating) -spin else 0f))
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                        DropdownMenuItem({ Text("Изменить") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        DropdownMenuItem({ Text("Копировать ссылку") }, { menu = false; onCopy() }, leadingIcon = { Icon(Icons.Rounded.Link, null) })
                        if (s.webPageUrl.isNotBlank()) DropdownMenuItem({ Text("Сайт сервиса") }, { menu = false; WinUtil.openUrl(s.webPageUrl) }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Удалить", color = cs.error) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
                    }
                }
            }
            AnimatedVisibility(isUpdating) { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) }
            if (s.total > 0) {
                Spacer(Modifier.height(14.dp))
                val used = s.upload + s.download
                val frac by animateFloatAsState((used.toFloat() / s.total).coerceIn(0f, 1f), MaterialTheme.motionScheme.slowSpatialSpec(), label = "q")
                LinearWavyProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(),
                    color = if (frac > 0.9f) cs.error else cs.primary)
                Spacer(Modifier.height(6.dp))
                Text("${formatBytes(used)} из ${formatBytes(s.total)}", style = MaterialTheme.typography.bodySmall)
            } else if (s.upload + s.download > 0) {
                Spacer(Modifier.height(10.dp))
                Text("Использовано ${formatBytes(s.upload + s.download)} · безлимит", style = MaterialTheme.typography.bodySmall)
            }
            if (s.expire > 0) {
                val days = (s.expire * 1000 - System.currentTimeMillis()) / 86_400_000
                val date = DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(s.expire))
                AssistChip(onClick = {}, label = { Text(if (days >= 0) "До $date · $days дн." else "Истекла $date") },
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
                Text("Ошибка: ${s.lastError}", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 8.dp))
            }
            if (s.supportUrl.isNotBlank()) {
                TextButton(onClick = { WinUtil.openUrl(s.supportUrl) }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Rounded.SupportAgent, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Поддержка")
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
        title = { Text(if (initial == null) "Новая подписка" else "Изменить подписку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(480.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("URL подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Название (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Если пусто — возьмём из заголовка profile-title") })
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit) }, label = { Text("Автообновление, часов (0 — выкл.)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(name, url.trim(), hours.toIntOrNull() ?: 12) }, enabled = url.startsWith("http")) { Text("Сохранить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
