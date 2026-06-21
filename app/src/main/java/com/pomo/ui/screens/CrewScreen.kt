package com.pomo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pomo.crew.CrewBoard
import com.pomo.crew.CrewBoardRow
import com.pomo.crew.CrewJoinCodeCodec
import com.pomo.crew.CrewJoinPayload
import com.pomo.crew.CrewMembershipSummary
import com.pomo.crew.CrewRankingMode
import com.pomo.ui.components.EmptyState
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.theme.PomoTokens
import java.util.Locale

public data class CrewScreenState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val board: CrewBoard? = null,
    val archivedMemberships: List<CrewMembershipSummary> = emptyList(),
    val errorMessage: String? = null,
)

@Composable
public fun CrewScreen(
    state: CrewScreenState,
    onCreateCrew: (String, String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
    onSwitchCrew: (String) -> Unit,
    onLeaveCrew: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onRankingModeChange: (CrewRankingMode) -> Unit,
    onMemberHiddenChange: (String, Boolean) -> Unit,
    onExportRecovery: () -> Unit,
    onImportRecovery: () -> Unit,
    initialJoinCode: String? = null,
    onInitialJoinCodeConsumed: () -> Unit = {},
) {
    var pendingJoin by remember { mutableStateOf<PendingJoin?>(null) }
    val requestJoin: (String, String) -> Unit = { joinCode, displayName ->
        val payload = CrewJoinCodeCodec.decode(joinCode.trim())
        if (payload == null) {
            onJoinCrew(joinCode, displayName)
        } else {
            pendingJoin = PendingJoin(joinCode.trim(), displayName, payload)
        }
    }
    LaunchedEffect(initialJoinCode) {
        if (initialJoinCode != null) {
            CrewJoinCodeCodec.decode(initialJoinCode)?.let { payload ->
                pendingJoin = PendingJoin(initialJoinCode, "", payload)
            }
            onInitialJoinCodeConsumed()
        }
    }
    // This Compose tree is hosted inside a View layout with no Surface ancestor, so the
    // ambient LocalContentColor defaults to black. Pin it to onSurface so any Text that
    // doesn't set its own color stays readable on the dark background.
    CompositionLocalProvider(LocalContentColor provides PomoTokens.colors.onSurface) {
        when {
            state.isLoading && state.board == null -> CrewLoadingState()
            state.board == null -> CrewEmptyState(
                archivedMemberships = state.archivedMemberships,
                errorMessage = state.errorMessage,
                onCreateCrew = onCreateCrew,
                onJoinCrew = requestJoin,
                onImportRecovery = onImportRecovery,
            )
            else -> CrewBoardContent(
                isSyncing = state.isSyncing,
                board = state.board,
                onCreateCrew = onCreateCrew,
                onJoinCrew = requestJoin,
                onSwitchCrew = onSwitchCrew,
                onLeaveCrew = onLeaveCrew,
                onDisplayNameChange = onDisplayNameChange,
                onRankingModeChange = onRankingModeChange,
                onMemberHiddenChange = onMemberHiddenChange,
                onExportRecovery = onExportRecovery,
                onImportRecovery = onImportRecovery,
            )
        }
    }
    pendingJoin?.let { pending ->
        JoinConfirmationSheet(
            pending = pending,
            onDismiss = { pendingJoin = null },
            onConfirm = {
                onJoinCrew(pending.joinCode, pending.displayName)
                pendingJoin = null
            },
        )
    }
}

@Composable
private fun CrewLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            headline = "Loading Crew",
            body = "Reading the last-known leaderboard.",
            icon = Icons.Outlined.Groups,
        )
    }
}

