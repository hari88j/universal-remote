package studio.zavi.uniremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import studio.zavi.uniremote.core.Brand

/** Apple-style semantic colours, one set for light and one for dark. */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val background: Color,       // systemGroupedBackground
    val card: Color,             // secondarySystemGroupedBackground
    val cardHigh: Color,         // tertiary
    val separator: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val accent: Color,
    val red: Color,
    val green: Color,
    // 3D key surfaces
    val keyTop: Color,
    val keyBottom: Color,
    val keyPressedTop: Color,
    val keyPressedBottom: Color,
    val keyHighlight: Color,
    val keyShadow: Color,
    val padOuterTop: Color,
    val padOuterBottom: Color,
)

val DarkColors = AppColors(
    isDark = true,
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardHigh = Color(0xFF2C2C2E),
    separator = Color(0xFF38383A),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF8E8E93),
    tertiaryLabel = Color(0xFF5A5A5F),
    accent = Color(0xFF0A84FF),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    keyTop = Color(0xFF3A3A3D),
    keyBottom = Color(0xFF1E1E21),
    keyPressedTop = Color(0xFF19191B),
    keyPressedBottom = Color(0xFF2A2A2D),
    keyHighlight = Color(0x40FFFFFF),
    keyShadow = Color(0xFF000000),
    padOuterTop = Color(0xFF2E2E31),
    padOuterBottom = Color(0xFF141416),
)

val LightColors = AppColors(
    isDark = false,
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    cardHigh = Color(0xFFF7F7FA),
    separator = Color(0xFFD1D1D6),
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF6C6C70),
    tertiaryLabel = Color(0xFFAEAEB2),
    accent = Color(0xFF007AFF),
    red = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    keyTop = Color(0xFFFFFFFF),
    keyBottom = Color(0xFFE6E6EB),
    keyPressedTop = Color(0xFFDCDCE2),
    keyPressedBottom = Color(0xFFF0F0F4),
    keyHighlight = Color(0xCCFFFFFF),
    keyShadow = Color(0xFF8A8A99),
    padOuterTop = Color(0xFFFFFFFF),
    padOuterBottom = Color(0xFFDDDDE3),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

/** Whether buttons vibrate (a setting). */
val LocalHaptics = staticCompositionLocalOf { true }

/** Shortcut: `C.label`, `C.accent`… inside composables. */
val C: AppColors
    @Composable get() = LocalAppColors.current

/** A colour per platform so saved TVs are easy to tell apart (no logos). */
fun brandColor(brand: Brand): Color = when (brand) {
    Brand.ANDROID_TV -> Color(0xFF30D158)
    Brand.SAMSUNG -> Color(0xFF0A84FF)
    Brand.LG -> Color(0xFFFF375F)
    Brand.ROKU -> Color(0xFFBF5AF2)
    Brand.FIRE_TV -> Color(0xFFFF9F0A)
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

private val AppleType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, letterSpacing = (-0.2).sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun RemoteTheme(mode: ThemeMode = ThemeMode.SYSTEM, haptics: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color.White,
            background = c.background, onBackground = c.label,
            surface = c.card, onSurface = c.label,
            surfaceVariant = c.cardHigh, onSurfaceVariant = c.secondaryLabel,
            surfaceContainerLowest = c.background, surfaceContainerLow = c.card,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.cardHigh,
            outline = c.separator, outlineVariant = c.separator,
            error = c.red, onError = Color.White,
            secondaryContainer = c.cardHigh, onSecondaryContainer = c.label,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White,
            background = c.background, onBackground = c.label,
            surface = c.card, onSurface = c.label,
            surfaceVariant = c.cardHigh, onSurfaceVariant = c.secondaryLabel,
            surfaceContainerLowest = c.card, surfaceContainerLow = c.card,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.cardHigh,
            outline = c.separator, outlineVariant = c.separator,
            error = c.red, onError = Color.White,
            secondaryContainer = c.cardHigh, onSecondaryContainer = c.label,
        )
    }
    CompositionLocalProvider(LocalAppColors provides c, LocalHaptics provides haptics) {
        MaterialTheme(colorScheme = scheme, typography = AppleType, content = content)
    }
}
