package com.pomo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pomo.crew.CrewBoard
import com.pomo.crew.CrewBoardRow
import com.pomo.crew.CrewRankingMode
import com.pomo.ui.components.EmptyState
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.components.SegmentedToggle
import com.pomo.ui.components.SegmentedToggleOption
import com.pomo.ui.theme.PomoTokens
import kotlinx.coroutines.delay

public data class CrewScreenState(
    val isLoading: Boolean = false,
    val board: CrewBoard? = null,
    val errorMessage: String? = null,
)

@Composable
public fun CrewScreen(
    state: CrewScreenState,
    onCreateCrew: (String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
    onSwitchCrew: (String) -> Unit,
    onLeaveCrew: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            "Crew",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(20.dp))

        when {
            state.isLoading -> CrewLoadingState()
            state.board == null -> CrewEmptyState(
                errorMessage = state.errorMessage,
                onCreateCrew = onCreateCrew,
                onJoinCrew = onJoinCrew,
            )
            else -> CrewBoardContent(
                board = state.board,
                onCreateCrew = onCreateCrew,
                onJoinCrew = onJoinCrew,
                onSwitchCrew = onSwitchCrew,
                onLeaveCrew = onLeaveCrew,
                onDisplayNameChange = onDisplayNameChange,
            )
        }
    }
}

@Composable
private fun CrewLoadingState() {
    EmptyState(
        headline = "Loading Crew",
        body = "Checking whether this phone already belongs to a Crew.",
        icon = Icons.Outlined.Groups,
        modifier = Modifier.fillMaxWidth(),
        action = {
            CircularProgressIndicator()
        },
    )
}

