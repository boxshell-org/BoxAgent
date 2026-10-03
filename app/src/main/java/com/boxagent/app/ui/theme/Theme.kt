package com.boxagent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple-minimal monochrome: light is white/parchment with near-black ink;
 * dark is the exact inversion. The single "accent" is pure ink — no second
 * color anywhere (per DESIGN.md's one-accent rule, rendered in B/W).
 */
object BwColors {
    val Canvas = Color(0xFFFFFFFF)
    val Parchment = Color(0xFFF5F5F7)
    val Pearl = Color(0xFFFAFAFC)
    val Hairline = Color(0xFFE0E0E0)
    val Ink = Color(0xFF1D1D1F)
    val Ink80 = Color(0xFF333333)
    val Ink48 = Color(0xFF7A7A7A)
    val Tile1 = Color(0xFF272729)
    val Tile2 = Color(0xFF2A2A2C)
    val Tile3 = Color(0xFF252527)
    val Black = Color(0xFF000000)
    val OnDark = Color(0xFFFFFFFF)
    val OnDarkMuted = Color(0xFFCCCCCC)
}

private val LightColors = lightColorScheme(
    primary = BwColors.Ink,
    onPrimary = BwColors.Canvas,
    secondary = BwColors.Ink80,
    onSecondary = BwColors.Canvas,
    tertiary = BwColors.Ink48,
    background = BwColors.Canvas,
    onBackground = BwColors.Ink,
    surface = BwColors.Canvas,
    onSurface = BwColors.Ink,
    surfaceVariant = BwColors.Parchment,
    onSurfaceVariant = BwColors.Ink80,
    outline = BwColors.Hairline,
    outlineVariant = BwColors.Hairline,
    surfaceContainer = BwColors.Parchment,
    surfaceContainerLow = BwColors.Pearl,
    inverseSurface = BwColors.Ink,
    inverseOnSurface = BwColors.Canvas,
)

private val DarkColors = darkColorScheme(
    primary = BwColors.Canvas,
    onPrimary = BwColors.Ink,
    secondary = BwColors.OnDarkMuted,
    onSecondary = BwColors.Black,
    tertiary = BwColors.OnDarkMuted,
    background = BwColors.Black,
    onBackground = BwColors.OnDark,
    surface = BwColors.Black,
    onSurface = BwColors.OnDark,
    surfaceVariant = BwColors.Tile1,
    onSurfaceVariant = BwColors.OnDarkMuted,
    outline = BwColors.Tile1,
    outlineVariant = BwColors.Tile1,
    surfaceContainer = BwColors.Tile1,
    surfaceContainerLow = BwColors.Tile3,
    inverseSurface = BwColors.Canvas,
    inverseOnSurface = BwColors.Ink,
)

val BwTypography = Typography(
    // Display — tight tracking, weight 600
    displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.25).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = (-0.37).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = (-0.2).sp),
    // Body — 17sp default read pace
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 25.sp, letterSpacing = (-0.37).sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = (-0.22).sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.22).sp),
    // Labels/captions
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = (-0.22).sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = (-0.12).sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = (-0.08).sp),
)

object BwShape {
    val Card = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    val Utility = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val Capsule = androidx.compose.foundation.shape.RoundedCornerShape(11.dp)
    val Pill = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
}

@Composable
fun BoxAgentTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = BwTypography,
        content = content,
    )
}
