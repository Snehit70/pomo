package com.pomo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.pomo.ui.theme.PomoSpacing
import com.pomo.ui.theme.PomoTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PomoDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    body: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PomoSpacing.Lg)
                    .widthIn(max = 360.dp),
            shape = MaterialTheme.shapes.large,
            color = PomoTokens.colors.surfaceElevated,
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        start = PomoSpacing.Lg,
                        top = PomoSpacing.Lg,
                        end = PomoSpacing.Lg,
                        bottom = 14.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(PomoSpacing.Sm),
            ) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge) {
                    title()
                }
                body()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    actions()
                }
            }
        }
    }
}
