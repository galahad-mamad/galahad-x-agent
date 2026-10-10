package com.agent.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Galahad palette - dark, professional, purple gradient accent */
object AppColors {
    val bg = Color(0xFF0A0D14)
    val bgBottom = Color(0xFF0E1220)
    val surface = Color(0xFF141A28)
    val surfaceHigh = Color(0xFF1B2334)
    val border = Color(0xFF263047)
    val accent1 = Color(0xFF6C5CE7)   // indigo-violet
    val accent2 = Color(0xFFA66CFF)   // light violet
    val teal = Color(0xFF2DD4BF)
    val textPrimary = Color(0xFFE9EDF6)
    val textSecondary = Color(0xFF8B94A9)
    val textFaint = Color(0xFF5A6479)
    val danger = Color(0xFFF0596B)
    val success = Color(0xFF34D399)
    val warn = Color(0xFFF5B14C)
    val userBubble = Brush.linearGradient(listOf(Color(0xFF6C5CE7), Color(0xFF9B6CFF)))
    val toolBg = Color(0xFF101625)
    val glow = Brush.radialGradient(
        listOf(Color(0x556C5CE7), Color(0x006C5CE7), Color(0x00000000))
    )
}

private val AppColorScheme = darkColorScheme(
    primary = AppColors.accent1,
    onPrimary = Color.White,
    primaryContainer = AppColors.surfaceHigh,
    onPrimaryContainer = AppColors.textPrimary,
    secondary = AppColors.teal,
    onSecondary = Color.Black,
    secondaryContainer = AppColors.surfaceHigh,
    onSecondaryContainer = AppColors.textPrimary,
    tertiary = AppColors.accent2,
    tertiaryContainer = AppColors.toolBg,
    onTertiaryContainer = AppColors.textPrimary,
    background = AppColors.bg,
    onBackground = AppColors.textPrimary,
    surface = AppColors.surface,
    onSurface = AppColors.textPrimary,
    surfaceVariant = AppColors.surfaceHigh,
    onSurfaceVariant = AppColors.textSecondary,
    surfaceTint = AppColors.accent1,
    error = AppColors.danger,
    onError = Color.White,
    errorContainer = Color(0xFF3A1B22),
    onErrorContainer = Color(0xFFFFB4BC),
    outline = AppColors.border
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp)
)

@Composable
fun AndroidAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content
    )
}
