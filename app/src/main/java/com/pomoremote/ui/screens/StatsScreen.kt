package com.pomoremote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pomoremote.db.SessionEntity
import com.pomoremote.ui.DayEntry
import com.pomoremote.ui.theme.Gold
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

public enum class GraphType { Bar, Line }
public enum class PeriodType { Week, Month }

@Composable
public fun StatsScreen(
    history: Map<String, DayEntry>,
    todaySessions: List<SessionEntity>,
    dailyGoal: Int,
    dayStartHour: Int,
    onExport: () -> Unit,
) {
    val scroll = rememberScrollState()
    var graphType by remember { mutableStateOf(GraphType.Bar) }
    var periodType by remember { mutableStateOf(PeriodType.Week) }
    var breakdownExpanded by remember { mutableStateOf(false) }
    var sortAscending by remember { mutableStateOf(false) }

    val totals = remember(history) {
        var minutes = 0
        var sessions = 0
        history.values.forEach {
            minutes += it.work_minutes
            sessions += it.completed
        }
        minutes to sessions
    }
    val (totalMinutes, totalSessions) = totals

    val today = remember(dayStartHour) { logicalToday(dayStartHour) }
    val todayEntry = history[today]
    val todaySessionsCount = todayEntry?.completed ?: 0
    val daysWithActivity = history.values.count { it.completed > 0 }
    val avgMinutes = if (daysWithActivity > 0) totalMinutes / daysWithActivity else 0
    val bestStreak = remember(history) { calculateBestStreak(history) }
    val currentStreak = remember(history, dayStartHour) { calculateCurrentStreak(history, dayStartHour) }

    val periodData = remember(history, periodType, dayStartHour) {
        buildPeriodData(history, periodType, dayStartHour)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Statistics",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onExport) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Export Stats",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Summary row 1
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Total Focus",
                value = formatHM(totalMinutes),
                valueColor = MaterialTheme.colorScheme.primary,
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Sessions",
                value = "$totalSessions",
                valueColor = MaterialTheme.colorScheme.secondary,
                extra = {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Today: $todaySessionsCount/$dailyGoal",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val pct = if (dailyGoal > 0) (todaySessionsCount.toFloat() / dailyGoal).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        color = Gold,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                },
            )
        }
        Spacer(Modifier.height(16.dp))

        // Summary row 2
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Current / Best",
                value = "$currentStreak / $bestStreak 🔥",
                valueColor = Gold,
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Daily Avg",
                value = formatHM(avgMinutes),
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(24.dp))

        // Heatmap
        Text(
            "Consistency",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Heatmap(history = history, dayStartHour = dayStartHour)
        }
        Spacer(Modifier.height(24.dp))

        // Week grid
        Text(
            "This Week",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        WeekGridCard(history = history, dayStartHour = dayStartHour, dailyGoal = dailyGoal)
        Spacer(Modifier.height(24.dp))

        // Graph header + toggles
        Text(
            text = if (periodType == PeriodType.Month) "Monthly Hours" else "Weekly Hours",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = periodType == PeriodType.Week,
                onClick = { periodType = PeriodType.Week },
                label = { Text("Week") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = periodType == PeriodType.Month,
                onClick = { periodType = PeriodType.Month },
                label = { Text("Month") },
            )
            Spacer(Modifier.width(16.dp))
            FilterChip(
                selected = graphType == GraphType.Bar,
                onClick = { graphType = GraphType.Bar },
                label = { Text("Bar") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = graphType == GraphType.Line,
                onClick = { graphType = GraphType.Line },
                label = { Text("Line") },
            )
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(16.dp),
            ) {
                if (graphType == GraphType.Bar) {
                    BarGraph(periodData, dailyGoal, periodType)
                } else {
                    LineGraph(periodData, dailyGoal)
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // Today's log
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Today's Log (${todaySessions.size})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (todaySessions.isNotEmpty()) {
                IconButton(onClick = { sortAscending = !sortAscending }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = { breakdownExpanded = !breakdownExpanded }) {
                    Text(
                        if (breakdownExpanded) "Hide" else "Show",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        AnimatedVisibility(visible = breakdownExpanded && todaySessions.isNotEmpty()) {
            Column {
                val sessions = if (sortAscending) {
                    todaySessions.sortedBy { it.start }
                } else {
                    todaySessions.sortedByDescending { it.start }
                }
                sessions.forEach { SessionRow(it) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color,
    extra: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = valueColor,
                fontSize = 22.sp,
            )
            extra?.invoke()
        }
    }
}

@Composable
private fun WeekGridCard(history: Map<String, DayEntry>, dayStartHour: Int, dailyGoal: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val days = remember(history, dayStartHour) { last7Days(history, dayStartHour) }
            val primary = MaterialTheme.colorScheme.primary
            val onSurface = MaterialTheme.colorScheme.onSurface
            val variant = MaterialTheme.colorScheme.surfaceVariant
            days.forEach { (label, entry) ->
                val sessions = entry?.completed ?: 0
                val mins = entry?.work_minutes ?: 0
                val goalMet = sessions >= dailyGoal
                val dotColor = when {
                    goalMet -> Gold
                    sessions > 0 -> primary
                    else -> variant
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (sessions > 0) "${mins}m" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (goalMet) Gold else primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarGraph(
    data: List<Pair<String, Int>>,
    dailyGoal: Int,
    period: PeriodType,
) {
    if (data.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val maxMins = (data.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    val goalMins = dailyGoal * 25

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(if (period == PeriodType.Month) 2.dp else 6.dp),
    ) {
        data.forEachIndexed { index, (label, mins) ->
            val frac = mins.toFloat() / maxMins
            val color = if (mins >= goalMins) Gold else primary
            val showLabel = if (period == PeriodType.Month) (index % 5 == 0 || index == data.size - 1) else true
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (period == PeriodType.Week) {
                    val text = if (mins >= 60) String.format(Locale.US, "%.1fh", mins / 60f) else "${mins}m"
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(if (period == PeriodType.Month) 0.6f else 0.7f)
                        .height((100 * frac).coerceAtLeast(if (mins > 0) 4f else 2f).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
                Text(
                    if (showLabel) label else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.6f),
                    fontSize = if (period == PeriodType.Month) 8.sp else 10.sp,
                )
            }
        }
    }
}

@Composable
private fun LineGraph(data: List<Pair<String, Int>>, dailyGoal: Int) {
    if (data.size < 2) return
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = onSurface.copy(alpha = 0.6f))
    val valueStyle = TextStyle(fontSize = 9.sp)
    val maxMins = (data.maxOfOrNull { it.second } ?: 60).coerceAtLeast(60)
    val goalMins = dailyGoal * 25

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val padX = 16.dp.toPx()
        val padTop = 22.dp.toPx()
        val padBottom = 28.dp.toPx()
        val w = size.width - padX * 2
        val h = size.height - padTop - padBottom
        val step = w / (data.size - 1)

        val points = data.mapIndexed { i, (_, m) ->
            Offset(padX + i * step, padTop + h - (m.toFloat() / maxMins) * h)
        }

        // Gradient fill
        val fillPath = Path().apply {
            moveTo(points.first().x, padTop + h)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, padTop + h)
            close()
        }
        drawPath(
            path = fillPath,
            brush = SolidColor(primary.copy(alpha = 0.18f)),
        )

        // Line
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(
            path = linePath,
            color = primary,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )

        // Dots + value labels + x labels
        data.forEachIndexed { i, (label, mins) ->
            val isGoal = mins >= goalMins
            val dotColor = if (isGoal) Gold else primary
            drawCircle(dotColor, radius = (if (isGoal) 6f else 5f).dp.toPx(), center = points[i])

            val valueText = if (mins >= 60) String.format(Locale.US, "%.1fh", mins / 60f) else "${mins}m"
            val tl = measurer.measure(valueText, valueStyle)
            drawText(
                tl,
                color = if (isGoal) Gold else onSurface.copy(alpha = 0.7f),
                topLeft = Offset(points[i].x - tl.size.width / 2f, points[i].y - tl.size.height - 4.dp.toPx()),
            )

            val ll = measurer.measure(label, labelStyle)
            drawText(
                ll,
                color = onSurface.copy(alpha = 0.6f),
                topLeft = Offset(points[i].x - ll.size.width / 2f, size.height - ll.size.height - 4.dp.toPx()),
            )
        }
    }
}

@Composable
private fun Heatmap(history: Map<String, DayEntry>, dayStartHour: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val variant = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val weeks = 20
    val cell = 12.dp
    val spacing = 4.dp

    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(
            width = (cell + spacing) * weeks + spacing,
            height = (cell + spacing) * 7 + spacing,
        ),
    ) {
        val cellPx = cell.toPx()
        val sp = spacing.toPx()
        val now = Calendar.getInstance().also {
            if (it.get(Calendar.HOUR_OF_DAY) < dayStartHour) it.add(Calendar.DAY_OF_YEAR, -1)
        }
        val startCal = (now.clone() as Calendar).apply {
            add(Calendar.WEEK_OF_YEAR, -(weeks - 1))
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = Date()
        val cal = startCal.clone() as Calendar
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                if (cal.time.after(today)) return@Canvas
                val key = df.format(cal.time)
                val entry = history[key]
                val sessions = entry?.completed ?: 0
                val mins = entry?.work_minutes ?: 0
                val color = when {
                    sessions == 0 -> variant
                    mins < 30 -> primary.copy(alpha = 0.25f)
                    mins < 60 -> primary.copy(alpha = 0.5f)
                    mins < 120 -> primary.copy(alpha = 0.75f)
                    else -> primary
                }
                val left = sp + w * (cellPx + sp)
                val top = sp + d * (cellPx + sp)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(cellPx, cellPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity) {
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val start = df.format(Date(session.start))
    val end = df.format(Date(session.start + session.duration * 1000L))
    val (bg, fg) = when (session.type) {
        "work" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "short" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "long" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$start - $end",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${session.duration / 60}m",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(session.type.uppercase(), color = fg, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatHM(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun logicalToday(dayStartHour: Int): String {
    val cal = Calendar.getInstance()
    if (cal.get(Calendar.HOUR_OF_DAY) < dayStartHour) cal.add(Calendar.DAY_OF_YEAR, -1)
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
}

private fun last7Days(history: Map<String, DayEntry>, dayStartHour: Int): List<Pair<String, DayEntry?>> {
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dayFmt = SimpleDateFormat("EEE", Locale.US)
    val cal = Calendar.getInstance()
    if (cal.get(Calendar.HOUR_OF_DAY) < dayStartHour) cal.add(Calendar.DAY_OF_YEAR, -1)
    cal.add(Calendar.DAY_OF_YEAR, -6)
    val out = mutableListOf<Pair<String, DayEntry?>>()
    for (i in 0 until 7) {
        val label = dayFmt.format(cal.time).take(1).uppercase()
        out.add(label to history[df.format(cal.time)])
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return out
}

private fun buildPeriodData(
    history: Map<String, DayEntry>,
    period: PeriodType,
    dayStartHour: Int,
): List<Pair<String, Int>> {
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dayFmt = SimpleDateFormat("EEE", Locale.US)
    val domFmt = SimpleDateFormat("d", Locale.US)
    val cal = Calendar.getInstance()
    if (cal.get(Calendar.HOUR_OF_DAY) < dayStartHour) cal.add(Calendar.DAY_OF_YEAR, -1)
    val days = if (period == PeriodType.Month) 30 else 7
    cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
    val out = mutableListOf<Pair<String, Int>>()
    for (i in 0 until days) {
        val label = if (period == PeriodType.Month) {
            domFmt.format(cal.time)
        } else {
            dayFmt.format(cal.time).take(1).uppercase()
        }
        out.add(label to (history[df.format(cal.time)]?.work_minutes ?: 0))
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return out
}

private fun calculateBestStreak(history: Map<String, DayEntry>): Int {
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dates = history.entries.filter { it.value.completed > 0 }
        .mapNotNull { runCatching { df.parse(it.key) }.getOrNull() }
        .sortedDescending()
    if (dates.isEmpty()) return 0
    var best = 1
    var cur = 1
    for (i in 0 until dates.size - 1) {
        val diff = (dates[i].time - dates[i + 1].time) / (1000 * 60 * 60 * 24)
        if (diff == 1L) {
            cur++
            best = maxOf(best, cur)
        } else cur = 1
    }
    return best
}

private fun calculateCurrentStreak(history: Map<String, DayEntry>, dayStartHour: Int): Int {
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    if (cal.get(Calendar.HOUR_OF_DAY) < dayStartHour) cal.add(Calendar.DAY_OF_YEAR, -1)
    val todayKey = df.format(cal.time)
    val todayActive = (history[todayKey]?.completed ?: 0) > 0
    if (!todayActive) cal.add(Calendar.DAY_OF_YEAR, -1)
    var streak = 0
    while (true) {
        val key = df.format(cal.time)
        if ((history[key]?.completed ?: 0) > 0) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        } else break
    }
    return streak
}
