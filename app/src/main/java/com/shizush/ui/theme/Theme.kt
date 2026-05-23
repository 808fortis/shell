package com.shizush.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = TerminalBlue,
    onPrimary = TerminalBackground,
    primaryContainer = TerminalBlue.copy(alpha = 0.2f),
    onPrimaryContainer = TerminalBlue,
    secondary = TerminalGreen,
    onSecondary = TerminalBackground,
    secondaryContainer = TerminalGreen.copy(alpha = 0.2f),
    onSecondaryContainer = TerminalGreen,
    tertiary = TerminalMagenta,
    onTertiary = TerminalBackground,
    background = TerminalBackground,
    onBackground = TerminalForeground,
    surface = TerminalBackground,
    onSurface = TerminalForeground,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TerminalWhite,
    outline = TerminalBrightBlack,
    error = TerminalRed,
    onError = TerminalBackground,
)

@Composable
fun ShellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> DarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ShellTypography,
        content = content
    )
}
