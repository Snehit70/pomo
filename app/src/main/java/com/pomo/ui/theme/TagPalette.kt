package com.pomo.ui.theme

import androidx.compose.ui.graphics.Color

/** Stable per-tag presentation colors, indexed by TagStore color slots. */
public val tagPalette: List<Color> =
    listOf(
        Color(0xFF64B5F6),
        Color(0xFF81C784),
        Color(0xFFFFB74D),
        Color(0xFFF06292),
        Color(0xFFBA68C8),
        Color(0xFF4DD0E1),
        Color(0xFFFF8A65),
        Color(0xFF90A4AE),
        Color(0xFFAED581),
        Color(0xFF7986CB),
    )

public val tagPaletteLight: List<Color> =
    listOf(
        Color(0xFF1565C0),
        Color(0xFF2E7D32),
        Color(0xFFEF6C00),
        Color(0xFFC2185B),
        Color(0xFF7B1FA2),
        Color(0xFF00838F),
        Color(0xFFD84315),
        Color(0xFF455A64),
        Color(0xFF558B2F),
        Color(0xFF3949AB),
    )

/** Success green from the UI-round mock (Installed chip, picker check, connected dot). */
public val SuccessGreenDark: Color = Color(0xFF6BD98C)

public val SuccessGreenLight: Color = Color(0xFF2E7D32)
