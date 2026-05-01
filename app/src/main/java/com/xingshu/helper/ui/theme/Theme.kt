package com.xingshu.helper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val QingTianBlue = Color(0xFF001A57)
private val QingTianBluePressed = Color(0xFF000F3D)
private val QingTianBlueContainer = Color(0xFFE8EDFF)
private val FullGroundRed = Color(0xFFB01220)
private val FullGroundRedContainer = Color(0xFFFFE7E6)
private val WhiteSun = Color(0xFFFFFBF2)
private val Paper = Color(0xFFFFFCF6)
private val PaperVariant = Color(0xFFF3ECDE)
private val SealGold = Color(0xFF7C5700)
private val SealGoldContainer = Color(0xFFFFE9B8)
private val Ink = Color(0xFF1E1B16)
private val MutedInk = Color(0xFF5D5548)
private val DividerInk = Color(0xFFD5CAB9)
private val ErrorRed = Color(0xFFBA1A1A)
private val ErrorRedContainer = Color(0xFFFFDAD6)

private val RepublicColorScheme = lightColorScheme(
    primary = QingTianBlue,
    onPrimary = WhiteSun,
    primaryContainer = QingTianBlueContainer,
    onPrimaryContainer = QingTianBluePressed,
    inversePrimary = Color(0xFFB8C7FF),
    secondary = FullGroundRed,
    onSecondary = WhiteSun,
    secondaryContainer = FullGroundRedContainer,
    onSecondaryContainer = Color(0xFF410005),
    tertiary = SealGold,
    onTertiary = WhiteSun,
    tertiaryContainer = SealGoldContainer,
    onTertiaryContainer = Color(0xFF3D2B00),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = MutedInk,
    surfaceTint = QingTianBlue,
    inverseSurface = Color(0xFF332F29),
    inverseOnSurface = Color(0xFFF8EFE3),
    outline = Color(0xFF807565),
    outlineVariant = DividerInk,
    error = ErrorRed,
    onError = WhiteSun,
    errorContainer = ErrorRedContainer,
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

private val RepublicTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.1.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
)

private val RepublicShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun XingShuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RepublicColorScheme,
        typography = RepublicTypography,
        shapes = RepublicShapes,
        content = content,
    )
}
