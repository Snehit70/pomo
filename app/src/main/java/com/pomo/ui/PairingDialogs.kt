package com.pomo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pomo.R
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.PomoDialog
import com.pomo.ui.components.PomoSheet
import com.pomo.ui.theme.JetBrainsMono
import com.pomo.ui.theme.PomoRadius
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.SuccessGreenDark
import com.pomo.ui.theme.SuccessGreenLight
import kotlinx.coroutines.delay

internal data class PairingSheetData(
    val url: String,
    val token: String,
    val payload: String,
)

internal data class ScanResultData(
    val message: String,
    val url: String,
)

/** Masked token display: dots with a readable tail until the eye toggles it open. */
private fun maskedToken(
    token: String,
    shown: Boolean,
): String = if (shown) token else "••••••••••••" + token.takeLast(4)

private fun buildCommand(
    url: String,
    token: String,
): String = "pomo-link pair-json '{\"url\":\"$url\",\"token\":\"$token\"}'"

@Composable
internal fun PairingSheet(
    data: PairingSheetData,
    onCopy: (String) -> Unit,
    onShare: () -> Unit,
    onRotate: () -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    connectedProvider: () -> Int = { 0 },
) {
    var tokenShown by remember { mutableStateOf(false) }
    var connectedClients by remember { mutableStateOf(connectedProvider()) }
    LaunchedEffect(Unit) {
        while (true) {
            connectedClients = connectedProvider()
            delay(1_000)
        }
    }
    val connected = connectedClients > 0
    val successGreen = if (PomoTokens.colors.isDark) SuccessGreenDark else SuccessGreenLight

    PomoSheet(title = stringResource(R.string.pair_desktop_title), onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.pairing_sheet_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pulse = rememberInfiniteTransition()
                val dotAlpha by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                )
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .alpha(if (connected) 1f else dotAlpha)
                            .background(
                                if (connected) successGreen else MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        if (connected) R.string.pairing_status_connected else R.string.pairing_status_waiting,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (connected) {
                            successGreen
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(PomoRadius.Md))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            RoundedCornerShape(PomoRadius.Md),
                        ),
            ) {
                SelectionContainer {
                    Text(
                        text = buildCommand(data.url, maskedToken(data.token, tokenShown)),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 44.dp, bottom = 12.dp),
                    )
                }
                IconButton(
                    onClick = { onCopy(buildCommand(data.url, data.token)) },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.pairing_copy_command),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PomoButton(
                    onClick = { onCopy(data.payload) },
                    variant = PomoButtonVariant.Tonal,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.pairing_copy_code))
                }
                PomoButton(onClick = onShare, variant = PomoButtonVariant.Ghost) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(stringResource(R.string.pairing_share))
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            CopyRow(
                label = stringResource(R.string.pairing_url_label),
                value = data.url,
                shown = true,
                onToggle = {},
                onCopy = { onCopy(data.url) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            CopyRow(
                label = stringResource(R.string.pairing_token_label),
                value = maskedToken(data.token, tokenShown),
                shown = tokenShown,
                onToggle = { tokenShown = !tokenShown },
                onCopy = { onCopy(data.token) },
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            SheetActionRow(
                icon = Icons.Outlined.QrCodeScanner,
                title = stringResource(R.string.scan_pairing_qr_title),
                onClick = onScan,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            SheetActionRow(
                icon = Icons.Outlined.Autorenew,
                title = stringResource(R.string.rotate_pairing_token_title),
                summary = stringResource(R.string.rotate_pairing_token_summary),
                onClick = onRotate,
            )
        }
    }
}

@Composable
private fun CopyRow(
    label: String,
    value: String,
    shown: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        if (!shown) {
            IconButton(onClick = onToggle) {
                Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.pairing_toggle_token),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.pairing_copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RotateTokenConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PomoDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rotate_pairing_token_title)) },
        body = { Text(stringResource(R.string.rotate_pairing_token_confirm)) },
        actions = {
            PomoButton(onClick = onDismiss, variant = PomoButtonVariant.Ghost) {
                Text(stringResource(android.R.string.cancel))
            }
            PomoButton(onClick = onConfirm, variant = PomoButtonVariant.Filled) {
                Text(stringResource(R.string.rotate_pairing_token_action))
            }
        },
    )
}

@Composable
internal fun ScanResultDialog(
    data: ScanResultData,
    onDismiss: () -> Unit,
) {
    PomoDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_pairing_qr_title)) },
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(data.message, style = MaterialTheme.typography.bodyMedium)
                if (data.url.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            data.url,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
