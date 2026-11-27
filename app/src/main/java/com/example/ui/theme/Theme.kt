package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val FileManagerDarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = ElectricBlueContainer,
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = CyanAccent,
    onSecondary = ObsidianDeep,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = Color(0xFFCCF5FF),
    tertiary = VioletGlow,
    onTertiary = Color.White,
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = Color(0xFFF0DBFF),
    background = CharcoalBackground,
    onBackground = TextPrimary,
    surface = CharcoalSurface,
    onSurface = TextPrimary,
    surfaceVariant = CharcoalSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceDim = SurfaceDim,
    surfaceBright = SurfaceBright,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainerDefault,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = ElectricBlueVariant,
    outline = CharcoalSurfaceHighlight,
    outlineVariant = DocumentsUIDivider,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    scrim = Color.Black
)

@Immutable
data class TechnicalColors(
    val cyanGlow: Color,
    val goldAccent: Color,
    val violetGlow: Color,
    val success: Color,
    val warning: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val mutedText: Color
)

private val DarkTechnicalColors = TechnicalColors(
    cyanGlow = CyanGlow,
    goldAccent = GoldAccent,
    violetGlow = VioletGlow,
    success = ColorSuccessGreen,
    warning = ColorWarningAmber,
    surfaceElevated = CharcoalSurfaceVariant,
    borderSubtle = CharcoalSurfaceHighlight,
    mutedText = TextMuted
)

val LocalTechnicalColors = staticCompositionLocalOf { DarkTechnicalColors }

object FileManagerThemeTokens {
    val colors: TechnicalColors
        @Composable get() = LocalTechnicalColors.current
}

@Composable
fun FileManagerProTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    CompositionLocalProvider(LocalTechnicalColors provides DarkTechnicalColors) {
        MaterialTheme(
            colorScheme = FileManagerDarkColorScheme,
            typography = Typography,
            shapes = FileManagerShapes,
            content = content
        )
    }
}
