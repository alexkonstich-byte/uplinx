package dev.uplinx.ui

import dev.uplinx.L

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.uplinx.AppController
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun LogsScreen(app: AppController) {
    val logs by app.core.logs.collectAsState()
    var level by remember { mutableStateOf('I') }
    var follow by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val order = "DIWE"
    val shown = remember(logs, level, filter) {
        logs.filter { order.indexOf(it.level) >= order.indexOf(level) && (filter.isBlank() || it.text.contains(filter, ignoreCase = true)) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) list.animateScrollToItem(shown.lastIndex) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss") }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("Логи"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            listOf('I' to L("Все"), 'W' to L("Предупр."), 'E' to L("Ошибки")).forEach { (l, t) ->
                FilterChip(level == l, { level = l }, label = { Text(t) }, modifier = Modifier.padding(end = 8.dp))
            }
            OutlinedTextField(filter, { filter = it }, placeholder = { Text(L("Фильтр")) }, singleLine = true, modifier = Modifier.width(200.dp),
                leadingIcon = { Icon(Icons.Rounded.FilterList, null) }, shape = RoundedCornerShape(50))
            TooltipIcon(if (follow) L("Автопрокрутка вкл.") else L("Автопрокрутка выкл."), if (follow) Icons.Rounded.VerticalAlignBottom else Icons.Rounded.PauseCircle) { follow = !follow }
            TooltipIcon(L("Копировать всё"), Icons.Rounded.ContentCopy) { app.copyToClipboard(shown.joinToString("\n") { "${fmt.format(Date(it.time))} ${it.text}" }, L("Логи скопированы")) }
            TooltipIcon(L("Очистить"), Icons.Rounded.ClearAll) { app.core.clearLogs() }
        }
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
            if (shown.isEmpty()) Box(contentAlignment = Alignment.Center) { Text(L("Пусто"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else SelectionContainer {
                LazyColumn(state = list, contentPadding = PaddingValues(16.dp)) {
                    items(shown) { l ->
                        val color = when (l.level) {
                            'E' -> MaterialTheme.colorScheme.error; 'W' -> MaterialTheme.colorScheme.tertiary
                            'D' -> MaterialTheme.colorScheme.outline; else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row {
                            Text(fmt.format(Date(l.time)) + "  ", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.outline)
                            Text(l.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = color)
                        }
                    }
                }
            }
        }
    }
}
