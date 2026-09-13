package com.pomo.ui.components

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pomo.ui.theme.PomoTheme

public data class SegmentedToggleOption(
    val value: String,
    val label: String,
    val weight: Float = 1f,
    val icon: ImageVector? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SegmentedToggle(
    options: List<SegmentedToggleOption>,
    selectedValue: String,
    onSelectedValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.height(IntrinsicSize.Min)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option.value == selectedValue,
                onClick = { onSelectedValueChange(option.value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.weight(option.weight).fillMaxHeight(),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        activeContentColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                if (option.icon != null) {
                    Icon(
                        option.icon,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp),
                    )
                }
                Text(option.label)
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
