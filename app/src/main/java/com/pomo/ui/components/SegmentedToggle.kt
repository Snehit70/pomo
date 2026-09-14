package com.pomo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pomo.ui.theme.PomoRadius
import com.pomo.ui.theme.PomoTheme
import com.pomo.ui.theme.PomoTokens

public data class SegmentedToggleOption(
    val value: String,
    val label: String,
    val weight: Float = 1f,
    val icon: ImageVector? = null,
)

/**
 * Mock segmented control: 40dp outlined split, 14dp end radii, selected fill
 * `signal @ 18%` with signal label. No M3 checkmark, no stadium shape.
 */
@Composable
public fun SegmentedToggle(
    options: List<SegmentedToggleOption>,
    selectedValue: String,
    onSelectedValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = PomoTokens.colors
    val last = options.lastIndex
    Row(modifier = modifier.height(40.dp).selectableGroup()) {
        options.forEachIndexed { index, option ->
            val selected = option.value == selectedValue
            val shape: Shape =
                when (index) {
                    0 -> RoundedCornerShape(topStart = PomoRadius.Md, bottomStart = PomoRadius.Md)
                    last -> RoundedCornerShape(topEnd = PomoRadius.Md, bottomEnd = PomoRadius.Md)
                    else -> RectangleShape
                }
            val contentColor = if (selected) tokens.focus else tokens.onSurface
            Box(
                modifier =
                    Modifier
                        .weight(option.weight)
                        .fillMaxHeight()
                        .then(if (index > 0) Modifier.offset(x = (-1).dp) else Modifier)
                        .zIndex(if (selected) 1f else 0f)
                        .clip(shape)
                        .background(if (selected) tokens.focus.copy(alpha = 0.18f) else Color.Transparent)
                        .border(1.dp, tokens.outline, shape)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelectedValueChange(option.value) },
                        )
                        .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (option.icon != null) {
                        Icon(
                            option.icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SegmentedTogglePreview() {
    PomoTheme {
        SegmentedToggle(
            options =
                listOf(
                    SegmentedToggleOption("today", "Today"),
                    SegmentedToggleOption("week", "Week"),
                    SegmentedToggleOption("month", "Month"),
                ),
            selectedValue = "today",
            onSelectedValueChange = {},
        )
    }
}
