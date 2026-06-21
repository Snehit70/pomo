package com.pomo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pomo.stats.HourRhythm
import com.pomo.stats.RhythmPattern
import com.pomo.ui.HistoryItem
import com.pomo.ui.components.EmptyState
import com.pomo.ui.components.HourBarChart24
import com.pomo.ui.components.PomoSheet
import com.pomo.ui.components.StatTile
import com.pomo.ui.components.rhythmCaption
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.TimerTextStyle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun HistoryScreen(
    items: List<HistoryItem>,
    loadRhythm: suspend (String) -> HourRhythm = { emptyRhythm() },
) {
    val grouped = remember(items) { groupByMonth(items) }
    var selected by remember { mutableStateOf<HistoryItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "History",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    headline = "No history yet",
                    body = "Finish a focus session and it will show up here.",
                    icon = Icons.Outlined.History,
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            grouped.forEach { (monthLabel, entries) ->
                stickyHeader(key = "header_$monthLabel") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        Spacer(Modifier.height(20.dp))
                        MonthHeader(monthLabel, entries)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(entries, key = { it.date }) { entry ->
                    HistoryRow(entry, onClick = { selected = entry })
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        thickness = 1.dp,
                    )
                }
            }
        }
    }

    selected?.let { item ->
        DayDetailSheet(item = item, loadRhythm = loadRhythm, onDismiss = { selected = null })
    }
}

@Composable
private fun MonthHeader(label: String, entries: List<HistoryItem>) {
    val focusMinutes = entries.sumOf { it.entry.work_minutes }
    val blocks = entries.sumOf { it.entry.completed }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceMuted,
        )
        Text(
            "${formatMinutes(focusMinutes)} · $blocks blocks",
            style = MaterialTheme.typography.labelSmall,
            color = PomoTokens.colors.onSurfaceFaint,
        )
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    val displayDate = remember(item.date) { formatDate(item.date) }
    val blocks = item.entry.completed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                displayDate,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (blocks == 1) "1 block" else "$blocks blocks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatMinutes(item.entry.work_minutes),
            style = TimerTextStyle.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DayDetailSheet(
    item: HistoryItem,
    loadRhythm: suspend (String) -> HourRhythm,
    onDismiss: () -> Unit,
) {
    val rhythm by produceState(initialValue = emptyRhythm(), item.date) {
        value = runCatching { loadRhythm(item.date) }.getOrElse { emptyRhythm() }
    }
    PomoSheet(title = formatFullDate(item.date), onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(formatMinutes(item.entry.work_minutes), "FOCUS", Modifier.weight(1f))
                StatTile(item.entry.completed.toString(), "BLOCKS", Modifier.weight(1f))
                StatTile(formatMinutes(item.entry.break_minutes), "BREAK", Modifier.weight(1f))
            }
            Column {
                HourBarChart24(rhythm)
                Spacer(Modifier.height(8.dp))
                Text(
                    rhythmCaption(rhythm),
                    style = MaterialTheme.typography.bodySmall,
                    color = PomoTokens.colors.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun emptyRhythm(): HourRhythm = HourRhythm(IntArray(24), null, RhythmPattern.None)

private fun groupByMonth(items: List<HistoryItem>): List<Pair<String, List<HistoryItem>>> {
    if (items.isEmpty()) return emptyList()
    val sorted = items.sortedByDescending { it.date }
    val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.US)
    val out = mutableListOf<Pair<String, MutableList<HistoryItem>>>()
    sorted.forEach { item ->
        val date = parse.parse(item.date) ?: return@forEach
        val cal = Calendar.getInstance().apply { time = date }
        val key = monthFmt.format(cal.time)
        if (out.isEmpty() || out.last().first != key) {
            out += key to mutableListOf(item)
        } else {
            out.last().second += item
        }
    }
    return out.map { it.first to it.second.toList() }
}

private fun formatDate(iso: String): String = try {
    val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val output = SimpleDateFormat("EEE, MMM d", Locale.US)
    input.parse(iso)?.let { output.format(it) } ?: iso
} catch (_: Exception) {
    iso
}

private fun formatFullDate(iso: String): String = try {
    val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val output = SimpleDateFormat("EEEE, MMMM d", Locale.US)
    input.parse(iso)?.let { output.format(it) } ?: iso
} catch (_: Exception) {
    iso
}
