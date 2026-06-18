package com.pomo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.component.shape.shader.verticalGradient
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.values.ChartValues
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.pomo.stats.BestDay
import com.pomo.stats.BestWeek
import com.pomo.stats.ChartTrend
import com.pomo.stats.HabitWindow
import com.pomo.stats.HeatCell
import com.pomo.stats.Lifetime
import com.pomo.stats.Records
import com.pomo.stats.StatsSnapshot
import com.pomo.stats.WeekShape
import com.pomo.ui.components.EmptyState
import com.pomo.ui.components.HourBarChart24
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.components.rhythmCaption
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.TimerTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
public fun StatsScreen(
    snapshot: StatsSnapshot,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Statistics",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "Share statistics screenshot",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onExport) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "Export statistics",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (snapshot.isEmpty) {
            Spacer(Modifier.height(24.dp))
            EmptyState(
                headline = "No sessions yet",
                body = "Finish a focus session and the stats will start to fill in here.",
                icon = Icons.Outlined.QueryStats,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader("Trend")
        Spacer(Modifier.height(14.dp))
        PerDayLineChart(snapshot.chartTrend)

        Spacer(Modifier.height(24.dp))
        TodayWeekStrip(snapshot.habit)

        Spacer(Modifier.height(24.dp))
        LifetimeHeroBlock(snapshot.lifetime)

        Spacer(Modifier.height(32.dp))
        SectionHeader("When you focus")
        Spacer(Modifier.height(14.dp))
        HourBarChart24(snapshot.rhythm)
        Spacer(Modifier.height(10.dp))
        Text(
            text = rhythmCaption(snapshot.rhythm),
            style = MaterialTheme.typography.bodyMedium,
            color = PomoTokens.colors.onSurfaceMuted,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(36.dp))
        SectionHeader("Consistency")
        Spacer(Modifier.height(14.dp))
        HabitHeatmap(snapshot.habit)
        Spacer(Modifier.height(14.dp))
        HabitFooterFacts(snapshot)

        Spacer(Modifier.height(36.dp))
        SectionHeader("Which days")
        Spacer(Modifier.height(14.dp))
        WeekShapeStrip(snapshot.weekShape)

        Spacer(Modifier.height(36.dp))
        SectionHeader("Records")
        Spacer(Modifier.height(12.dp))
        RecordsList(snapshot.records)

        Spacer(Modifier.height(28.dp))
        SinceFooter(snapshot.lifetime.firstDate)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun LifetimeHeroBlock(lifetime: Lifetime) {
    val hours = lifetime.focusMinutes / 60
    val mins = lifetime.focusMinutes % 60
    val hero = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

    Column {
        Text(
            text = hero,
            style = TimerTextStyle.copy(fontSize = 56.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        val sub = buildString {
            append(lifetime.sessions)
            append(if (lifetime.sessions == 1) " session" else " sessions")
            append("  ·  ")
            append(lifetime.daysWithApp)
            append(if (lifetime.daysWithApp == 1) " day with Pomo" else " days with Pomo")
        }
        Text(
            text = sub,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HabitHeatmap(habit: HabitWindow) {
    val cell = 14.dp
    val gap = 4.dp
    // Heatmap is monochrome by design — intensity, not hue. Signal red is reserved for
    // live state and peak bars elsewhere.
    val focus = PomoTokens.colors.onSurface
    val empty = PomoTokens.colors.outline

    // Cells are in chronological order, week-by-week from a Sunday-aligned start.
    val weeks = habit.weeks
    Canvas(
        modifier = Modifier.size(
            width = (cell + gap) * weeks + gap,
            height = (cell + gap) * 7 + gap,
        ),
    ) {
        val cellPx = cell.toPx()
        val gapPx = gap.toPx()
        habit.cells.forEachIndexed { index, c ->
            val w = index / 7
            val d = index % 7
            val color = colorFor(c.minutes, c.sessions, focus, empty)
            val left = gapPx + w * (cellPx + gapPx)
            val top = gapPx + d * (cellPx + gapPx)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(cellPx, cellPx),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    HeatmapLegend(focus = focus, empty = empty)
}

private fun colorFor(minutes: Int, sessions: Int, focus: Color, empty: Color): Color = when {
    sessions == 0 -> empty
    minutes < 30 -> focus.copy(alpha = 0.30f)
    minutes < 60 -> focus.copy(alpha = 0.55f)
    minutes < 120 -> focus.copy(alpha = 0.80f)
    else -> focus
}

@Composable
private fun HeatmapLegend(focus: Color, empty: Color) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "less",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = muted,
        )
        Spacer(Modifier.width(8.dp))
        listOf(empty, focus.copy(alpha = 0.30f), focus.copy(alpha = 0.55f), focus.copy(alpha = 0.80f), focus).forEach { c ->
            Canvas(modifier = Modifier.size(10.dp)) {
                drawRoundRect(
                    color = c,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "more",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = muted,
        )
    }
}

@Composable
private fun HabitFooterFacts(snapshot: StatsSnapshot) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val current = snapshot.habit.currentStreak
    val best = snapshot.habit.bestStreak
    val streakLine = buildString {
        append("current streak ")
        append(current)
        append(if (current == 1) " day" else " days")
        if (best > 0) {
            append("  ·  best ")
            append(best)
        }
    }
    val goal = snapshot.goal
    val goalLine = if (goal.dailyGoal > 0) {
        "goal hit ${goal.daysHit} of last ${goal.totalDays} days"
    } else null

    Text(streakLine, style = MaterialTheme.typography.bodyMedium, color = muted)
    if (goalLine != null) {
        Spacer(Modifier.height(4.dp))
        Text(goalLine, style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

@Composable
private fun WeekShapeStrip(week: WeekShape) {
    val signal = PomoTokens.colors.accent
    val bar = PomoTokens.colors.onSurfaceMuted
    val empty = PomoTokens.colors.outline
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    val max = (week.buckets.maxOrNull() ?: 0).coerceAtLeast(1)
    val muted = PomoTokens.colors.onSurfaceMuted

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (i in 0 until 7) {
                val v = week.buckets[i]
                val frac = (v.toFloat() / max).coerceIn(0f, 1f)
                val isPeak = week.strongestDayIndex == i && v > 0
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((4 + (frac * 84)).dp)
                            .background(
                                if (v > 0) (if (isPeak) signal else bar) else empty,
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            labels.forEachIndexed { i, l ->
                Text(
                    text = l,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = if (week.strongestDayIndex == i) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        val caption = weekCaption(week)
        if (caption != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun weekCaption(week: WeekShape): String? {
    val idx = week.strongestDayIndex ?: return null
    val day = when (idx) {
        0 -> "Mondays"; 1 -> "Tuesdays"; 2 -> "Wednesdays"; 3 -> "Thursdays"
        4 -> "Fridays"; 5 -> "Saturdays"; else -> "Sundays"
    }
    return "$day are your strongest day"
}

@Composable
private fun RecordsList(records: Records) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RecordRow(label = "best day", value = records.bestDay?.let { formatBestDay(it) } ?: "—")
        RecordRow(label = "best week", value = records.bestWeek?.let { formatBestWeek(it) } ?: "—")
        RecordRow(
            label = "longest streak",
            value = if (records.longestStreak > 0) {
                "${records.longestStreak} day" + if (records.longestStreak == 1) "" else "s"
            } else "—",
        )
    }
}

@Composable
private fun RecordRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatBestDay(d: BestDay): String {
    val pretty = formatPrettyDate(d.date)
    val n = d.sessions
    return "$n session${if (n == 1) "" else "s"}  ·  $pretty"
}

private fun formatBestWeek(w: BestWeek): String {
    val pretty = formatPrettyDate(w.weekStart)
    val n = w.sessions
    return "$n session${if (n == 1) "" else "s"}  ·  wk of $pretty"
}

private fun formatPrettyDate(iso: String): String = try {
    val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val output = SimpleDateFormat("MMM d", Locale.US)
    input.parse(iso)?.let { output.format(it) } ?: iso
} catch (_: Exception) {
    iso
}

@Composable
private fun SinceFooter(firstDate: String?) {
    if (firstDate == null) return
    val text = "since " + formatSinceDate(firstDate)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun formatSinceDate(iso: String): String = try {
    val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val output = SimpleDateFormat("MMM d, yyyy", Locale.US)
    input.parse(iso)?.let { output.format(it) } ?: iso
} catch (_: Exception) {
    iso
}

private enum class TrendRange(val label: String) {
    Today("1D"), Days7("7D"), Days28("28D"), AllTime("ALL"),
}

@Composable
private fun TrendRangePills(selected: TrendRange, onSelect: (TrendRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrendRange.entries.forEach { r ->
            val active = r == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) PomoTokens.colors.accent else Color.Transparent)
                    .border(1.dp, if (active) PomoTokens.colors.accent else PomoTokens.colors.outline, RoundedCornerShape(4.dp))
                    .clickable { onSelect(r) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = r.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = if (active) MaterialTheme.colorScheme.background else PomoTokens.colors.onSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun PerDayLineChart(trend: ChartTrend) {
    var range by remember { mutableStateOf(TrendRange.Days7) }
    val series = when (range) {
        TrendRange.Today -> trend.today
        TrendRange.Days7 -> trend.week
        TrendRange.Days28 -> trend.month
        TrendRange.AllTime -> trend.allTime
    }
    val points = series.points
    if (points.isEmpty()) return

    val accent = PomoTokens.colors.accent
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val modelProducer = remember { ChartEntryModelProducer() }
    var chartReady by remember(points) { mutableStateOf(false) }

    LaunchedEffect(points) {
        chartReady = false
        modelProducer.setEntries(points.mapIndexed { i, p -> FloatEntry(i.toFloat(), p.value) })
        chartReady = true
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TrendRangePills(selected = range, onSelect = { range = it })
    }
    Spacer(Modifier.height(10.dp))

    val areaShader = verticalGradient(arrayOf(accent.copy(alpha = 0.25f), Color.Transparent))
    val spec = lineSpec(lineColor = accent, lineThickness = 2.dp, lineBackgroundShader = areaShader)

    val labels = points.map { it.label }
    val labelFormatter = remember(labels) {
        object : AxisValueFormatter<AxisPosition.Horizontal.Bottom> {
            override fun formatValue(value: Float, chartValues: ChartValues): CharSequence =
                labels.getOrElse(value.toInt()) { "" }
        }
    }

    if (chartReady) {
        Chart(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            chart = lineChart(lines = listOf(spec)),
            chartModelProducer = modelProducer,
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(valueFormatter = labelFormatter),
        )
    } else {
        Spacer(Modifier.fillMaxWidth().height(160.dp))
    }

    Spacer(Modifier.height(8.dp))
    val maxVal = points.maxOfOrNull { it.value.toInt() }?.coerceAtLeast(1) ?: 1
    val rangeDesc = when (range) {
        TrendRange.Today -> "today by hour"
        TrendRange.Days7 -> "last 7 days"
        TrendRange.Days28 -> "last 4 weeks"
        TrendRange.AllTime -> "all time by month"
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rangeDesc, style = MaterialTheme.typography.labelSmall, color = muted)
        Text("max ${maxVal}m", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

private fun nowFormatted(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private data class Kpi(val minutes: Int, val sessions: Int)

private fun computeKpis(habit: HabitWindow): Triple<Kpi, Kpi, Kpi> {
    val today = nowFormatted()
    val thisMonthPrefix = today.substring(0, 7)
    val cells: List<HeatCell> = habit.cells
    val todayCell = cells.lastOrNull { it.date == today }
    val todayKpi = Kpi(todayCell?.minutes ?: 0, todayCell?.sessions ?: 0)

    val todayIdx = cells.indexOfLast { it.date == today }
    val weekSlice = if (todayIdx >= 0) {
        cells.subList((todayIdx - 6).coerceAtLeast(0), todayIdx + 1)
    } else {
        cells.takeLast(7)
    }
    val weekKpi = Kpi(minutes = weekSlice.sumOf { it.minutes }, sessions = weekSlice.sumOf { it.sessions })

    val monthCells = cells.filter { it.date.startsWith(thisMonthPrefix) }
    val monthKpi = Kpi(minutes = monthCells.sumOf { it.minutes }, sessions = monthCells.sumOf { it.sessions })

    return Triple(todayKpi, weekKpi, monthKpi)
}

private fun computeWeekDelta(habit: HabitWindow): Int? {
    val today = nowFormatted()
    val cells = habit.cells
    val todayIdx = cells.indexOfLast { it.date == today }
    if (todayIdx < 13) return null
    val thisWeek = cells.subList((todayIdx - 6).coerceAtLeast(0), todayIdx + 1).sumOf { it.sessions }
    val lastWeek = cells.subList((todayIdx - 13).coerceAtLeast(0), (todayIdx - 6).coerceAtLeast(0)).sumOf { it.sessions }
    if (lastWeek == 0) return null
    return ((thisWeek - lastWeek) * 100 / lastWeek)
}

@Composable
private fun TodayWeekStrip(habit: HabitWindow) {
    val (today, week, month) = computeKpis(habit)
    val weekDelta = computeWeekDelta(habit)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KpiCell(label = "TODAY", kpi = today, modifier = Modifier.weight(1f))
        KpiCell(label = "THIS WEEK", kpi = week, delta = weekDelta, modifier = Modifier.weight(1f))
        KpiCell(label = "THIS MONTH", kpi = month, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun KpiCell(label: String, kpi: Kpi, modifier: Modifier = Modifier, delta: Int? = null) {
    val hours = kpi.minutes / 60
    val mins = kpi.minutes % 60
    val value = when {
        kpi.minutes == 0 -> "0m"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
    val sessionSub = "${kpi.sessions} " + if (kpi.sessions == 1) "session" else "sessions"
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.18f.em),
            color = PomoTokens.colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = TimerTextStyle.copy(fontSize = 28.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = sessionSub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (delta != null) {
            val sign = if (delta >= 0) "↑" else "↓"
            val deltaColor = if (delta >= 0) PomoTokens.colors.accent else PomoTokens.colors.onSurfaceMuted
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$sign ${kotlin.math.abs(delta)}% vs last wk",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = deltaColor,
            )
        }
    }
}
