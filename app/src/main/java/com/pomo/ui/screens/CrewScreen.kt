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
import com.pomo.ui.components.EmptyState
import com.pomo.ui.components.PomoButton
import com.pomo.ui.theme.PomoTokens

public data class CrewScreenState(
    val isLoading: Boolean = false,
    val board: CrewBoard? = null,
)

@Composable
public fun CrewScreen(
    state: CrewScreenState,
    onCreateCrew: (String) -> Unit,
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
            state.board == null -> CrewEmptyState(onCreateCrew)
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
private fun CrewEmptyState(onCreateCrew: (String) -> Unit) {
    var displayName by remember { mutableStateOf("") }
    EmptyState(
        headline = "No Crew yet",
        body = "Create a Crew to publish your focus minutes and see yourself ranked.",
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
            }
        },
    )
}

@Composable
private fun CrewBoardContent(board: CrewBoard) {
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        board.rows.forEach { row ->
            CrewRow(row)
        }
    }
}

@Composable
private fun CrewRow(row: CrewBoardRow) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "#${row.rank}",
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
}
