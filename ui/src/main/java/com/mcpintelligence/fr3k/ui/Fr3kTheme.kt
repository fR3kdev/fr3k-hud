package com.mcpintelligence.fr3k.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

object Fr3kPalette {
    val Bg = Color(0xFF080B18)
    val Surface = Color(0xFF151B30)
    val Border = Color(0xFF343E60)
    val Text = Color(0xFFE6ECF5)
    val TextDim = Color(0xFFB0BCD5)
    val Accent = Color(0xFFC5A0FF)
    val AccentDim = Color(0xFF9CBAFF)
    val Ok = Color(0xFF28E0B0)
    val Warn = Color(0xFFFFAA33)
    val Err = Color(0xFFFF5577)
    val Magenta = Color(0xFFFF3FA4)
}

private val Fr3kDarkColors = darkColorScheme(
    primary = Fr3kPalette.Accent,
    onPrimary = Color.Black,
    secondary = Fr3kPalette.Ok,
    onSecondary = Color.Black,
    background = Fr3kPalette.Bg,
    onBackground = Fr3kPalette.Text,
    surface = Fr3kPalette.Surface,
    onSurface = Fr3kPalette.Text,
    surfaceVariant = Fr3kPalette.Surface,
    onSurfaceVariant = Fr3kPalette.TextDim,
    outline = Fr3kPalette.Border,
    outlineVariant = Fr3kPalette.Border,
    error = Fr3kPalette.Err,
    onError = Color.Black,
)

private val Fr3kLightColors = lightColorScheme(
    primary = Fr3kPalette.AccentDim,
    secondary = Fr3kPalette.Ok,
    background = Fr3kPalette.Bg,
    surface = Fr3kPalette.Surface,
)

private val Fr3kTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
)

@Composable
fun Fr3kTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) Fr3kDarkColors else Fr3kDarkColors  // dark is the brand
    MaterialTheme(colorScheme = colors, typography = Fr3kTypography, content = content)
}