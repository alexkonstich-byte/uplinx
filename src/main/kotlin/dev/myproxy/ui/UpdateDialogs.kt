@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.myproxy.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.myproxy.AppController
import dev.myproxy.L
import dev.myproxy.core.UpdateInfo
import dev.myproxy.core.Updater
import dev.myproxy.core.WinUtil

/** Just enough Markdown for release notes: headings, bullets, **bold**, `code`. */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("#") -> Text(inline(line.trimStart('#', ' ')), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    Row(Modifier.padding(start = (indent * 16).dp)) {
                        Box(Modifier.padding(top = 8.dp, end = 10.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, PolygonShape(MaterialShapes.Cookie4Sided)))
                        Text(inline(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun inline(text: String) = buildAnnotatedString {
    val code = MaterialTheme.colorScheme.surfaceContainerHighest
    var i = 0
    val re = Regex("""\*\*(.+?)\*\*|`(.+?)`|\[(.+?)]\((.+?)\)""")
    re.findAll(text).forEach { m ->
        append(text.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = Mono, background = code)) { append(m.groupValues[2]) }
            else -> append(m.groupValues[3])
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

@Composable
private fun SpinningBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val inf = rememberInfiniteTransition(label = "badge")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "r")
    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, PolygonShape(MaterialShapes.SoftBurst, rot)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Full release notes before updating, with download progress. */
@Composable
fun UpdateDialog(app: AppController, u: UpdateInfo, onExit: () -> Unit, onDismiss: () -> Unit) {
    val progress by app.updateProgress.collectAsState()
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        icon = { SpinningBadge(Icons.Rounded.SystemUpdate) },
        title = { Text(L("Доступна версия %1", u.version)) },
        text = {
            Column(Modifier.width(560.dp)) {
                Text(L("Сейчас установлена %1 · обновление из GitHub Releases%2", dev.myproxy.core.BuildInfo.version,
                    if (Updater.isPortable) L(" (портативная версия)") else ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    MarkdownText(u.notes.ifBlank { L("Описание изменений не указано") }, Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
                }
                progress?.let { p ->
                    Spacer(Modifier.height(14.dp))
                    LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(L("Загрузка… %1%", (p * 100).toInt()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { Button({ app.installUpdate(onExit) }, enabled = progress == null) { Text(L("Обновить и перезапустить")) } },
        dismissButton = {
            Row {
                TextButton({ WinUtil.openUrl(u.url) }) { Text("GitHub") }
                TextButton(onDismiss, enabled = progress == null) { Text(L("Позже")) }
            }
        },
    )
}

@Composable
fun WhatsNewDialog(u: UpdateInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SpinningBadge(Icons.Rounded.AutoAwesome) },
        title = { Text(L("Что нового в %1", u.version)) },
        text = {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(560.dp).heightIn(max = 420.dp)) {
                MarkdownText(u.notes, Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
            }
        },
        confirmButton = { Button(onDismiss) { Text(L("Отлично")) } },
    )
}
