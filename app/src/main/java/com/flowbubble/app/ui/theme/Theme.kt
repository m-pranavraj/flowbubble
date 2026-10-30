package com.flowbubble.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FlowColorScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF04201B),
    primaryContainer = Color(0xFF123B34),
    onPrimaryContainer = Color(0xFFBDF6EB),
    secondary = Color(0xFF8AC7D8),
    onSecondary = Color(0xFF04202A),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE8EFF5),
    surface = Color(0xFF101720),
    onSurface = Color(0xFFE8EFF5),
    surfaceVariant = Color(0xFF1A242E),
    onSurfaceVariant = Color(0xFFA9B7C4),
    outline = Color(0xFF2C3A47),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF3A0B0B)
)

@Composable
fun FlowBubbleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlowColorScheme,
        typography = Typography(),
        content = content
    )
}
