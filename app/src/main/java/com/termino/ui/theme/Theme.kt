package com.termino.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = TerracottaSoft,
    onPrimaryContainer = Color(0xFF3B1E12),
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = SageSoft,
    onSecondaryContainer = Color(0xFF1D3027),
    tertiary = Color(0xFF8A6A1F),
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = PaperBright,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE5D8),
    onSurfaceVariant = InkSoft,
    outline = Color(0xFF8C8172),
    outlineVariant = Line,
    error = Color(0xFFB3261E),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = NightTerracotta,
    onPrimary = Color(0xFF3B1E12),
    primaryContainer = Color(0xFF5A3324),
    onPrimaryContainer = Color(0xFFFFDCCB),
    secondary = Color(0xFFA8C6B5),
    onSecondary = Color(0xFF173329),
    secondaryContainer = Color(0xFF2C4A3D),
    onSecondaryContainer = Color(0xFFDCE7DF),
    tertiary = Color(0xFFE8C56B),
    onTertiary = Color(0xFF3D2F00),
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightInkSoft,
    outline = Color(0xFF8F8578),
    outlineVariant = Color(0xFF3A3530),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

@Composable
fun LectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = LectorTypography,
        content = content
    )
}
