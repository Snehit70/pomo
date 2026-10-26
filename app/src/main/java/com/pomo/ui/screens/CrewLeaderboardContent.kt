package com.pomo.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.pomo.crew.CrewValidation
import com.pomo.ui.components.Avatar
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.PomoSheet
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.components.SegmentedToggle
import com.pomo.ui.components.SegmentedToggleOption
import com.pomo.ui.components.StatTile
import com.pomo.ui.theme.PomoMotion
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.isReducedMotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun CrewBoardContent(
    isSyncing: Boolean,
    board: CrewBoard,
    profileDisplayName: String,
    onCreateCrew: (String, String) -> Unit,
    onJoinCrew: (String, String) -> Unit,
    onSwitchCrew: (String) -> Unit,
    onLeaveCrew: (String) -> Unit,
    onRankingModeChange: (CrewRankingMode) -> Unit,
    onMemberHiddenChange: (String, Boolean) -> Unit,
    onExportRecovery: () -> Unit,
    onImportRecovery: () -> Unit,
    onOpenOwnStats: () -> Unit,
) {
    var showManage by remember { mutableStateOf(false) }
    var selectedMember by remember { mutableStateOf<CrewBoardRow?>(null) }
    var pendingHide by remember { mutableStateOf<CrewBoardRow?>(null) }
    var statsMember by remember { mutableStateOf<CrewBoardRow?>(null) }
    var showDayPicker by remember { mutableStateOf(false) }

    statsMember?.let { row ->
        CrewMemberStatsScreen(row = row, onBack = { statsMember = null })
        return
    }

    if (showManage) {
        ManageCrewScreen(
            board = board,
            profileDisplayName = profileDisplayName,
            onBack = { showManage = false },
            onCreateCrew = onCreateCrew,
            onReviewJoin = { joinCode -> onJoinCrew(joinCode, "") },
            onLeaveCrew = onLeaveCrew,
            onMemberHiddenChange = onMemberHiddenChange,
            onExportRecovery = onExportRecovery,
            onImportRecovery = onImportRecovery,
        )
        return
    }

    var search by remember(board.crewId) { mutableStateOf("") }
    var showInactive by remember(board.crewId) { mutableStateOf(false) }
    val reduceMotion = isReducedMotion()
    AnimatedContent(
        targetState = board,
        contentKey = { it.crewId },
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
            } else {
                (
                    fadeIn(tween(PomoMotion.DurationM, easing = PomoMotion.EaseOutExpo)) +
                        slideInHorizontally(
                            animationSpec = tween(PomoMotion.DurationM, easing = PomoMotion.EaseOutExpo),
                            initialOffsetX = { it / 24 },
                        )
                ) togetherWith (
                    fadeOut(tween(PomoMotion.DurationXs)) +
                        slideOutHorizontally(
                            animationSpec = tween(PomoMotion.DurationXs),
                            targetOffsetX = { -it / 40 },
                        )
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "crew-switch",
    ) { animatedBoard ->
        val activeRows = animatedBoard.rows.filterNot { it.isInactive }
        val inactiveRows = animatedBoard.rows.filter { it.isInactive }
        val duplicateNames =
            activeRows.groupingBy { it.displayName.trim().lowercase(Locale.ROOT) }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val visibleRows = activeRows.filter { row -> row.matchesSearch(search, duplicateNames) }
        val tiedRanks =
            activeRows.mapNotNull { it.rank }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        ) {
            item(key = "header") {
                CrewHeader(
                    board = animatedBoard,
                    isSyncing = isSyncing,
                    onManage = { showManage = true },
                    onPickDay = { showDayPicker = true },
                    onSwitchCrew = onSwitchCrew,
                )
                Spacer(Modifier.height(20.dp))
            }
            item(key = "window") {
                RankingWindowControl(animatedBoard.rankingMode, onRankingModeChange)
                (animatedBoard.rankingMode as? CrewRankingMode.Day)?.let { day ->
                    Spacer(Modifier.height(10.dp))
                    SelectedDayChip(
                        localDate = day.localDate,
                        onClear = { onRankingModeChange(CrewRankingMode.Today) },
                    )
                }
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                    )
                }
            }
            item(key = "leaderboard-heading") {
                SectionHeader("Leaderboard")
                Spacer(Modifier.height(8.dp))
                LeaderboardColumnHeader()
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
    }

    selectedMember?.let { row ->
        MemberDetailSheet(
            row = row,
            self = board.rows.firstOrNull { it.isSelf },
            rankingMode = board.rankingMode,
            onDismiss = { selectedMember = null },
            onViewStats = {
                // Your own stats live in Room at full fidelity. CrewMemberStatsScreen can only
                // rebuild them from the snapshot you published, which is lossy and can disagree
                // with the Stats tab, so send yourself to the real thing.
                if (row.isSelf) onOpenOwnStats() else statsMember = row
                selectedMember = null
            },
            onHide = { pendingHide = row },
        )
    }

    if (showDayPicker) {
        PickDayDialog(
            initialDate = (board.rankingMode as? CrewRankingMode.Day)?.localDate,
            onDismiss = { showDayPicker = false },
            onPick = { localDate ->
                onRankingModeChange(CrewRankingMode.Day(localDate))
                showDayPicker = false
            },
        )
    }

    pendingHide?.let { row ->
        HideMemberConfirmDialog(
            memberName = row.displayName,
            onConfirm = {
                onMemberHiddenChange(row.identityPublicKey, true)
                pendingHide = null
                selectedMember = null
            },
            onDismiss = { pendingHide = null },
        )
    }
}

