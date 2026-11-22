@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.uplinx.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle
import dev.uplinx.core.WinUtil
import dev.uplinx.data.AppSettings
import dev.uplinx.data.ColorSource
import dev.uplinx.data.ThemeMode
import kotlinx.coroutines.delay

/** Seeds people can pick from when not following the Windows accent. */
val SeedPresets = listOf(
    0xFF1F8BFF, 0xFF4B2EFF, 0xFF00BFB3, 0xFF6750A4, 0xFF0B57D0, 0xFF006A6A, 0xFF386A20, 0xFF7D5260,
    0xFFB3261E, 0xFFE8710A, 0xFF8E4EC6, 0xFF00639B, 0xFF4F378B, 0xFF795548, 0xFF5D5F5F,
)

/** Variable fonts (OFL): Unbounded for display/headlines, Rubik for everything else. Both cover Cyrillic. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(resource: String, vararg weights: Int) = androidx.compose.ui.text.font.FontFamily(
    weights.map { w ->
        androidx.compose.ui.text.platform.Font(
            resource = resource,
            weight = androidx.compose.ui.text.font.FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
        )
    }
)

val Rubik = variable("fonts/Rubik.ttf", 300, 400, 500, 600, 700)
val Unbounded = variable("fonts/Unbounded.ttf", 400, 500, 600, 700)

private val AppTypography: androidx.compose.material3.Typography by lazy {
    val base = androidx.compose.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.body() = copy(fontFamily = Rubik)
    fun androidx.compose.ui.text.TextStyle.display(w: Int) = copy(fontFamily = Unbounded, fontWeight = androidx.compose.ui.text.font.FontWeight(w), letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified)
    base.copy(
        displayLarge = base.displayLarge.display(600), displayMedium = base.displayMedium.display(600), displaySmall = base.displaySmall.display(600),
        headlineLarge = base.headlineLarge.display(600), headlineMedium = base.headlineMedium.display(600), headlineSmall = base.headlineSmall.display(500),
        titleLarge = base.titleLarge.body().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        titleMedium = base.titleMedium.body().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        titleSmall = base.titleSmall.body().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.body(), bodyMedium = base.bodyMedium.body(), bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        labelMedium = base.labelMedium.body().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        labelSmall = base.labelSmall.body(),
    )
}

@Composable
fun UplinxTheme(settings: AppSettings, content: @Composable () -> Unit) {
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
    SideEffect { dev.uplinx.I18n.apply(settings.language) }
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
        typography = AppTypography,
        animate = true,
        content = content,
    )
}
