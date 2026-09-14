package com.pomo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pomo.ui.theme.PomoRadius
import com.pomo.ui.theme.PomoTokens

/**
 * Mock switch: 52×32 outlined track, 16dp thumb off, 24dp white thumb on a filled
 * signal track. Red is reserved for the on state.
 */
@Composable
public fun PomoSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = PomoTokens.colors
    val thumbSize by animateDpAsState(if (checked) 24.dp else 16.dp, tween(150), label = "thumb-size")
    val thumbOffset by animateDpAsState(if (checked) 22.dp else 6.dp, tween(150), label = "thumb-offset")
    val trackColor = if (checked) tokens.focus else Color.Transparent
    val borderColor = if (checked) tokens.focus else tokens.outline
    val thumbColor = if (checked) Color.White else tokens.outlineStrong
    Box(
        modifier =
            modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(RoundedCornerShape(PomoRadius.Pill))
                .background(trackColor)
                .border(2.dp, borderColor, RoundedCornerShape(PomoRadius.Pill))
                .toggleable(
                    value = checked,
                    enabled = enabled && onCheckedChange != null,
                    role = Role.Switch,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onValueChange = { onCheckedChange?.invoke(it) },
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .background(thumbColor, CircleShape),
        )
    }
}
