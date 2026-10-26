package com.pomo.crew

import com.pomo.db.DayStatsEntity
import com.pomo.stats.BestDay
import com.pomo.stats.BestMonth
import com.pomo.stats.BestWeek
import com.pomo.stats.HourRhythm
import com.pomo.stats.Lifetime
import com.pomo.stats.Records
import com.pomo.stats.RhythmPattern
import com.pomo.stats.StatsAggregator
import com.pomo.stats.StatsSnapshot
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TimeZone

/**
 * Rebuild a crew member's [StatsSnapshot] from what they shared, so their page can be drawn with
 * the very same screen we draw for ourselves.
 *
 * Two things are deliberately not re-derived from the shared history window: lifetime totals and
 * records. Both are sent whole in [CrewStatsExtras] precisely because the window is finite, and a
 * number that quietly shrank on someone else's phone would be worse than no number at all.
 *
 * A member on a build that predates the extras still gets a page — it just carries the 30 daily
 * totals every snapshot has always had, and [StatsSnapshot.rhythm] stays empty. Ask
 * [hasFullStats] before promising the reader an hour-by-hour chart.
 */
public fun CrewBoardRow.toStatsSnapshot(
    nowMs: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): StatsSnapshot {
    val today: String = localDate.toIsoDateOrNull()?.toString() ?: LocalDate.now().toString()
    val days = memberDays()
    val base =
        StatsAggregator.aggregate(
            days = days,
            // Members share bucketed rhythm, never raw sessions: their timestamps are their business.
            sessions = emptyList(),
            dailyGoal = 0,
            today = today,
            nowMs = nowMs,
            tz = timeZone,
        )
    if (base.isEmpty && allTimeFocusMinutes == 0) return base

    val bestStreak = stats?.bestStreak ?: base.habit.bestStreak
    return base.copy(
        lifetime =
            Lifetime(
                focusMinutes = allTimeFocusMinutes,
                sessions = stats?.allTimeWorkBlocks ?: days.sumOf { it.completed },
                activeDays = stats?.allTimeActiveDays ?: days.count { it.completed > 0 },
                daysWithApp = daysWithPomo(today),
                firstDate = stats?.firstFocusLocalDate,
            ),
        rhythm =
            stats?.hourBuckets
                ?.takeIf { it.size == CrewValidation.HOUR_BUCKETS }
                ?.let { StatsAggregator.rhythmFromBuckets(it.toIntArray()) }
                ?: HourRhythm(IntArray(CrewValidation.HOUR_BUCKETS), null, RhythmPattern.None),
        weekShape =
            stats?.weekdayBuckets
                ?.takeIf { it.size == CrewValidation.WEEKDAY_BUCKETS }
                ?.let { StatsAggregator.weekShapeFromBuckets(it.toIntArray()) }
                ?: base.weekShape,
        // Their streak is anchored to their clock and their midnight, so take it as given rather
        // than recomputing it against ours.
        habit = base.habit.copy(currentStreak = currentStreak, bestStreak = bestStreak),
        records =
            Records(
                bestDay = stats?.bestDay() ?: base.records.bestDay,
                bestWeek = stats?.bestWeek() ?: base.records.bestWeek,
                longestStreak = bestStreak,
                // Old snapshots do not carry a lifetime best month. Their short daily window
                // cannot prove this record, so leave it blank instead of presenting a partial one.
                bestMonth = stats?.bestMonth(),
            ),
    )
}

/** Whether this member's build shares enough for the hour-of-day chart and the long heatmap. */
public fun CrewBoardRow.hasFullStats(): Boolean = stats?.hourBuckets?.size == CrewValidation.HOUR_BUCKETS

/**
 * Prefer the dense history window; fall back to the 30 daily aggregates every snapshot carries.
 * Gaps are real zeros — a day a member did not focus is a day with no minutes.
 */
private fun CrewBoardRow.memberDays(): List<DayStatsEntity> {
    val start = stats?.historyStartDate?.toIsoDateOrNull()
    val minutes = stats?.historyFocusMinutes
    val blocks = stats?.historyWorkBlocks
    if (start != null && minutes != null && blocks != null && minutes.size == blocks.size) {
        return minutes.indices.map { index ->
            DayStatsEntity(
                date = start.plusDays(index.toLong()).toString(),
                completed = blocks[index],
                workMinutes = minutes[index],
                breakMinutes = 0,
            )
        }
    }
    return dailyAggregates.map { aggregate ->
        DayStatsEntity(
            date = aggregate.localDate,
            completed = aggregate.completedWorkBlocks,
            workMinutes = aggregate.focusMinutes,
            breakMinutes = 0,
        )
    }
}

private fun CrewBoardRow.daysWithPomo(today: String): Int {
    val first = stats?.firstFocusLocalDate?.toIsoDateOrNull() ?: return 0
    val todayDate = today.toIsoDateOrNull() ?: return 0
    return (ChronoUnit.DAYS.between(first, todayDate) + 1).toInt().coerceAtLeast(0)
}

private fun CrewStatsExtras.bestDay(): BestDay? {
    val date = bestDayLocalDate ?: return null
    val minutes = bestDayFocusMinutes ?: return null
    return BestDay(date = date, sessions = bestDayWorkBlocks ?: 0, minutes = minutes)
}

private fun CrewStatsExtras.bestWeek(): BestWeek? {
    val weekStart = bestWeekStartDate ?: return null
    val minutes = bestWeekFocusMinutes ?: return null
    return BestWeek(weekStart = weekStart, sessions = bestWeekWorkBlocks ?: 0, minutes = minutes)
}

private fun CrewStatsExtras.bestMonth(): BestMonth? {
    val monthStart = bestMonthStartDate ?: return null
    val minutes = bestMonthFocusMinutes ?: return null
    return BestMonth(monthStart = monthStart, sessions = bestMonthWorkBlocks ?: 0, minutes = minutes)
}

private fun String.toIsoDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
