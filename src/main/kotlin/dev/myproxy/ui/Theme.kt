@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.myproxy.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle
import dev.myproxy.core.WinUtil
import dev.myproxy.data.AppSettings
import dev.myproxy.data.ColorSource
import dev.myproxy.data.ThemeMode
import kotlinx.coroutines.delay

/** Seeds people can pick from when not following the Windows accent. */
val SeedPresets = listOf(
    0xFF6750A4, 0xFF0B57D0, 0xFF006A6A, 0xFF386A20, 0xFF7D5260,
    0xFFB3261E, 0xFFE8710A, 0xFF8E4EC6, 0xFF00639B, 0xFF4F378B, 0xFF795548, 0xFF5D5F5F,
)

@Composable
fun MyProxyTheme(settings: AppSettings, content: @Composable () -> Unit) {
    // Windows has no "wallpaper colours" API for apps, so the accent colour is our dynamic source.
    // Poll it (cheap registry read) so the app follows Personalization changes live, like Android does.
    var accent by remember { mutableStateOf(WinUtil.accentColor()) }
    var systemDark by remember { mutableStateOf(WinUtil.systemDark()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            accent = WinUtil.accentColor()
            systemDark = WinUtil.systemDark()
        }
    }
    // language follows the setting live; reading I18n.lang in L() recomposes every text
    SideEffect { dev.myproxy.I18n.apply(settings.language) }
    val seed = when (settings.colorSource) {
        ColorSource.WINDOWS_ACCENT -> accent ?: settings.seedColor
        ColorSource.CUSTOM -> settings.seedColor
    }
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val style = PaletteStyle.entries.firstOrNull { it.name == settings.paletteStyle } ?: PaletteStyle.TonalSpot
    DynamicMaterialExpressiveTheme(
        seedColor = Color(seed),
        motionScheme = MotionScheme.expressive(),
        isDark = dark,
        isAmoled = settings.amoled && dark,
        style = style,
        animate = true,
        content = content,
    )
}
