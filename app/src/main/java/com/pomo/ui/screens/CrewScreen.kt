package com.pomo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pomo.crew.CrewBoard
import com.pomo.crew.CrewBoardRow
import com.pomo.crew.CrewRankingMode
import com.pomo.ui.components.EmptyState
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.SegmentedToggle
import com.pomo.ui.components.SegmentedToggleOption
import com.pomo.ui.theme.PomoTokens

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
            else -> CrewBoardContent(state.board)
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
private fun CrewBoardContent(board: CrewBoard) {
    var rankingMode by remember { mutableStateOf(CrewRankingMode.AllTime) }
    val rows = board.rows.rankedFor(rankingMode)
    Text(
        text = "Join code",
        style = MaterialTheme.typography.labelSmall,
        color = PomoTokens.colors.onSurfaceMuted,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = board.joinCode,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(24.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { index, row ->
            CrewRow(row, displayRank = index + 1)
        }
    }
}

@Composable
private fun CrewRow(row: CrewBoardRow, displayRank: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "#$displayRank",
                modifier = Modifier.weight(0.18f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (row.isSelf) "${row.displayName} (you)" else row.displayName,
                modifier = Modifier.weight(0.52f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${row.allTimeFocusMinutes}m",
                modifier = Modifier.weight(0.3f),
                style = MaterialTheme.typography.titleMedium,
                color = PomoTokens.colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "${row.todayFocusMinutes}m today - ${row.todaySessionCount} sessions - ${row.currentStreak} day streak - ${lastSeen(row.lastActiveEpochSeconds)}",
            style = MaterialTheme.typography.bodySmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
    }
}

private fun List<CrewBoardRow>.rankedFor(mode: CrewRankingMode): List<CrewBoardRow> =
    sortedWith(
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
