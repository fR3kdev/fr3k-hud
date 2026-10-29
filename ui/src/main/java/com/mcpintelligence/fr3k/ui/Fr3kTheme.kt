package com.mcpintelligence.fr3k.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

object Fr3kPalette {
    val Bg = Color(0xFF05060A)
    val Surface = Color(0xFF0E1018)
    val Border = Color(0xFF1F2A3D)
    val Text = Color(0xFFE6ECF5)
    val TextDim = Color(0xFF8A93A6)
    val Accent = Color(0xFFB829FF)
    val AccentDim = Color(0xFF5A1F8C)
    val Ok = Color(0xFF28E0B0)
    val Warn = Color(0xFFFFAA33)
    val Err = Color(0xFFFF5577)
    val Magenta = Color(0xFFFF3FA4)

    // Derived elevation tiers (additive — never replace the brand colors).
    val SurfaceLow = Color(0xFF0A0C14)
    val SurfaceHigh = Color(0xFF161B2B)
    val OkDim = Color(0xFF0B3B31)
    val ErrDim = Color(0xFF431522)
    val WarnDim = Color(0xFF43300F)

    // Derived light/tint tiers for gradient endpoints and sheens.
    val AccentLift = Color(0xFFD96BFF) // lightened Accent, gradient tips
    val OkLift = Color(0xFF7DF2D2) // lightened Ok, gradient tips
    val Sheen = Color(0x14FFFFFF) // top-edge highlight on raised surfaces
}

/**
 * Design tokens — the single source of truth for radii, spacing and elevation
 * so every panel/row/button in the app shares one rhythm. Use these instead
 * of hardcoded dp values in screens.
 */
object Fr3kDimens {
    val RadiusPanel = 16.dp
    val RadiusRow = 12.dp
    val RadiusChip = 8.dp
    val RadiusHero = 20.dp

    val SpacingXs = 4.dp
    val SpacingSm = 8.dp
    val SpacingMd = 12.dp
    val SpacingLg = 16.dp
    val SpacingXl = 24.dp
    val Spacing2xl = 32.dp

    val PanelPadding = 16.dp
    val PanelBorder = 1.dp
    val PanelElevation = 10.dp
    val ElevLow = 3.dp
    val ElevMid = 7.dp
    val ElevHigh = 14.dp
}

/** Motion language — one family of curves for the whole app. */
object Fr3kMotion {
    const val Fast = 150
    const val Standard = 240
    const val Slow = 380
    const val Stagger = 46
}

/** Shared gradient brushes — the HUD's "glow" identity. */
object Fr3kGradients {
    val PanelFill: Brush = Brush.linearGradient(listOf(Fr3kPalette.Surface, Fr3kPalette.SurfaceLow))
    val AccentFill: Brush = Brush.horizontalGradient(listOf(Fr3kPalette.Accent, Fr3kPalette.Magenta))
    val HeroTitle: Brush = Brush.linearGradient(listOf(Fr3kPalette.Accent, Fr3kPalette.Ok))
    val Divider: Brush = Brush.horizontalGradient(
        listOf(Fr3kPalette.Accent.copy(alpha = 0.55f), Fr3kPalette.Ok.copy(alpha = 0.30f), Fr3kPalette.Border.copy(alpha = 0.15f)),
    )
    val Sheen: Brush = Brush.horizontalGradient(
        listOf(
            Color.Transparent,
            Color.White.copy(alpha = 0.10f),
            Fr3kPalette.AccentLift.copy(alpha = 0.07f),
            Color.Transparent,
        ),
    )
    val OkSweep: Brush = Brush.horizontalGradient(listOf(Fr3kPalette.OkDim, Fr3kPalette.Ok, Fr3kPalette.OkLift))
    val ScreenAmbient: Brush = Brush.verticalGradient(
        0.0f to Color(0xFF0A0C16),
        0.45f to Fr3kPalette.Bg,
        1.0f to Color(0xFF030408.toInt()),
    )

    fun heroGlow(accent: Color = Fr3kPalette.Accent): Brush = Brush.radialGradient(
        listOf(accent.copy(alpha = 0.16f), Fr3kPalette.AccentDim.copy(alpha = 0.05f), Color.Transparent),
    )

    fun panelBorder(accent: Color): Brush = Brush.linearGradient(
        listOf(
            accent.copy(alpha = 0.60f),
            Fr3kPalette.Ok.copy(alpha = 0.28f),
            Fr3kPalette.Border.copy(alpha = 0.90f),
        ),
    )

    fun titleBrush(colors: List<Color>): Brush = Brush.linearGradient(colors)
}

private val Fr3kDarkColors = darkColorScheme(
    primary = Fr3kPalette.Accent,
    onPrimary = Fr3kPalette.Bg,
    primaryContainer = Fr3kPalette.AccentDim,
    onPrimaryContainer = Fr3kPalette.Text,
    secondary = Fr3kPalette.Ok,
    onSecondary = Fr3kPalette.Bg,
    secondaryContainer = Fr3kPalette.OkDim,
    onSecondaryContainer = Fr3kPalette.Text,
    tertiary = Fr3kPalette.Magenta,
    onTertiary = Fr3kPalette.Bg,
    background = Fr3kPalette.Bg,
    onBackground = Fr3kPalette.Text,
    surface = Fr3kPalette.Surface,
    onSurface = Fr3kPalette.Text,
    surfaceVariant = Fr3kPalette.SurfaceHigh,
    onSurfaceVariant = Fr3kPalette.TextDim,
    surfaceContainerLowest = Fr3kPalette.Bg,
    surfaceContainerLow = Fr3kPalette.SurfaceLow,
    surfaceContainer = Fr3kPalette.Surface,
    surfaceContainerHigh = Fr3kPalette.SurfaceHigh,
    surfaceContainerHighest = Fr3kPalette.SurfaceHigh,
    surfaceTint = Fr3kPalette.Accent,
    outline = Fr3kPalette.Border,
    outlineVariant = Fr3kPalette.Border.copy(alpha = 0.5f),
    inverseSurface = Fr3kPalette.SurfaceHigh,
    inverseOnSurface = Fr3kPalette.Text,
    error = Fr3kPalette.Err,
    onError = Fr3kPalette.Bg,
    errorContainer = Fr3kPalette.ErrDim,
    onErrorContainer = Fr3kPalette.Text,
)

private val Fr3kLightColors = lightColorScheme(
    primary = Fr3kPalette.AccentDim,
    secondary = Fr3kPalette.Ok,
    background = Fr3kPalette.Bg,
    surface = Fr3kPalette.Surface,
)

/**
 * Monospace type scale — the HUD identity. Sizes step on a consistent ladder:
 * hero 26 → section 11 bold tracked → body 13/12 → meta 10.
 */
private val Fr3kTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 2.4.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 1.4.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = 1.2.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.8.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.4.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.3.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.8.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp),
)

@Composable
fun Fr3kTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) Fr3kDarkColors else Fr3kDarkColors  // dark is the brand
    MaterialTheme(colorScheme = colors, typography = Fr3kTypography, content = content)
}