@Composable
private fun CrewEmptyState(
    archivedMemberships: List<CrewMembershipSummary>,
    errorMessage: String?,
    onCreateCrew: (String, String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
    onImportRecovery: () -> Unit,
) {
    var crewName by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        item {
            EmptyState(
                headline = if (archivedMemberships.isEmpty()) "No Crew yet" else "Crew v2 required",
                body = if (archivedMemberships.isEmpty()) {
                    "Create a private leaderboard or join one shared by a friend."
                } else {
                    "Older Crew memberships were archived locally. Create or join a v2 Crew for active rankings."
                },
                icon = Icons.Outlined.Groups,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (archivedMemberships.isNotEmpty()) {
            item {
                SectionHeader("Archived v1")
                Spacer(Modifier.height(8.dp))
                archivedMemberships.forEach { membership ->
                    Text(
                        text = "${membership.crewName} · ${membership.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PomoTokens.colors.onSurfaceMuted,
                    )
                }
            }
        }
        item {
            SectionHeader("Recovery")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Restore a saved identity and memberships after reinstalling or clearing Crew data.",
                style = MaterialTheme.typography.bodySmall,
                color = PomoTokens.colors.onSurfaceMuted,
            )
            Spacer(Modifier.height(8.dp))
            PomoButton(
                onClick = onImportRecovery,
                variant = PomoButtonVariant.Tonal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore Recovery") }
        }
        item {
            SectionHeader("Create")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = crewName,
                onValueChange = { crewName = it },
                label = { Text("Crew name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            NameField(displayName, onValueChange = { displayName = it })
        }
        item {
            PomoButton(
                onClick = { onCreateCrew(crewName, displayName) },
                enabled = crewName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create Crew") }
        }
        item {
            SectionHeader("Join")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = joinCode,
                onValueChange = { joinCode = it },
                label = { Text("Crew link or join code") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            PomoButton(
                onClick = { onJoinCrew(joinCode, displayName) },
                enabled = joinCode.isNotBlank(),
                variant = PomoButtonVariant.Tonal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Review Join") }
        }
        if (errorMessage != null) {
            item {
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

internal fun CrewBoardRow.matchesSearch(query: String, duplicateNames: Set<String>): Boolean {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return true
    return displayName.lowercase(Locale.ROOT).contains(normalized) ||
        (displayName.trim().lowercase(Locale.ROOT) in duplicateNames && identityPublicKey.startsWith(normalized))
}

internal fun standingContext(self: CrewBoardRow, rows: List<CrewBoardRow>): String {
    val rank = self.rank ?: return "UNRANKED"
    val tied = rows.count { it.rank == rank }
    if (tied > 1) return "TIED WITH ${tied - 1}"
    if (rank == 1) {
        val second = rows.firstOrNull { it.rank != null && it.rank > 1 } ?: return "SOLE LEADER"
        return "+${formatMinutes(self.selectedFocusMinutes - second.selectedFocusMinutes)} LEAD"
    }
    val next = rows.lastOrNull { it.rank != null && it.rank < rank } ?: return "RANKED"
    return "${formatMinutes(next.selectedFocusMinutes - self.selectedFocusMinutes)} TO #${next.rank}"
}

internal fun rowMeta(row: CrewBoardRow): String = buildList {
    add("${row.currentStreak}d streak")
    add("${row.todaySessionCount} blocks today")
    if (row.isStale) add("stale")
    if (row.isInactive) add("inactive")
}.joinToString(" · ")

internal fun freshnessLabel(board: CrewBoard, isSyncing: Boolean): String {
    val updated = board.lastUpdatedEpochSeconds ?: return "NO SNAPSHOTS"
    val ageSeconds = ((System.currentTimeMillis() / 1000L) - updated).coerceAtLeast(0L)
    val age = when {
        ageSeconds < 60 -> "NOW"
        ageSeconds < 3600 -> "${ageSeconds / 60}m AGO"
        else -> "${ageSeconds / 3600}h AGO"
    }
    val syncPrefix = if (isSyncing) "SYNCING · " else ""
    return when {
        board.successfulRelayCount == 0 -> "${syncPrefix}OFFLINE · UPDATED $age"
        board.successfulRelayCount < board.totalRelayCount ->
            "${syncPrefix}PARTIAL · ${board.successfulRelayCount}/${board.totalRelayCount} RELAYS · $age"
        else -> "${syncPrefix}UPDATED $age"
    }
}

@Composable
internal fun freshnessColor(board: CrewBoard, isSyncing: Boolean) =
    when {
        isSyncing -> PomoTokens.colors.accent
        board.successfulRelayCount == 0 -> PomoTokens.colors.warn
        else -> PomoTokens.colors.onSurfaceMuted
    }

internal val CrewRankingMode.label: String
    get() = when (this) {
        CrewRankingMode.Today -> "Today"
        CrewRankingMode.SevenDays -> "7 day"
        CrewRankingMode.ThirtyDays -> "30 day"
        CrewRankingMode.AllTime -> "All time"
    }

internal fun comparisonLabel(deltaMinutes: Int): String = when {
    deltaMinutes > 0 -> "+${formatMinutes(deltaMinutes)}"
    deltaMinutes < 0 -> "-${formatMinutes(-deltaMinutes)}"
    else -> "Even"
}

internal fun comparisonDaysLabel(deltaDays: Int): String = when {
    deltaDays > 0 -> "+${deltaDays}d"
    deltaDays < 0 -> "-${-deltaDays}d"
    else -> "Even"
}

internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

internal fun List<Int>.median(): Int {
    if (isEmpty()) return 0
    val middle = size / 2
    return if (size % 2 == 1) this[middle] else (this[middle - 1] + this[middle]) / 2
}

internal const val SEARCH_THRESHOLD: Int = 20
internal const val QR_SIZE: Int = 512
internal const val QR_FOREGROUND: Int = -0xefecea
internal const val QR_BACKGROUND: Int = -0x90807

internal data class PendingJoin(
    val joinCode: String,
    val displayName: String,
    val payload: CrewJoinPayload,
)
