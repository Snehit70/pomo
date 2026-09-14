package com.pomo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pomo.ui.theme.JetBrainsMono
import com.pomo.ui.theme.PomoRadius
import com.pomo.ui.theme.PomoTokens

/** 44dp outlined field used by the number editor and tag add/rename dialogs. */
@Composable
public fun CompactEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suffix: String? = null,
    trailing: @Composable () -> Unit = {},
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    mono: Boolean = false,
    onConfirm: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val tokens = PomoTokens.colors
    val borderColor = if (focused) tokens.outlineStrong else tokens.outline
    val textStyle: TextStyle =
        if (mono) {
            MaterialTheme.typography.titleLarge.copy(
                fontFamily = JetBrainsMono,
                color = tokens.onSurface,
            )
        } else {
            MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium,
                color = tokens.onSurface,
            )
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(tokens.surface, RoundedCornerShape(PomoRadius.Md))
                .border(1.dp, borderColor, RoundedCornerShape(PomoRadius.Md))
                .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.onSurfaceFaint,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(tokens.focus),
                keyboardOptions = keyboardOptions,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && onConfirm != null) {
                                onConfirm()
                                true
                            } else {
                                false
                            }
                        },
            )
        }
        if (!suffix.isNullOrEmpty()) {
            Text(
                suffix,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.onSurfaceMuted,
            )
            Spacer(Modifier.width(8.dp))
        }
        trailing()
    }
}
