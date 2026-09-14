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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
            val commandBg = if (PomoTokens.colors.isDark) Color(0xFF0E0F12) else PomoTokens.colors.surface
            val masked = maskedToken(data.token, tokenShown)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(commandBg, RoundedCornerShape(PomoRadius.Md))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(PomoRadius.Md)),
            ) {
                SelectionContainer {
                    Text(
                        text =
                            buildAnnotatedString {
                                append("pomo-link pair-json ")
                                withStyle(SpanStyle(color = PomoTokens.colors.onSurfaceMuted)) {
                                    append("'{\"url\":\"${data.url}\",\"token\":\"$masked\"}'")
                                }
                            },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 44.dp, bottom = 12.dp),
                    )
                }
                IconButton(
                    onClick = { onCopy(buildCommand(data.url, data.token)) },
                    modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.pairing_copy_command),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CopyRow(
                icon = Icons.Outlined.Link,
                label = stringResource(R.string.pairing_url_label),
                value = data.url,
                canToggle = false,
                revealed = true,
                onToggle = {},
                onCopy = { onCopy(data.url) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            CopyRow(
                icon = Icons.Outlined.Key,
                label = stringResource(R.string.pairing_token_label),
                value = masked,
                canToggle = true,
                revealed = tokenShown,
                onToggle = { tokenShown = !tokenShown },
                onCopy = { onCopy(data.token) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PomoButton(
                    onClick = { onCopy(data.payload) },
                    variant = PomoButtonVariant.Tonal,
                    compact = true,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.pairing_copy_code))
                }
                PomoButton(onClick = onShare, variant = PomoButtonVariant.Ghost, compact = true) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(stringResource(R.string.pairing_share))
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
            SheetActionRow(
                icon = Icons.Outlined.QrCodeScanner,
                title = stringResource(R.string.scan_pairing_qr_title),
                onClick = onScan,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
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
    icon: ImageVector,
    label: String,
    value: String,
    canToggle: Boolean,
    revealed: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = PomoTokens.colors.onSurfaceFaint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PomoTokens.colors.onSurfaceFaint,
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
        if (canToggle) {
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.pairing_toggle_token),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
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
            PomoButton(onClick = onDismiss, variant = PomoButtonVariant.Ghost, compact = true) {
                Text(stringResource(android.R.string.cancel))
            }
            PomoButton(onClick = onConfirm, variant = PomoButtonVariant.Filled, compact = true) {
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
