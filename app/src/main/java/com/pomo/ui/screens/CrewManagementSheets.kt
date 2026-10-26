package com.pomo.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.pomo.crew.CrewBoard
import com.pomo.crew.CrewBoardRow
import com.pomo.crew.CrewJoinPreview
import com.pomo.crew.CrewValidation
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.PomoDialog
import com.pomo.ui.components.PomoSheet
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.theme.PomoTokens
import java.util.Locale

private val ManageCrewButtonShape = RoundedCornerShape(12.dp)

@Composable
internal fun ManageCrewScreen(
    board: CrewBoard,
    profileDisplayName: String,
    onBack: () -> Unit,
    onCreateCrew: (String, String) -> Unit,
    onReviewJoin: (String) -> Unit,
    onLeaveCrew: (String) -> Unit,
    onMemberHiddenChange: (String, Boolean) -> Unit,
    onExportRecovery: () -> Unit,
    onImportRecovery: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var joinCode by remember { mutableStateOf("") }
    var createRequest by remember(profileDisplayName) { mutableStateOf<CreateCrewRequest?>(null) }
    var pendingHide by remember { mutableStateOf<CrewBoardRow?>(null) }
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    val payload = remember(board.joinCode) { com.pomo.crew.CrewJoinCodeCodec.decode(board.joinCode) }
    val shareUri = payload?.let(com.pomo.crew.CrewJoinCodeCodec::encodeUri) ?: board.joinCode
    BackHandler(onBack = onBack)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Manage ${board.crewName}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CrewQrCode(shareUri)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PomoButton(
                        onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "Join ${board.crewName} in Pomo:\n$shareUri")
                                    },
                                    "Share Crew",
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = ManageCrewButtonShape,
                    ) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share Crew")
                    }
                    PomoButton(
                        onClick = { clipboard.setText(AnnotatedString(board.joinCode)) },
                        modifier = Modifier.weight(1f),
                        variant = PomoButtonVariant.Tonal,
                        shape = ManageCrewButtonShape,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy code")
                    }
                }
            }
            val hideableMembers = board.rows.filterNot { it.isSelf }
            if (hideableMembers.isNotEmpty()) {
                item { SectionHeader("Members") }
                item { MemberTableHeader() }
                items(hideableMembers, key = { "member-${it.identityPublicKey}" }) { row ->
                    MemberTableRow(
                        displayName = row.displayName,
                        focusMinutes = row.selectedFocusMinutes,
                        identityPublicKey = row.identityPublicKey,
                        isHidden = false,
                        onToggleHidden = { pendingHide = row },
                    )
                    HorizontalDivider(color = PomoTokens.colors.outline)
                }
            }
            if (board.hiddenMembers.isNotEmpty()) {
                item { SectionHeader("Hidden members") }
                item { MemberTableHeader() }
                items(board.hiddenMembers, key = { "hidden-${it.identityPublicKey}" }) { member ->
                    MemberTableRow(
                        displayName = member.displayName,
                        focusMinutes = member.selectedFocusMinutes,
                        identityPublicKey = member.identityPublicKey,
                        isHidden = true,
                        onToggleHidden = { onMemberHiddenChange(member.identityPublicKey, false) },
                    )
                    HorizontalDivider(color = PomoTokens.colors.outline)
                }
            }
            if (board.memberships.any { it.isArchived }) {
                item { SectionHeader("Archived v1") }
                items(board.memberships.filter { it.isArchived }, key = { "archived-${it.crewId}" }) { membership ->
                    Text(
                        text = "${membership.crewName} · ${membership.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PomoTokens.colors.onSurfaceMuted,
                    )
                }
            }
            item { SectionHeader("Recovery") }
            item {
                Text(
                    text = "Export your current identity before restoring another one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PomoTokens.colors.onSurfaceMuted,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PomoButton(
                        onClick = onExportRecovery,
                        modifier = Modifier.weight(1f),
                        variant = PomoButtonVariant.Tonal,
                        shape = ManageCrewButtonShape,
                    ) {
                        Text("Export")
                    }
                    PomoButton(
                        onClick = onImportRecovery,
                        modifier = Modifier.weight(1f),
                        variant = PomoButtonVariant.Ghost,
                        shape = ManageCrewButtonShape,
                    ) {
                        Text("Restore")
                    }
                }
            }
            item { SectionHeader("Join another") }
            item {
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { joinCode = it },
                    label = { Text("Crew link or code") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                PomoButton(
                    onClick = { onReviewJoin(joinCode) },
                    enabled = joinCode.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    variant = PomoButtonVariant.Tonal,
                    shape = ManageCrewButtonShape,
                ) { Text("Review Join") }
            }
            item { SectionHeader("Create another") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PomoButton(
                        onClick = { createRequest = CreateCrewRequest(profileDisplayName) },
                        modifier = Modifier.weight(1f),
                        variant = PomoButtonVariant.Tonal,
                        shape = ManageCrewButtonShape,
                    ) { Text("Create") }
                    PomoButton(
                        onClick = { showLeaveConfirmation = true },
                        modifier = Modifier.weight(1f),
                        variant = PomoButtonVariant.Ghost,
                        shape = ManageCrewButtonShape,
                    ) { Text("Leave Crew") }
                }
            }
        }
    }
    createRequest?.let { request ->
        CreateCrewSheet(
            initialDisplayName = request.initialDisplayName,
            onDismiss = { createRequest = null },
            onConfirm = { crewName, name ->
                onCreateCrew(crewName, name)
                createRequest = null
            },
        )
    }
    pendingHide?.let { row ->
        HideMemberConfirmDialog(
            memberName = row.displayName,
            onConfirm = {
                onMemberHiddenChange(row.identityPublicKey, true)
                pendingHide = null
            },
            onDismiss = { pendingHide = null },
        )
    }
    if (showLeaveConfirmation) {
        PomoDialog(
            onDismissRequest = { showLeaveConfirmation = false },
            title = { Text("Leave ${board.crewName}?") },
            body = {
                Text(
                    "Your membership and this phone's cached leaderboard for this Crew will be " +
                        "removed. Other members may still see your last shared stats until they " +
                        "age out. You'll need a current Crew link or code to rejoin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PomoTokens.colors.onSurfaceMuted,
                )
            },
            actions = {
                TextButton(
                    onClick = { showLeaveConfirmation = false },
                    shape = ManageCrewButtonShape,
                ) { Text("Cancel") }
                TextButton(
                    onClick = {
                        showLeaveConfirmation = false
                        onLeaveCrew(board.crewId)
                    },
                    shape = ManageCrewButtonShape,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Leave Crew") }
            },
        )
    }
}

