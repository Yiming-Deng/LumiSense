package com.oppovisual.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.oppovisual.app.R

val OppoDisplayFont = FontFamily(
    Font(R.font.smiley_sans, weight = FontWeight.SemiBold),
)

val OppoScoreFont = FontFamily(
    Font(R.font.roboto_condensed, weight = FontWeight.Black),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66D8C6),
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF145047),
    onPrimaryContainer = Color(0xFFB4F4E8),
    secondary = Color(0xFFBAC7C3),
    onSecondary = Color(0xFF25332F),
    tertiary = Color(0xFFC3C5CD),
    background = Color(0xFF0E1214),
    onBackground = Color(0xFFE6EAE8),
    surface = Color(0xFF161B1D),
    onSurface = Color(0xFFE6EAE8),
    surfaceVariant = Color(0xFF23292B),
    onSurfaceVariant = Color(0xFFBEC8C5),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3E4845),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color(0xFFF4FFFC),
    primaryContainer = Color(0xFFB7F0E5),
    onPrimaryContainer = Color(0xFF003730),
    secondary = Color(0xFF52615D),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5D6068),
    background = Color(0xFFF4F6F5),
    onBackground = Color(0xFF171C1B),
    surface = Color(0xFFFBFCFB),
    onSurface = Color(0xFF171C1B),
    surfaceVariant = Color(0xFFE4E9E7),
    onSurfaceVariant = Color(0xFF414A47),
    outline = Color(0xFF717B78),
    outlineVariant = Color(0xFFC3CBC8),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = OppoDisplayFont,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = OppoDisplayFont,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = OppoDisplayFont,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun OppoVisualTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
