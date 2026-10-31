package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AstraDarkColorScheme = darkColorScheme(
    primary = AstraEmeraldGreen,
    onPrimary = AstraObsidianBg,
    primaryContainer = AstraEmeraldContainer,
    onPrimaryContainer = AstraEmeraldGreen,
    secondary = AstraCyanAccent,
    onSecondary = AstraObsidianBg,
    secondaryContainer = AstraCyanContainer,
    onSecondaryContainer = AstraCyanAccent,
    tertiary = AstraAmberWarn,
    onTertiary = AstraObsidianBg,
    tertiaryContainer = AstraAmberContainer,
    onTertiaryContainer = AstraAmberWarn,
    error = AstraCrimsonDanger,
    onError = AstraObsidianBg,
    errorContainer = AstraCrimsonContainer,
    onErrorContainer = AstraCrimsonDanger,
    background = AstraObsidianBg,
    onBackground = AstraTextPrimary,
    surface = AstraSurfaceElevated,
    onSurface = AstraTextPrimary,
    surfaceVariant = AstraSurfaceCard,
    onSurfaceVariant = AstraTextSecondary,
    outline = AstraSurfaceOutline
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AstraDarkColorScheme,
        typography = Typography,
        content = content
    )
}
