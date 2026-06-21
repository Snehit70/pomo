package com.pomo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pomo.crew.CrewBoard
import com.pomo.crew.CrewBoardRow
import com.pomo.crew.CrewRankingMode
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.PomoSheet
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.components.SegmentedToggle
import com.pomo.ui.components.SegmentedToggleOption
import com.pomo.ui.components.StatTile
import com.pomo.ui.theme.PomoTokens
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CrewBoardContent(
    isSyncing: Boolean,
    board: CrewBoard,
    onCreateCrew: (String, String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
    onSwitchCrew: (String) -> Unit,
    onLeaveCrew: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onRankingModeChange: (CrewRankingMode) -> Unit,
    onMemberHiddenChange: (String, Boolean) -> Unit,
    onExportRecovery: () -> Unit,
    onImportRecovery: () -> Unit,
) {
    var showManage by remember { mutableStateOf(false) }
    var selectedMember by remember { mutableStateOf<CrewBoardRow?>(null) }
    var search by remember { mutableStateOf("") }
    var showInactive by remember { mutableStateOf(false) }
    val activeRows = board.rows.filterNot { it.isInactive }
    val inactiveRows = board.rows.filter { it.isInactive }
    val duplicateNames = activeRows.groupingBy { it.displayName.trim().lowercase(Locale.ROOT) }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    val visibleRows = activeRows.filter { row -> row.matchesSearch(search, duplicateNames) }
    val tiedRanks = activeRows.mapNotNull { it.rank }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
    ) {
        item(key = "header") {
            CrewHeader(board, isSyncing = isSyncing, onManage = { showManage = true })
            Spacer(Modifier.height(20.dp))
        }
        item(key = "window") {
            RankingWindowControl(board.rankingMode, onRankingModeChange)
            Spacer(Modifier.height(20.dp))
        }
        item(key = "summary") {
            CrewSummary(activeRows)
            Spacer(Modifier.height(20.dp))
        }
        item(key = "standing") {
            YourStanding(activeRows, tiedRanks)
            Spacer(Modifier.height(20.dp))
        }
        if (activeRows.size > SEARCH_THRESHOLD) {
            item(key = "search") {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search members") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
            }
        }
        item(key = "leaderboard-heading") {
            SectionHeader("Leaderboard")
            Spacer(Modifier.height(4.dp))
        }
        items(visibleRows, key = { it.identityPublicKey }) { row ->
            CrewRow(
                row = row,
                showFingerprint = row.displayName.trim().lowercase(Locale.ROOT) in duplicateNames,
                isTied = row.rank in tiedRanks,
                onClick = { selectedMember = row },
            )
            HorizontalDivider(color = PomoTokens.colors.outline)
        }
        if (visibleRows.isEmpty()) {
            item(key = "no-results") {
                Text(
                    text = if (search.isBlank()) "NO ACTIVE MEMBERS" else "NO MATCHES",
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = PomoTokens.colors.onSurfaceMuted,
                )
            }
        }
        if (inactiveRows.isNotEmpty()) {
            item(key = "inactive-toggle") {
                PomoButton(
                    onClick = { showInactive = !showInactive },
                    variant = PomoButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showInactive) "Hide inactive" else "Inactive · ${inactiveRows.size}")
                }
            }
            if (showInactive) {
                items(inactiveRows, key = { "inactive-${it.identityPublicKey}" }) { row ->
                    CrewRow(row, showFingerprint = false, isTied = false, onClick = { selectedMember = row })
                    HorizontalDivider(color = PomoTokens.colors.outline)
                }
            }
        }
    }

    if (showManage) {
        ManageCrewSheet(
            board = board,
            onDismiss = { showManage = false },
            onCreateCrew = onCreateCrew,
            onJoinCrew = onJoinCrew,
            onSwitchCrew = onSwitchCrew,
            onLeaveCrew = onLeaveCrew,
            onDisplayNameChange = onDisplayNameChange,
            onMemberHiddenChange = onMemberHiddenChange,
            onExportRecovery = onExportRecovery,
            onImportRecovery = onImportRecovery,
        )
    }
    selectedMember?.let { row ->
        MemberDetailSheet(
            row = row,
            self = board.rows.firstOrNull { it.isSelf },
            rankingMode = board.rankingMode,
            onDismiss = { selectedMember = null },
            onHide = {
                onMemberHiddenChange(row.identityPublicKey, true)
                selectedMember = null
            },
        )
    }
}

@Composable
private fun CrewHeader(board: CrewBoard, isSyncing: Boolean, onManage: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = board.crewName,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = freshnessLabel(board, isSyncing),
                style = MaterialTheme.typography.labelSmall,
                color = freshnessColor(board, isSyncing),
                fontFamily = FontFamily.Monospace,
            )
        }
        PomoButton(onClick = onManage, variant = PomoButtonVariant.Ghost) {
            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Manage")
        }
    }
}

