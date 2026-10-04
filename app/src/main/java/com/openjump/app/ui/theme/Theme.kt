package com.openjump.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceVariant = DarkSurfaceVariant,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

/**
 * Product-specific semantic colors that do not map cleanly to Material roles.
 * Never use color as the only carrier of information.
 */
data class OpenJumpColors(
    val positive: Color,
    val positiveContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val dataBlue: Color,
    val dataBlueContainer: Color,
    val navigationSelectedContainer: Color,
    val navigationSelectedContent: Color,
    val progressCompletedContainer: Color,
    val progressCompletedContent: Color,
    val onboardingIllustrationSurface: Color,
    val isDark: Boolean,
)

private val LightOpenJumpColors = OpenJumpColors(
    positive = LightPositive,
    positiveContainer = LightPositiveContainer,
    warning = LightWarning,
    warningContainer = LightWarningContainer,
    dataBlue = LightDataBlue,
    dataBlueContainer = LightDataBlueContainer,
    navigationSelectedContainer = LightNavigationSelectedContainer,
    navigationSelectedContent = LightNavigationSelectedContent,
    progressCompletedContainer = LightProgressCompletedContainer,
    progressCompletedContent = LightProgressCompletedContent,
    onboardingIllustrationSurface = LightOnboardingIllustrationSurface,
    isDark = false,
)

private val DarkOpenJumpColors = OpenJumpColors(
    positive = DarkPositive,
    positiveContainer = DarkPositiveContainer,
    warning = DarkWarning,
    warningContainer = DarkWarningContainer,
    dataBlue = DarkDataBlue,
    dataBlueContainer = DarkDataBlueContainer,
    navigationSelectedContainer = DarkNavigationSelectedContainer,
    navigationSelectedContent = DarkNavigationSelectedContent,
    progressCompletedContainer = DarkProgressCompletedContainer,
    progressCompletedContent = DarkProgressCompletedContent,
    onboardingIllustrationSurface = DarkOnboardingIllustrationSurface,
    isDark = true,
)

private val LocalOpenJumpColors = staticCompositionLocalOf { LightOpenJumpColors }

/** Access point for OpenJump product colors: `OpenJumpTheme.colors.positive`. */
object OpenJumpTheme {
    val colors: OpenJumpColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenJumpColors.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = colors.isDark
}

@Composable
fun OpenJumpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val openJumpColors = if (darkTheme) DarkOpenJumpColors else LightOpenJumpColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalOpenJumpColors provides openJumpColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = OpenJumpTypography,
            shapes = OpenJumpShapes,
            content = content,
        )
    }
}