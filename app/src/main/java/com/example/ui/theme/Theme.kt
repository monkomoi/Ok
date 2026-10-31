package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AstraDarkColorScheme = darkColorScheme(
    primary = AstraCyanAccent,
    onPrimary = Color(0xFF04131A),
    primaryContainer = Color(0xFF0D2D3A),
    onPrimaryContainer = AstraCyanAccent,
    secondary = AstraEmeraldGreen,
    onSecondary = Color(0xFF031B12),
    secondaryContainer = Color(0xFF0D2E24),
    onSecondaryContainer = AstraEmeraldGreen,
    tertiary = AstraAmberWarn,
    onTertiary = Color(0xFF1F1202),
    tertiaryContainer = Color(0xFF332208),
    onTertiaryContainer = AstraAmberWarn,
    error = AstraCrimsonDanger,
    onError = Color.White,
    errorContainer = Color(0xFF3A1216),
    onErrorContainer = Color(0xFFFECACA),
    background = AstraMidnightBg,
    onBackground = AstraTextPrimary,
    surface = AstraMidnightBg,
    onSurface = AstraTextPrimary,
    surfaceVariant = AstraSurfaceElevated,
    onSurfaceVariant = AstraTextSecondary,
    outline = AstraSurfaceOutline
)

@Composable
fun AstraTraderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AstraDarkColorScheme,
        typography = AstraTypography,
        content = content
    )
}
