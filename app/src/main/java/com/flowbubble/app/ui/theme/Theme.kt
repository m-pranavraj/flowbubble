package com.flowbubble.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The whole product reads from this one palette. Overlay drawing code that cannot use
 * MaterialTheme (Canvas on a raw View) reads the same values from [Ink] so the bubble, the panel
 * and the app never drift apart into three different black-and-neon schemes.
 */
object Ink {
    val Void = Color(0xFF050308)
    val Obsidian = Color(0xFF0A0710)
    val Onyx = Color(0xFF120C1C)
    val Smoke = Color(0xFF1B1428)
    val Veil = Color(0xFF2A2140)

    val Lavender = Color(0xFFC9B8FF)
    val LavenderDeep = Color(0xFF8B5CF6)
    val Burgundy = Color(0xFF7A1F3D)
    val BurgundyDeep = Color(0xFF450F24)
    val Crimson = Color(0xFFFF2D55)
    val CrimsonSoft = Color(0xFFFF7A96)
    val Jade = Color(0xFF22D8A2)
    val Amber = Color(0xFFF5A524)

    val Mist = Color(0xFFF2EEFB)
    val Faint = Color(0xFFA79DC4)
    val Whisper = Color(0xFF6E6688)
}

private val BubbleColorScheme = darkColorScheme(
    primary = Ink.Lavender,
    onPrimary = Ink.Void,
    primaryContainer = Ink.BurgundyDeep,
    onPrimaryContainer = Ink.Lavender,
    secondary = Ink.CrimsonSoft,
    onSecondary = Ink.Void,
    secondaryContainer = Ink.Burgundy,
    onSecondaryContainer = Color(0xFFFFE4EC),
    tertiary = Ink.Jade,
    onTertiary = Ink.Void,
    background = Ink.Void,
    onBackground = Ink.Mist,
    surface = Ink.Obsidian,
    onSurface = Ink.Mist,
    surfaceVariant = Ink.Onyx,
    onSurfaceVariant = Ink.Faint,
    outline = Ink.Veil,
    outlineVariant = Ink.Smoke,
    error = Ink.Crimson,
    onError = Color(0xFFFFE4EC),
    errorContainer = Ink.BurgundyDeep,
    onErrorContainer = Ink.CrimsonSoft
)

/** Display sizes are set here rather than inherited so headings keep the cinematic tracking. */
private val BubbleTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
)

object BubbleShapes {
    val Card = RoundedCornerShape(26.dp)
    val Button = RoundedCornerShape(16.dp)
    val Field = RoundedCornerShape(14.dp)
    val Chip = RoundedCornerShape(50)
    val Sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 26.dp, bottomEnd = 26.dp)
}

@Composable
fun FlowBubbleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BubbleColorScheme,
        typography = BubbleTypography,
        shapes = androidx.compose.material3.Shapes(
            small = BubbleShapes.Chip,
            medium = BubbleShapes.Field,
            large = BubbleShapes.Button,
            extraLarge = BubbleShapes.Card
        ),
        content = content
    )
}
