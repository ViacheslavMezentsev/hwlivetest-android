package io.github.viacheslavmezentsev.hwlivetest.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Дизайн-система HW Live Test: цвета, шрифты и статусные цвета (docs/roadmap.md, этап 2).
 * Светлая и тёмная тема следуют системной настройке.
 */
object HwColors {
    val Brand = Color(0xFF1F6FEB)
    val BrandDark = Color(0xFF0B3D91)
    val BrandLight = Color(0xFF79B8FF)
}

/** Цвета результатов: ОК, предупреждение, ошибка, сведения, пропуск. */
@Immutable
data class StatusColors(
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val info: Color,
    val muted: Color,
)

private val LightStatus = StatusColors(
    ok = Color(0xFF1A7F37),
    warn = Color(0xFFBF8700),
    bad = Color(0xFFCF222E),
    info = Color(0xFF1F6FEB),
    muted = Color(0xFF57606A),
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF3FB950),
    warn = Color(0xFFD29922),
    bad = Color(0xFFF85149),
    info = Color(0xFF58A6FF),
    muted = Color(0xFF8B949E),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val LightScheme = lightColorScheme(
    primary = HwColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBFF),
    onPrimaryContainer = HwColors.BrandDark,
    secondary = Color(0xFF57606A),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF2F5),
    onSurfaceVariant = Color(0xFF57606A),
    error = Color(0xFFCF222E),
)

private val DarkScheme = darkColorScheme(
    primary = HwColors.BrandLight,
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF0B3D91),
    onPrimaryContainer = Color(0xFFDDEBFF),
    secondary = Color(0xFF8B949E),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFF85149),
)

/** Моноширинный текст для значений, адресов и журналов. */
val MonoText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun HwTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = Typography(),
            content = content,
        )
    }
}
