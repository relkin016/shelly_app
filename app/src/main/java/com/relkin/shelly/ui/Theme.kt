package com.relkin.shelly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.relkin.shelly.data.Prefs

// Запасная палитра (если цвета из обоев выключены): синий «Shelly».
private val BrandLight = lightColorScheme(
    primary = Color(0xFF2A5EA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF555F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F8), onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF6F5675), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9D8FE), onTertiaryContainer = Color(0xFF28132F),
    background = Color(0xFFF8F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4), surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8), outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6CF),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF003064),
    primaryContainer = Color(0xFF00468C), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC), onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDCBCE1), onTertiary = Color(0xFF3E2845),
    tertiaryContainer = Color(0xFF563E5C), onTertiaryContainer = Color(0xFFF9D8FE),
    background = Color(0xFF111318), onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111318), onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A), outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
)

@Composable
fun AppTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val theme by prefs.theme.state.collectAsState()
    val dynamic by prefs.dynamicColor.state.collectAsState()
    val dark = when (theme) {
        0 -> isSystemInDarkTheme()
        1 -> false
        else -> true
    }
    val ctx = LocalContext.current
    var scheme = when {
        dynamic == 1 && dark -> dynamicDarkColorScheme(ctx)
        dynamic == 1 -> dynamicLightColorScheme(ctx)
        dark -> BrandDark
        else -> BrandLight
    }
    if (theme == 3) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF0E0E0E), surfaceContainerHigh = Color(0xFF161616),
            surfaceContainerHighest = Color(0xFF1E1E1E),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