@Composable
private fun MemberTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "MEMBER",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
        Text(
            text = "FOCUS",
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
            textAlign = TextAlign.End,
        )
        Text(
            text = "KEY",
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun MemberTableRow(
    displayName: String,
    focusMinutes: Int,
    identityPublicKey: String,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatMinutes(focusMinutes),
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Text(
            text = identityPublicKey.take(4).uppercase(Locale.ROOT),
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        IconButton(onClick = onToggleHidden) {
            Icon(
                imageVector = if (isHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = if (isHidden) "Unhide $displayName" else "Hide $displayName",
                tint = PomoTokens.colors.onSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun HideMemberConfirmDialog(
    memberName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PomoDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hide $memberName?") },
        body = {
            Text(
                text =
                    "They disappear from your leaderboard on this device only. Nothing is " +
                        "deleted for them, and you can unhide them any time from Manage.",
                style = MaterialTheme.typography.bodyMedium,
                color = PomoTokens.colors.onSurfaceMuted,
            )
        },
        actions = {
            TextButton(onClick = onDismiss, shape = ManageCrewButtonShape) { Text("Cancel") }
            TextButton(onClick = onConfirm, shape = ManageCrewButtonShape) { Text("Hide") }
        },
    )
}

@Composable
private fun CrewQrCode(value: String) {
    val bitmap =
        remember(value) {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
            Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).apply {
                val pixels =
                    IntArray(QR_SIZE * QR_SIZE) { index ->
                        val x = index % QR_SIZE
                        val y = index / QR_SIZE
                        if (matrix[x, y]) QR_FOREGROUND else QR_BACKGROUND
                    }
                setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
            }
        }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code to join Crew",
            modifier = Modifier.size(160.dp),
        )
        Text("SCAN TO JOIN", style = MaterialTheme.typography.labelSmall, color = PomoTokens.colors.onSurfaceMuted)
    }
}

/**
 * The name belongs to the Profile, not to this Crew. A member who already has one is told which
 * name they are joining under and is not asked again; only a member who has never named themselves
 * is asked, and the caller writes that answer to the Profile.
 * See docs/adr/0004-profile-replaces-settings-in-the-nav.md.
 */
@Composable
internal fun JoinConfirmationSheet(
    pending: PendingJoin,
    loadPreview: suspend (String) -> CrewJoinPreview?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var preview by remember(pending.joinCode) { mutableStateOf<CrewJoinPreview?>(null) }
    val alreadyNamed = pending.initialDisplayName.isNotBlank()
    var displayName by remember(pending.joinCode) { mutableStateOf(pending.initialDisplayName) }
    LaunchedEffect(pending.joinCode) {
        preview = loadPreview(pending.joinCode)
    }
    val normalizedDisplayName =
        remember(displayName) {
            CrewValidation.normalizeDisplayName(displayName)
        }
    val duplicateName =
        normalizedDisplayName
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.let { normalized -> preview?.knownDisplayNames?.contains(normalized) == true }
            ?: false

    PomoSheet(title = "Join Crew", onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = pending.payload.crewName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Shared focus, measured together.",
                style = MaterialTheme.typography.bodyMedium,
                color = PomoTokens.colors.onSurfaceMuted,
            )
            preview?.let { stats ->
                JoinPreviewStats(stats)
            }
            if (alreadyNamed) {
                Text(
                    text = "Joining as ${pending.initialDisplayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                NameField(value = displayName, onValueChange = { displayName = it })
            }
            if (duplicateName) {
                Text(
                    text = "Name already in use in this Crew. Your key will still distinguish you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PomoTokens.colors.onSurfaceMuted,
                )
            }
            PomoButton(
                onClick = { normalizedDisplayName?.let(onConfirm) },
                enabled = normalizedDisplayName != null,
                modifier = Modifier.fillMaxWidth(),
                shape = ManageCrewButtonShape,
            ) { Text("Join Crew") }
        }
    }
}

@Composable
internal fun CreateCrewSheet(
    initialDisplayName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var crewName by remember(initialDisplayName) { mutableStateOf("") }
    val alreadyNamed = initialDisplayName.isNotBlank()
    var displayName by remember(initialDisplayName) { mutableStateOf(initialDisplayName) }
    val normalizedCrewName = remember(crewName) { CrewValidation.normalizeCrewName(crewName) }
    val normalizedDisplayName = remember(displayName) { CrewValidation.normalizeDisplayName(displayName) }

    PomoSheet(title = "Create Crew", onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Shared focus, measured together.",
                style = MaterialTheme.typography.bodyMedium,
                color = PomoTokens.colors.onSurfaceMuted,
            )
            OutlinedTextField(
                value = crewName,
                onValueChange = { crewName = it },
                label = { Text("Crew name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (alreadyNamed) {
                Text(
                    text = "Creating as $initialDisplayName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                NameField(value = displayName, onValueChange = { displayName = it })
            }
            PomoButton(
                onClick = {
                    val name = normalizedCrewName
                    val display = normalizedDisplayName
                    if (name != null && display != null) onConfirm(name, display)
                },
                enabled = normalizedCrewName != null && normalizedDisplayName != null,
                modifier = Modifier.fillMaxWidth(),
                shape = ManageCrewButtonShape,
            ) { Text("Create Crew") }
        }
    }
}

@Composable
private fun JoinPreviewStats(preview: CrewJoinPreview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreviewStat(value = preview.activeMembers.toString(), label = "ACTIVE", modifier = Modifier.weight(1f))
        PreviewStat(value = formatMinutes(preview.todayFocusMinutes), label = "TODAY", modifier = Modifier.weight(1f))
        PreviewStat(value = formatMinutes(preview.medianMemberFocusMinutes), label = "MEDIAN", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Display name") },
        supportingText = { Text("Required") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