@Composable
private fun CrewEmptyState(
    errorMessage: String?,
    onCreateCrew: (String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
) {
    var displayName by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    EmptyState(
        headline = "No Crew yet",
        body = "Create a Crew or paste a join code from a friend.",
        icon = Icons.Outlined.Groups,
        modifier = Modifier.fillMaxWidth(),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
                PomoButton(onClick = { onCreateCrew(displayName) }) {
                    Text("Create Crew")
                }
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { joinCode = it },
                    label = { Text("Join code") },
                    minLines = 2,
                )
                PomoButton(onClick = { onJoinCrew(joinCode, displayName) }) {
                    Text("Join Crew")
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun CrewBoardContent(
    board: CrewBoard,
    onCreateCrew: (String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
    onSwitchCrew: (String) -> Unit,
    onLeaveCrew: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
) {
    var rankingMode by remember { mutableStateOf(CrewRankingMode.AllTime) }
    val rows = board.rows.rankedFor(rankingMode)
    if (board.memberships.size > 1) {
        SectionHeader("Crew switcher")
        Spacer(Modifier.height(8.dp))
        SegmentedToggle(
            options = board.memberships.map { membership ->
                SegmentedToggleOption(membership.crewId, membership.crewId.take(6))
            },
            selectedValue = board.crewId,
            onSelectedValueChange = onSwitchCrew,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }

    JoinCodeCard(joinCode = board.joinCode)

    Spacer(Modifier.height(24.dp))
    CrewManagementPanel(
        board = board,
        onCreateCrew = onCreateCrew,
        onJoinCrew = onJoinCrew,
        onLeaveCrew = onLeaveCrew,
        onDisplayNameChange = onDisplayNameChange,
    )
    Spacer(Modifier.height(28.dp))
    Text(
        text = "Leaderboard",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(12.dp))
    SegmentedToggle(
        options = listOf(
            SegmentedToggleOption(CrewRankingMode.AllTime.name, "All-time"),
            SegmentedToggleOption(CrewRankingMode.Today.name, "Today"),
        ),
        selectedValue = rankingMode.name,
        onSelectedValueChange = { rankingMode = CrewRankingMode.valueOf(it) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            CrewRow(row, displayRank = index + 1, mode = rankingMode)
        }
    }
}

@Composable
private fun JoinCodeCard(joinCode: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PomoTokens.colors.surfaceElevated)
            .border(1.dp, PomoTokens.colors.outline, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        SectionHeader("Join code")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = joinCode,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            PomoButton(
                onClick = {
                    clipboard.setText(AnnotatedString(joinCode))
                    copied = true
                },
                variant = PomoButtonVariant.Tonal,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied" else "Copy")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Share this code so friends can join your Crew.",
            style = MaterialTheme.typography.bodySmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun CrewManagementPanel(
    board: CrewBoard,
    onCreateCrew: (String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
    onLeaveCrew: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
) {
    var displayName by remember(board.displayName) { mutableStateOf(board.displayName) }
    var joinCode by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Manage",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display name for all Crews") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PomoButton(
                onClick = { onDisplayNameChange(displayName) },
                variant = PomoButtonVariant.Tonal,
            ) {
                Text("Save name")
            }
            PomoButton(
                onClick = { onLeaveCrew(board.crewId) },
                variant = PomoButtonVariant.Ghost,
            ) {
                Text("Leave Crew")
            }
        }
        OutlinedTextField(
            value = joinCode,
            onValueChange = { joinCode = it },
            label = { Text("Join another Crew") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PomoButton(onClick = { onJoinCrew(joinCode, displayName) }) {
                Text("Join")
            }
            PomoButton(
                onClick = { onCreateCrew(displayName) },
                variant = PomoButtonVariant.Tonal,
            ) {
                Text("Create another")
            }
        }
    }
}

@Composable
private fun CrewRow(row: CrewBoardRow, displayRank: Int, mode: CrewRankingMode) {
    val rowAlpha = if (row.isStale) 0.52f else 1f
    val headlineMinutes = when (mode) {
        CrewRankingMode.AllTime -> row.allTimeFocusMinutes
        CrewRankingMode.Today -> row.todayFocusMinutes
    }
    val containerColor = if (row.isSelf) {
        PomoTokens.colors.accent.copy(alpha = 0.10f)
    } else {
        PomoTokens.colors.surface
    }
    val borderColor = if (row.isSelf) {
        PomoTokens.colors.accent.copy(alpha = 0.55f)
    } else {
        PomoTokens.colors.outline
    }
    val rankColor = if (displayRank == 1) PomoTokens.colors.accent else PomoTokens.colors.onSurfaceMuted
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .alpha(rowAlpha)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#$displayRank",
                modifier = Modifier.weight(0.16f),
                style = MaterialTheme.typography.titleMedium,
                color = rankColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (row.isSelf) "${row.displayName} (you)" else row.displayName,
                modifier = Modifier.weight(0.54f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${headlineMinutes}m",
                modifier = Modifier.weight(0.30f),
                style = MaterialTheme.typography.titleMedium,
                color = PomoTokens.colors.accent,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = crewRowMeta(row),
            style = MaterialTheme.typography.bodySmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
    }
}

private fun crewRowMeta(row: CrewBoardRow): String {
    val parts = buildList {
        add("${row.todayFocusMinutes}m today")
        add("${row.todaySessionCount} sessions")
        add("${row.currentStreak} day streak")
        add(lastSeen(row.lastActiveEpochSeconds))
        if (row.isStale) add("stale")
    }
    return parts.joinToString("  ·  ")
}

private fun List<CrewBoardRow>.rankedFor(mode: CrewRankingMode): List<CrewBoardRow> =
    filter { row -> mode != CrewRankingMode.AllTime || !row.isDroppedFromAllTime }
        .sortedWith(
        compareByDescending<CrewBoardRow> {
            when (mode) {
                CrewRankingMode.AllTime -> it.allTimeFocusMinutes
                CrewRankingMode.Today -> it.todayFocusMinutes
            }
        }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.identityPublicKey },
    )

private fun lastSeen(lastActiveEpochSeconds: Long): String {
    val elapsedMinutes = ((System.currentTimeMillis() / 1000L) - lastActiveEpochSeconds)
        .coerceAtLeast(0L) / 60L
    return when {
        elapsedMinutes < 1L -> "last seen just now"
        elapsedMinutes == 1L -> "last seen 1 min ago"
        elapsedMinutes < 60L -> "last seen $elapsedMinutes min ago"
        else -> "last seen ${elapsedMinutes / 60L}h ago"
    }
}