@Composable
private fun CrewHeader(
    board: CrewBoard,
    isSyncing: Boolean,
    onManage: () -> Unit,
    onPickDay: () -> Unit,
    onSwitchCrew: (String) -> Unit,
) {
    var switchMenuExpanded by remember(board.crewId) { mutableStateOf(false) }
    val switchableCrews = board.memberships.filterNot { it.isArchived }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = board.crewName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (switchableCrews.size > 1) {
                    Box {
                        IconButton(
                            onClick = { switchMenuExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ExpandMore,
                                contentDescription = "Switch Crew",
                                tint = PomoTokens.colors.accent,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = switchMenuExpanded,
                            onDismissRequest = { switchMenuExpanded = false },
                        ) {
                            switchableCrews.forEach { crew ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = crew.crewName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    onClick = {
                                        switchMenuExpanded = false
                                        if (!crew.isActive) onSwitchCrew(crew.crewId)
                                    },
                                    enabled = !crew.isActive,
                                    trailingIcon = {
                                        if (crew.isActive) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "Current Crew",
                                                tint = PomoTokens.colors.accent,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = freshnessLabel(board, isSyncing),
                style = MaterialTheme.typography.labelSmall,
                color = freshnessColor(board, isSyncing),
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(
            onClick = onPickDay,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, PomoTokens.colors.outline, RoundedCornerShape(8.dp)),
        ) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = "Pick a day",
                tint = PomoTokens.colors.onSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onManage,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, PomoTokens.colors.outline, RoundedCornerShape(8.dp)),
        ) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = "Manage Crew",
                tint = PomoTokens.colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RankingWindowControl(
    mode: CrewRankingMode,
    onChange: (CrewRankingMode) -> Unit,
) {
    SegmentedToggle(
        options =
            listOf(
                SegmentedToggleOption(TOGGLE_TODAY, "Today", weight = 1.1f),
                SegmentedToggleOption(TOGGLE_YESTERDAY, "Yesterday", weight = 1.5f),
                SegmentedToggleOption(TOGGLE_SEVEN_DAYS, "7D", weight = 0.8f),
                SegmentedToggleOption(TOGGLE_THIRTY_DAYS, "30D", weight = 0.9f),
                SegmentedToggleOption(TOGGLE_ALL_TIME, "All", weight = 0.8f),
            ),
        // A picked day matches no option, so the row shows nothing selected and the
        // day chip below carries the current window instead.
        selectedValue = mode.toggleKey(),
        onSelectedValueChange = { key ->
            onChange(
                when (key) {
                    TOGGLE_YESTERDAY -> CrewRankingMode.Yesterday
                    TOGGLE_SEVEN_DAYS -> CrewRankingMode.SevenDays
                    TOGGLE_THIRTY_DAYS -> CrewRankingMode.ThirtyDays
                    TOGGLE_ALL_TIME -> CrewRankingMode.AllTime
                    else -> CrewRankingMode.Today
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SelectedDayChip(
    localDate: String,
    onClear: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(PomoTokens.colors.accent.copy(alpha = 0.14f))
                .clickable(onClick = onClear)
                .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDayLabel(localDate),
            style = MaterialTheme.typography.labelLarge,
            color = PomoTokens.colors.accent,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.Close,
            contentDescription = "Clear selected day",
            tint = PomoTokens.colors.accent,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun CrewRankingMode.toggleKey(): String =
    when (this) {
        CrewRankingMode.Today -> TOGGLE_TODAY
        CrewRankingMode.Yesterday -> TOGGLE_YESTERDAY
        CrewRankingMode.SevenDays -> TOGGLE_SEVEN_DAYS
        CrewRankingMode.ThirtyDays -> TOGGLE_THIRTY_DAYS
        CrewRankingMode.AllTime -> TOGGLE_ALL_TIME
        is CrewRankingMode.Day -> TOGGLE_DAY
    }

private const val TOGGLE_TODAY = "TODAY"
private const val TOGGLE_YESTERDAY = "YESTERDAY"
private const val TOGGLE_SEVEN_DAYS = "SEVEN_DAYS"
private const val TOGGLE_THIRTY_DAYS = "THIRTY_DAYS"
private const val TOGGLE_ALL_TIME = "ALL_TIME"
private const val TOGGLE_DAY = "DAY"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDayDialog(
    initialDate: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val today = remember { LocalDate.now() }
    // Snapshots only carry MAX_DAILY_AGGREGATES days of history, so earlier days would
    // rank everyone at zero. Don't offer them.
    val earliest =
        remember(today) {
            today.minusDays((CrewValidation.MAX_DAILY_AGGREGATES - 1).toLong())
        }
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = (initialDate?.toLocalDateOrNull() ?: today).toUtcMillis(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val date = utcTimeMillis.toUtcLocalDate()
                        return !date.isBefore(earliest) && !date.isAfter(today)
                    }

                    override fun isSelectableYear(year: Int): Boolean = year in earliest.year..today.year
                },
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(it.toUtcLocalDate().toString()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text("Show") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun CrewSummary(rows: List<CrewBoardRow>) {
    val participating = rows.filter { it.selectedFocusMinutes > 0 }
    val total = participating.sumOf { it.selectedFocusMinutes }
    val median = participating.map { it.selectedFocusMinutes }.sorted().median()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, PomoTokens.colors.outline, RoundedCornerShape(10.dp))
                .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatTile(formatMinutes(total), "CREW FOCUS", Modifier.weight(1f).padding(start = 14.dp))
        Box(Modifier.width(1.dp).height(42.dp).background(PomoTokens.colors.outline))
        StatTile(participating.size.toString(), "ACTIVE", Modifier.weight(1f).padding(start = 14.dp))
        Box(Modifier.width(1.dp).height(42.dp).background(PomoTokens.colors.outline))
        StatTile(formatMinutes(median), "MEDIAN", Modifier.weight(1f).padding(start = 14.dp))
    }
}

@Composable
private fun YourStanding(
    rows: List<CrewBoardRow>,
    tiedRanks: Set<Int>,
) {
    val self = rows.firstOrNull { it.isSelf } ?: return
    val context = standingContext(self, rows)
    val accent = PomoTokens.colors.accent
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PomoTokens.colors.surfaceElevated)
                .border(1.dp, PomoTokens.colors.outline, RoundedCornerShape(12.dp))
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
        CrewPositionScale(rows)
    }
}

@Composable
private fun CrewPositionScale(rows: List<CrewBoardRow>) {
    val ranked = rows.filter { it.rank != null && it.selectedFocusMinutes > 0 }
    if (ranked.size < 2) return

    val maxMinutes = ranked.maxOf { it.selectedFocusMinutes }.toLong()
    val outline = PomoTokens.colors.outlineStrong
    val muted = PomoTokens.colors.onSurfaceMuted
    val accent = PomoTokens.colors.accent
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val description =
        ranked.joinToString(prefix = "Crew positions. ", separator = ". ") { row ->
            "rank ${row.rank}, ${row.displayName}, ${formatMinutes(row.selectedFocusMinutes)}"
        }

    Spacer(Modifier.height(12.dp))
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = description },
    ) {
        val lineInset = 4.dp
        val lineWidth = maxWidth - lineInset * 2
        val labelWidth = 32.dp * fontScale
        val rankBandHeight = 18.dp * fontScale
        val lineY = rankBandHeight + 8.dp
        val tickLabelY = lineY + 10.dp
        val chartHeight = tickLabelY + 16.dp * fontScale
        val maxIntervals = (lineWidth / labelWidth).toInt().coerceIn(2, 8)
        val stepMinutes = positionScaleStepMinutes(maxMinutes, maxIntervals)
        val axisMaxMinutes = ((maxMinutes + stepMinutes - 1L) / stepMinutes) * stepMinutes
        val ticks = generateSequence(0L) { previous -> (previous + stepMinutes).takeIf { it <= axisMaxMinutes } }.toList()
        val markers =
            ranked
                .groupBy { it.rank }
                .values
                .map { tied -> tied.firstOrNull { it.isSelf } ?: tied.first() }
                .sortedBy { it.selectedFocusMinutes }
        val minimumLabelGap = labelWidth / lineWidth
        val labeledRanks = readableMarkerRanks(markers, axisMaxMinutes, minimumLabelGap)

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(chartHeight),
        ) {
            val startX = lineInset.toPx()
            val endX = size.width - lineInset.toPx()
            val lineYPx = lineY.toPx()
            drawLine(outline, Offset(startX, lineYPx), Offset(endX, lineYPx), strokeWidth = 1.dp.toPx())
            ticks.forEach { tick ->
                val x = startX + (endX - startX) * tick / axisMaxMinutes.toFloat()
                drawLine(
                    outline,
                    Offset(x, lineYPx - 3.dp.toPx()),
                    Offset(x, lineYPx + 3.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        ticks.forEach { tick ->
            val ratio = tick / axisMaxMinutes.toFloat()
            Text(
                text = positionScaleTickLabel(tick),
                modifier =
                    Modifier
                        .width(labelWidth)
                        .offset(
                            x = (lineInset + lineWidth * ratio - labelWidth / 2).coerceIn(0.dp, maxWidth - labelWidth),
                            y = tickLabelY,
                        ),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
        markers.forEach { row ->
            val ratio = row.selectedFocusMinutes / axisMaxMinutes.toFloat()
            val markerColor = if (row.isSelf) accent else MaterialTheme.colorScheme.onSurface
            if (row.rank in labeledRanks) {
                Text(
                    text = "#${row.rank}",
                    modifier =
                        Modifier
                            .width(labelWidth)
                            .offset(
                                x = (lineInset + lineWidth * ratio - labelWidth / 2).coerceIn(0.dp, maxWidth - labelWidth),
                                y = 0.dp,
                            ),
                    style = MaterialTheme.typography.labelSmall,
                    color = markerColor,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .offset(
                            x = (lineInset + lineWidth * ratio - 4.dp).coerceIn(0.dp, maxWidth - 8.dp),
                            y = lineY - 4.dp,
                        )
                        .clip(CircleShape)
                        .background(markerColor),
            )
        }
    }
}

private fun positionScaleStepMinutes(
    maxMinutes: Long,
    maxIntervals: Int,
): Long {
    val targetHours = kotlin.math.ceil(maxMinutes / 60.0 / maxIntervals).toLong().coerceAtLeast(1L)
    var magnitude = 1L
    while (targetHours > magnitude * 10L) magnitude *= 10L
    val stepHours =
        when {
            targetHours <= magnitude -> magnitude
            targetHours <= magnitude * 2L -> magnitude * 2L
            targetHours <= magnitude * 5L -> magnitude * 5L
            else -> magnitude * 10L
        }
    return stepHours * 60L
}

private fun readableMarkerRanks(
    rows: List<CrewBoardRow>,
    axisMaxMinutes: Long,
    minimumGap: Float,
): Set<Int?> {
    val required = rows.filter { it.rank == 1 || it.isSelf }.mapTo(mutableSetOf()) { it.rank }
    var lastLabeledRatio = Float.NEGATIVE_INFINITY
    rows.forEach { row ->
        val ratio = row.selectedFocusMinutes / axisMaxMinutes.toFloat()
        if (row.rank in required || ratio - lastLabeledRatio >= minimumGap) {
            required += row.rank
            lastLabeledRatio = ratio
        }
    }
    return required
}

private fun positionScaleTickLabel(minutes: Long): String {
    if (minutes == 0L) return "0"
    val hours = minutes / 60L
    return when {
        hours < 1_000L -> "${hours}h"
        hours < 1_000_000L -> "${hours / 1_000L}kh"
        else -> "${hours / 1_000_000L}Mh"
    }
}

@Composable
private fun LeaderboardColumnHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "POS",
            modifier = Modifier.width(42.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
        Text(
            "MEMBER",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
        Text(
            "TREND",
            modifier = Modifier.width(46.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "FOCUS",
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun CrewRow(
    row: CrewBoardRow,
    showFingerprint: Boolean,
    isTied: Boolean,
    onClick: () -> Unit,
) {
    val rankLabel = row.rank?.let { if (isTied) "=$it" else "#$it" } ?: "—"
    val displayLabel =
        if (showFingerprint) {
            "${row.displayName} · ${row.identityPublicKey.take(4).uppercase(Locale.ROOT)}"
        } else {
            row.displayName
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (row.isSelf) PomoTokens.colors.surfaceElevated else MaterialTheme.colorScheme.background)
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
        Avatar(
            avatarBase64 = row.avatarBase64,
            displayName = row.displayName,
            size = 34.dp,
        )
        Spacer(Modifier.width(10.dp))
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
        modifier =
            Modifier
                .width(46.dp)
                .height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(7) { index ->
            val value = values.getOrElse(index) { 0 }
            val height = if (value == 0) 2.dp else (4 + 20 * value / max).dp
            val barColor =
                when {
                    value == max && value > 0 -> PomoTokens.colors.accent
                    value == 0 -> PomoTokens.colors.onSurfaceFaint
                    else -> PomoTokens.colors.onSurface
                }
            Box(
                modifier =
                    Modifier
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
    onViewStats: () -> Unit,
    onHide: () -> Unit,
) {
    var showAvatarPreview by remember { mutableStateOf(false) }
    val activeDays = row.dailyAggregates.count { it.completedWorkBlocks > 0 }
    val blocks = row.dailyAggregates.sumOf { it.completedWorkBlocks }
    PomoSheet(title = row.displayName, onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            MemberIdentityStrip(row, onAvatarTap = {
                if (row.avatarBase64 != null) showAvatarPreview = true
            })
            MemberHistoryBars(row)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(formatMinutes(row.thirtyDayFocusMinutes), "30 DAY", Modifier.weight(1f))
                StatTile(activeDays.toString(), "ACTIVE DAYS", Modifier.weight(1f))
                StatTile(blocks.toString(), "BLOCKS", Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PomoButton(onClick = onViewStats, modifier = Modifier.weight(1f)) { Text("View stats") }
                if (!row.isSelf) {
                    PomoButton(onClick = onHide, variant = PomoButtonVariant.Ghost) { Text("Hide") }
                }
            }
            if (!row.isSelf && self != null) {
                MemberComparisons(row, self, rankingMode)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAvatarPreview && row.avatarBase64 != null) {
        AvatarPreviewSheet(
            avatarBase64 = row.avatarBase64,
            displayName = row.displayName,
            onDismiss = { showAvatarPreview = false },
        )
    }
}

@Composable
private fun MemberIdentityStrip(
    row: CrewBoardRow,
    onAvatarTap: () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            avatarBase64 = row.avatarBase64,
            displayName = row.displayName,
            size = 42.dp,
            modifier = Modifier.clickable(onClick = onAvatarTap),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.rank?.let { "#$it" } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                color = PomoTokens.colors.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${row.currentStreak}d streak · ${row.identityPublicKey.take(4).uppercase(Locale.ROOT)}",
                style = MaterialTheme.typography.labelSmall,
                color = PomoTokens.colors.onSurfaceFaint,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = formatMinutes(row.selectedFocusMinutes),
            style = MaterialTheme.typography.headlineSmall,
            color = PomoTokens.colors.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MemberHistoryBars(row: CrewBoardRow) {
    val days = row.dailyAggregates.take(HISTORY_BAR_DAYS).reversed()
    if (days.isEmpty()) return
    val max = days.maxOf { it.focusMinutes }.coerceAtLeast(1)
    val best = row.dailyAggregates.maxByOrNull { it.focusMinutes }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "LAST ${days.size} DAYS",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = PomoTokens.colors.onSurfaceFaint,
            )
            best?.let {
                Text(
                    text = "BEST ${formatMinutes(it.focusMinutes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PomoTokens.colors.onSurfaceFaint,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { day ->
                val fraction = day.focusMinutes.toFloat() / max
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction.coerceAtLeast(MIN_BAR_FRACTION))
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                when {
                                    day.focusMinutes == 0 -> PomoTokens.colors.outline
                                    day.focusMinutes == max -> PomoTokens.colors.accent
                                    else -> PomoTokens.colors.outlineStrong
                                },
                            ),
                )
            }
        }
    }
}

/**
 * Renders each "vs you" gap as a bar growing from centre — right in accent when they are
 * ahead, left in grey when you are — so the sign is legible without reading the number.
 */
@Composable
private fun MemberComparisons(
    row: CrewBoardRow,
    self: CrewBoardRow,
    rankingMode: CrewRankingMode,
) {
    val minuteScale =
        maxOf(
            abs(row.selectedFocusMinutes - self.selectedFocusMinutes),
            abs(row.thirtyDayFocusMinutes - self.thirtyDayFocusMinutes),
            1,
        )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ComparisonBar(
            label = rankingMode.label,
            deltaMinutes = row.selectedFocusMinutes - self.selectedFocusMinutes,
            fraction = abs(row.selectedFocusMinutes - self.selectedFocusMinutes).toFloat() / minuteScale,
            value = comparisonLabel(row.selectedFocusMinutes - self.selectedFocusMinutes),
        )
        ComparisonBar(
            label = "30 day",
            deltaMinutes = row.thirtyDayFocusMinutes - self.thirtyDayFocusMinutes,
            fraction = abs(row.thirtyDayFocusMinutes - self.thirtyDayFocusMinutes).toFloat() / minuteScale,
            value = comparisonLabel(row.thirtyDayFocusMinutes - self.thirtyDayFocusMinutes),
        )
        ComparisonBar(
            label = "Streak",
            deltaMinutes = row.currentStreak - self.currentStreak,
            fraction =
                abs(row.currentStreak - self.currentStreak).toFloat() /
                    maxOf(abs(row.currentStreak - self.currentStreak), 1),
            value = comparisonDaysLabel(row.currentStreak - self.currentStreak),
        )
    }
}

@Composable
private fun ComparisonBar(
    label: String,
    deltaMinutes: Int,
    fraction: Float,
    value: String,
) {
    val ahead = deltaMinutes > 0
    val accent = PomoTokens.colors.accent
    val track = PomoTokens.colors.surfaceElevated
    val neutral = PomoTokens.colors.outlineStrong
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(Locale.ROOT),
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .drawBehind {
                        drawRect(track)
                        val half = size.width / 2f
                        val width = half * fraction.coerceIn(0f, 1f)
                        if (deltaMinutes != 0) {
                            drawRect(
                                color = if (ahead) accent else neutral,
                                topLeft = Offset(if (ahead) half else half - width, 0f),
                                size = Size(width, size.height),
                            )
                        }
                        drawRect(
                            color = neutral,
                            topLeft = Offset(half - 0.5f, 0f),
                            size = Size(1f, size.height),
                        )
                    },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (ahead) accent else PomoTokens.colors.onSurfaceMuted,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
    }
}

private const val HISTORY_BAR_DAYS = 30
private const val MIN_BAR_FRACTION = 0.04f
