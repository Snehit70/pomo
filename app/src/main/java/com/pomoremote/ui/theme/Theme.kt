package com.pomoremote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PomoColorScheme = darkColorScheme(
    primary = FocusCoral,
    onPrimary = OnFocus,
    primaryContainer = FocusContainer,
    onPrimaryContainer = OnFocusContainer,
    secondary = BreakTeal,
    onSecondary = OnBreak,
    secondaryContainer = BreakContainer,
    onSecondaryContainer = OnBreakContainer,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = OledBackground,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
)

@Composable
public fun PomoRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PomoColorScheme,
        typography = PomoTypography,
        content = content,
    )
}
