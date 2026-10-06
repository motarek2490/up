package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val FridaDarkColorScheme = darkColorScheme(
    primary = FridaGold,
    onPrimary = FridaBlack,
    primaryContainer = FridaGoldContainer,
    onPrimaryContainer = FridaGoldLight,
    secondary = FridaGoldDeep,
    onSecondary = FridaBlack,
    secondaryContainer = FridaCardElevated,
    onSecondaryContainer = FridaGoldLight,
    tertiary = FridaGoldLight,
    onTertiary = FridaBlack,
    background = FridaBlack,
    onBackground = FridaTextPrimary,
    surface = FridaSurface,
    onSurface = FridaTextPrimary,
    surfaceVariant = FridaCard,
    onSurfaceVariant = FridaTextSecondary,
    outline = FridaBorderGold,
    outlineVariant = FridaBorder,
    error = FridaRed,
    onError = FridaTextPrimary,
    errorContainer = FridaRedContainer,
    onErrorContainer = FridaRed
)

@Composable
fun FridaTheme(
    darkTheme: Boolean = true, // Luxury dark theme always for admin panel
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            window?.let {
                it.statusBarColor = FridaBlack.toArgb()
                it.navigationBarColor = FridaBlack.toArgb()
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = FridaDarkColorScheme,
        typography = Typography,
        content = content
    )
}