@Composable
private fun RankingWindowControl(mode: CrewRankingMode, onChange: (CrewRankingMode) -> Unit) {
    SegmentedToggle(
        options = listOf(
            SegmentedToggleOption(CrewRankingMode.Today.name, "Today"),
            SegmentedToggleOption(CrewRankingMode.SevenDays.name, "7D"),
            SegmentedToggleOption(CrewRankingMode.ThirtyDays.name, "30D"),
            SegmentedToggleOption(CrewRankingMode.AllTime.name, "All"),
        ),
        selectedValue = mode.name,
        onSelectedValueChange = { onChange(CrewRankingMode.valueOf(it)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CrewSummary(rows: List<CrewBoardRow>) {
    val participating = rows.filter { it.selectedFocusMinutes > 0 }
    val total = participating.sumOf { it.selectedFocusMinutes }
    val median = participating.map { it.selectedFocusMinutes }.sorted().median()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(formatMinutes(total), "CREW FOCUS", Modifier.weight(1f))
        StatTile(participating.size.toString(), "ACTIVE", Modifier.weight(1f))
        StatTile(formatMinutes(median), "MEDIAN", Modifier.weight(1f))
    }
}

@Composable
private fun YourStanding(rows: List<CrewBoardRow>, tiedRanks: Set<Int>) {
    val self = rows.firstOrNull { it.isSelf } ?: return
    val context = standingContext(self, rows)
    val accent = PomoTokens.colors.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PomoTokens.colors.surfaceElevated)
            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text("YOUR STANDING", style = MaterialTheme.typography.labelSmall, color = PomoTokens.colors.onSurfaceMuted)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = self.rank?.let { if (it in tiedRanks) "=$it" else "#$it" } ?: "—",
                style = MaterialTheme.typography.headlineLarge,
                color = accent,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatMinutes(self.selectedFocusMinutes),
                style = MaterialTheme.typography.titleLarge,
                color = PomoTokens.colors.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(context, style = MaterialTheme.typography.labelSmall, color = PomoTokens.colors.onSurfaceMuted)
        }
    }
}

@Composable
private fun CrewRow(row: CrewBoardRow, showFingerprint: Boolean, isTied: Boolean, onClick: () -> Unit) {
    val rankLabel = row.rank?.let { if (isTied) "=$it" else "#$it" } ?: "—"
    val displayLabel = if (showFingerprint) {
        "${row.displayName} · ${row.identityPublicKey.take(4).uppercase(Locale.ROOT)}"
    } else {
        row.displayName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (row.isStale) 0.58f else 1f)
            .semantics {
                contentDescription = "$rankLabel, $displayLabel, ${formatMinutes(row.selectedFocusMinutes)}"
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rankLabel,
            modifier = Modifier.width(42.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (row.rank == 1) PomoTokens.colors.accent else PomoTokens.colors.onSurfaceMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (row.isSelf) "$displayLabel · YOU" else displayLabel,
                style = MaterialTheme.typography.titleMedium,
                color = PomoTokens.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rowMeta(row),
                style = MaterialTheme.typography.bodySmall,
                color = PomoTokens.colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SevenDayBars(row)
        Spacer(Modifier.width(12.dp))
        Text(
            formatMinutes(row.selectedFocusMinutes),
            style = MaterialTheme.typography.titleMedium,
            color = if (row.isSelf) PomoTokens.colors.accent else MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SevenDayBars(row: CrewBoardRow) {
    val values = row.dailyAggregates.take(7).reversed().map { it.focusMinutes }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier
            .width(46.dp)
            .height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(7) { index ->
            val value = values.getOrElse(index) { 0 }
            val height = if (value == 0) 2.dp else (4 + 20 * value / max).dp
            val barColor = when {
                value == max && value > 0 -> PomoTokens.colors.accent
                value == 0 -> PomoTokens.colors.onSurfaceFaint
                else -> PomoTokens.colors.onSurface
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun MemberDetailSheet(
    row: CrewBoardRow,
    self: CrewBoardRow?,
    rankingMode: CrewRankingMode,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
) {
    val activeDays = row.dailyAggregates.count { it.focusMinutes > 0 }
    val average = row.dailyAggregates.filter { it.focusMinutes > 0 }
        .map { it.focusMinutes }
        .average()
        .takeUnless { it.isNaN() }
        ?.roundToInt()
        ?: 0
    val best = row.dailyAggregates.maxByOrNull { it.focusMinutes }
    PomoSheet(title = row.displayName, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(formatMinutes(row.thirtyDayFocusMinutes), "30 DAY", Modifier.weight(1f))
                StatTile(activeDays.toString(), "ACTIVE DAYS", Modifier.weight(1f))
                StatTile(formatMinutes(average), "ACTIVE AVG", Modifier.weight(1f))
            }
            DetailFact("Best day", best?.let { "${it.localDate} · ${formatMinutes(it.focusMinutes)}" } ?: "—")
            DetailFact("Work blocks", row.dailyAggregates.sumOf { it.completedWorkBlocks }.toString())
            DetailFact("Current streak", "${row.currentStreak}d")
            DetailFact("Identity", row.identityPublicKey.take(12).uppercase(Locale.ROOT))
            if (!row.isSelf && self != null) {
                DetailFact("${rankingMode.label} vs you", comparisonLabel(row.selectedFocusMinutes - self.selectedFocusMinutes))
                DetailFact("30 day vs you", comparisonLabel(row.thirtyDayFocusMinutes - self.thirtyDayFocusMinutes))
                DetailFact("Streak vs you", comparisonDaysLabel(row.currentStreak - self.currentStreak))
            }
            if (!row.isSelf) {
                PomoButton(onClick = onHide, variant = PomoButtonVariant.Ghost) { Text("Hide member locally") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DetailFact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = PomoTokens.colors.onSurfaceMuted)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}
