package com.pomo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pomo.ui.theme.PomoTheme

public enum class PomoButtonVariant { Filled, Tonal, Ghost }

@Composable
public fun PomoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PomoButtonVariant = PomoButtonVariant.Filled,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val clickWithHaptic = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
    val phaseColor = MaterialTheme.colorScheme.primary
    val minSize = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    val interactionSource = remember { MutableInteractionSource() }

    when (variant) {
        PomoButtonVariant.Filled -> Button(
            onClick = clickWithHaptic,
            modifier = minSize,
            enabled = enabled && !loading,
            interactionSource = interactionSource,
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = phaseColor,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            content = { ButtonContent(loading, MaterialTheme.colorScheme.onPrimary, content) },
        )
        PomoButtonVariant.Tonal -> Button(
            onClick = clickWithHaptic,
            modifier = minSize,
            enabled = enabled && !loading,
            interactionSource = interactionSource,
            contentPadding = contentPadding,
            // Solid elevated-slate chip with a signal-red label. A translucent red fill
            // washed out against dark surfaces; the opaque slate + outline reads clearly.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = phaseColor,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            content = { ButtonContent(loading, phaseColor, content) },
        )
        PomoButtonVariant.Ghost -> TextButton(
            onClick = clickWithHaptic,
            modifier = minSize,
            enabled = enabled && !loading,
            interactionSource = interactionSource,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = phaseColor,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            content = { ButtonContent(loading, phaseColor, content) },
        )
    }
}

@Composable
private fun RowScope.ButtonContent(
    loading: Boolean,
    indicatorColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.padding(horizontal = 8.dp),
            color = indicatorColor,
            strokeWidth = 2.dp,
        )
    } else {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun PomoButtonPreview() {
    PomoTheme {
        androidx.compose.foundation.layout.Row {
            PomoButton(onClick = {}) { Text("Start") }
            PomoButton(onClick = {}, variant = PomoButtonVariant.Tonal) { Text("Skip") }
            PomoButton(onClick = {}, variant = PomoButtonVariant.Ghost) { Text("Reset") }
        }
    }
}
