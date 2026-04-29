package com.xingshu.helper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RedPrimary = Color(0xFFC0392B)
private val RedContainer = Color(0xFFFFEBEA)
private val GoldAccent = Color(0xFFB7860B)
private val SurfaceColor = Color(0xFFFAF9F7)

private val ColorScheme = lightColorScheme(
    primary = RedPrimary,
    onPrimary = Color.White,
    primaryContainer = RedContainer,
    onPrimaryContainer = RedPrimary,
    secondary = GoldAccent,
    surface = SurfaceColor,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF0EDEA),
    outline = Color(0xFFCBC8C0),
    error = Color(0xFFBA1A1A),
)

@Composable
fun XingShuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content
    )
}
