package studio.zavi.uniremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import studio.zavi.uniremote.core.Brand

object Palette {
    val Bg = Color(0xFF0A0B0F)
    val Surface = Color(0xFF13151C)
    val Raised = Color(0xFF1B1E27)
    val RaisedHi = Color(0xFF272B37)
    val Stroke = Color(0xFF2B303D)
    val Text = Color(0xFFECEEF3)
    val Muted = Color(0xFF8B91A1)
    val Accent = Color(0xFF5B8CFF)
    val AccentDim = Color(0xFF233259)
    val Power = Color(0xFFFF5A5F)
    val Good = Color(0xFF3DDC97)
}

/** A color per platform so saved TVs are easy to tell apart (no logos). */
fun brandColor(brand: Brand): Color = when (brand) {
    Brand.ANDROID_TV -> Color(0xFF3DDC97)
    Brand.SAMSUNG -> Color(0xFF5B8CFF)
    Brand.LG -> Color(0xFFFF6FAE)
    Brand.ROKU -> Color(0xFFA27BFF)
    Brand.FIRE_TV -> Color(0xFFFFA24C)
}

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Accent,
        onPrimary = Color.White,
        primaryContainer = Palette.AccentDim,
        onPrimaryContainer = Palette.Text,
        secondary = Palette.Accent,
        onSecondary = Color.White,
        secondaryContainer = Palette.RaisedHi,
        onSecondaryContainer = Palette.Text,
        background = Palette.Bg,
        onBackground = Palette.Text,
        surface = Palette.Surface,
        onSurface = Palette.Text,
        surfaceVariant = Palette.Raised,
        onSurfaceVariant = Palette.Muted,
        surfaceContainerLowest = Palette.Bg,
        surfaceContainerLow = Palette.Surface,
        surfaceContainer = Palette.Surface,
        surfaceContainerHigh = Palette.Raised,
        surfaceContainerHighest = Palette.RaisedHi,
        outline = Palette.Stroke,
        outlineVariant = Palette.Stroke,
        error = Palette.Power,
        onError = Color.White,
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
