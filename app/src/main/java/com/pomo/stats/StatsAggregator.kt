package com.pomo.stats

import com.pomo.db.DayStatsEntity
import com.pomo.db.SessionEntity
import com.pomo.util.DateLogic
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap

public object StatsAggregator {

    private const val DATE_PATTERN: String = "yyyy-MM-dd"
    private const val HABIT_WEEKS: Int = 12
    private const val GOAL_WINDOW_DAYS: Int = 30
    private const val WORK_TYPE: String = "work"

    /**
     * Aggregate a [StatsSnapshot] from raw Room data. Pure: no Android, no clock.
     * Caller passes [nowMs] (used for streak anchoring) and [today] (the effective
     * calendar date string the rest of the app is using).
     */
    public fun aggregate(
        days: List<DayStatsEntity>,
        sessions: List<SessionEntity>,
        dailyGoal: Int,
        today: String,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): StatsSnapshot {
        if (days.isEmpty() && sessions.isEmpty()) {
            return StatsSnapshot.Empty.copy(
                goal = GoalSummary(dailyGoal = dailyGoal, daysHit = 0, totalDays = 0),
                habit = HabitWindow(
                    weeks = HABIT_WEEKS,
                    cells = buildEmptyHabitCells(today, HABIT_WEEKS, tz),
                    currentStreak = 0,
                    bestStreak = 0,
                ),
            )
        }

        val workSessions = sessions.filter { it.type == WORK_TYPE && it.completed }
        val dayByDate = days.associateBy { it.date }

        val lifetime = computeLifetime(days, today, tz)
        val rhythm = computeHourRhythm(workSessions, tz)
        val weekShape = computeWeekShape(workSessions, tz)
        val habit = computeHabitWindow(dayByDate, today, nowMs, tz)
        val goal = computeGoalSummary(dayByDate, today, dailyGoal, tz)
        val records = computeRecords(days, habit.bestStreak)
        val chartTrend = computeChartTrend(days, sessions, dayByDate, today, tz)

        return StatsSnapshot(
            lifetime = lifetime,
            rhythm = rhythm,
            weekShape = weekShape,
            habit = habit,
            goal = goal,
            records = records,
            chartTrend = chartTrend,
        )
    }

    private fun computeLifetime(
        days: List<DayStatsEntity>,
        today: String,
        tz: TimeZone,
    ): Lifetime {
        if (days.isEmpty()) return Lifetime(0, 0, 0, null)
        var minutes = 0
        var sessions = 0
        var earliest: String? = null
        for (d in days) {
            minutes += d.workMinutes
            sessions += d.completed
            if (d.completed > 0 && (earliest == null || d.date < earliest)) {
                earliest = d.date
            }
        }
        val daysSpan = earliest?.let { inclusiveDayCount(it, today, tz) } ?: 0
        return Lifetime(
            focusMinutes = minutes,
            sessions = sessions,
            daysWithApp = daysSpan,
            firstDate = earliest,
        )
    }

    /** Build the 24-hour focus rhythm for a single day's sessions (work blocks only). */
    public fun hourRhythmForDay(
        sessions: List<SessionEntity>,
        tz: TimeZone = TimeZone.getDefault(),
    ): HourRhythm = computeHourRhythm(sessions.filter { it.type == WORK_TYPE && it.completed }, tz)

    private fun computeHourRhythm(
        workSessions: List<SessionEntity>,
        tz: TimeZone,
    ): HourRhythm {
        val buckets = IntArray(24)
        if (workSessions.isEmpty()) {
            return HourRhythm(buckets, null, RhythmPattern.None)
        }
        val cal = Calendar.getInstance(tz)
        for (s in workSessions) {
            // SessionEntity.start is epoch SECONDS; convert to ms.
            cal.time = Date(s.start * 1000L)
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val minutes = (s.duration + 59) / 60
            buckets[h] += minutes.coerceAtLeast(1)
        }
        val total = buckets.sum()
        if (total == 0) return HourRhythm(buckets, null, RhythmPattern.None)
        val peak = buckets.indices.maxBy { buckets[it] }
        val pattern = classifyPattern(buckets, peak, total)
        return HourRhythm(buckets, peak, pattern)
    }

    private fun classifyPattern(buckets: IntArray, peak: Int, total: Int): RhythmPattern {
        // Concentration: top-3 hours share of total. < 45% → scattered.
        val sorted = buckets.toList().sortedDescending().take(3).sum()
        val concentration = sorted.toFloat() / total
        if (concentration < 0.45f) return RhythmPattern.Scattered
        return when (peak) {
            in 5..11 -> RhythmPattern.Morning
            in 12..16 -> RhythmPattern.Afternoon
            in 17..20 -> RhythmPattern.Evening
            else -> RhythmPattern.Night
        }
    }

    private fun computeWeekShape(
        workSessions: List<SessionEntity>,
        tz: TimeZone,
    ): WeekShape {
        val buckets = IntArray(7) // 0 = Monday, 6 = Sunday
        if (workSessions.isEmpty()) return WeekShape(buckets, null)
        val cal = Calendar.getInstance(tz)
        for (s in workSessions) {
            cal.time = Date(s.start * 1000L)
            val dow = cal.get(Calendar.DAY_OF_WEEK) // Sun=1..Sat=7
            val idx = (dow + 5) % 7 // Mon=0..Sun=6
            val minutes = (s.duration + 59) / 60
            buckets[idx] += minutes.coerceAtLeast(1)
        }
        if (buckets.sum() == 0) return WeekShape(buckets, null)
        val strongest = buckets.indices.maxBy { buckets[it] }
        return WeekShape(buckets, strongest)
    }

    private fun computeHabitWindow(
        dayByDate: Map<String, DayStatsEntity>,
        today: String,
        nowMs: Long,
        tz: TimeZone,
    ): HabitWindow {
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = tz }
        val todayDate = df.parse(today) ?: return HabitWindow(HABIT_WEEKS, emptyList(), 0, 0)

        // Floor: the most recent HABIT_WEEKS weeks, Sunday-aligned.
        val floorStart = sundayOnOrBefore(
            Calendar.getInstance(tz).apply {
                time = todayDate
                add(Calendar.WEEK_OF_YEAR, -(HABIT_WEEKS - 1))
            },
        )
        // Prefer starting at the first active day so new users don't see empty
        // padding on the left; the window grows toward the 12-week cap over time.
        // Eligibility is workMinutes (not completed) so the pre-midnight segment of
        // a split block anchors the window; otherwise its focus minutes drop off the
        // left edge of the heatmap and the KPI strip.
        val activityStart = dayByDate.values
            .filter { it.workMinutes > 0 }
            .minByOrNull { it.date }
            ?.date
            ?.let { df.parse(it) }
            ?.let { sundayOnOrBefore(Calendar.getInstance(tz).apply { time = it }) }
        // Later (more recent) of the two = at most 12 weeks, trimmed to real history.
        val startMillis = maxOf(floorStart.timeInMillis, activityStart?.timeInMillis ?: Long.MIN_VALUE)

        val cells = mutableListOf<HeatCell>()
        val iter = Calendar.getInstance(tz).apply { timeInMillis = startMillis }
        while (!iter.time.after(todayDate)) {
            val key = df.format(iter.time)
            val entry = dayByDate[key]
            cells += HeatCell(
                date = key,
                sessions = entry?.completed ?: 0,
                minutes = entry?.workMinutes ?: 0,
            )
            iter.add(Calendar.DAY_OF_YEAR, 1)
        }
        val weeks = ((cells.size + 6) / 7).coerceAtLeast(1)

        val activeDates = dayByDate.values
            .filter { it.completed > 0 }
            .map { it.date }
            .toSet()
        return HabitWindow(
            weeks = weeks,
            cells = cells,
            currentStreak = DateLogic.currentStreak(activeDates, nowMs, tz),
            bestStreak = DateLogic.bestStreak(activeDates),
        )
    }

    private fun computeGoalSummary(
        dayByDate: Map<String, DayStatsEntity>,
        today: String,
        dailyGoal: Int,
        tz: TimeZone,
    ): GoalSummary {
        if (dailyGoal <= 0) return GoalSummary(dailyGoal = 0, daysHit = 0, totalDays = 0)
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = tz }
        val todayDate = df.parse(today) ?: return GoalSummary(dailyGoal, 0, 0)
        val iter = Calendar.getInstance(tz).apply {
            time = todayDate
            add(Calendar.DAY_OF_YEAR, -(GOAL_WINDOW_DAYS - 1))
        }
        var hit = 0
        repeat(GOAL_WINDOW_DAYS) {
            val key = df.format(iter.time)
            val completed = dayByDate[key]?.completed ?: 0
            if (completed >= dailyGoal) hit++
            iter.add(Calendar.DAY_OF_YEAR, 1)
        }
        return GoalSummary(dailyGoal = dailyGoal, daysHit = hit, totalDays = GOAL_WINDOW_DAYS)
    }

    private fun computeRecords(
        days: List<DayStatsEntity>,
        bestStreak: Int,
    ): Records {
        // Rank by focus minutes. Use workMinutes (not completed) as the eligibility
        // filter so the pre-midnight segment of a split block — which carries the
        // minutes but no completed count — can still win as the best focus day.
        val bestDay = days
            .filter { it.workMinutes > 0 }
            .maxByOrNull { it.workMinutes }
            ?.let { BestDay(date = it.date, sessions = it.completed, minutes = it.workMinutes) }

        // Best week: group sessions by Sunday-anchored week (week start = Sunday).
        val bestWeek = if (days.isEmpty()) null else computeBestWeek(days)

        return Records(bestDay = bestDay, bestWeek = bestWeek, longestStreak = bestStreak)
    }

    private fun computeBestWeek(days: List<DayStatsEntity>): BestWeek? {
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US)
        val cal = Calendar.getInstance()
        // Per Sunday-anchored week: accumulate both Focus minutes (the ranking metric)
        // and completed blocks (kept for display).
        val minutesByWeek = HashMap<String, Int>()
        val blocksByWeek = HashMap<String, Int>()
        for (d in days) {
            val date = df.parse(d.date) ?: continue
            cal.time = date
            // Snap to Sunday of that week.
            val dow = cal.get(Calendar.DAY_OF_WEEK) // Sun=1
            cal.add(Calendar.DAY_OF_YEAR, -(dow - Calendar.SUNDAY))
            val key = df.format(cal.time)
            minutesByWeek[key] = (minutesByWeek[key] ?: 0) + d.workMinutes
            blocksByWeek[key] = (blocksByWeek[key] ?: 0) + d.completed
        }
        val (weekStart, minutes) = minutesByWeek.maxByOrNull { it.value } ?: return null
        // Guard on focus minutes, not block count: a week holding the pre-midnight
        // segment of a split block has minutes but may have zero completed blocks.
        if (minutes == 0) return null
        return BestWeek(weekStart = weekStart, sessions = blocksByWeek[weekStart] ?: 0, minutes = minutes)
    }

    private fun inclusiveDayCount(fromDate: String, toDate: String, tz: TimeZone): Int {
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = tz }
        val from = df.parse(fromDate) ?: return 0
        val to = df.parse(toDate) ?: return 0
        val days = (to.time - from.time) / (1000L * 60 * 60 * 24)
        return (days.toInt() + 1).coerceAtLeast(1)
    }

    private fun computeChartTrend(
        days: List<DayStatsEntity>,
        sessions: List<SessionEntity>,
        dayByDate: Map<String, DayStatsEntity>,
        today: String,
        tz: TimeZone,
    ): ChartTrend {
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = tz }
        val cal = Calendar.getInstance(tz)
        val todayDate = df.parse(today) ?: return ChartTrend.Empty

        // 1D — hourly buckets for today (minutes per hour)
        val hourlyBuckets = IntArray(24)
        for (s in sessions) {
            if (s.type != WORK_TYPE || !s.completed) continue
            cal.time = Date(s.start * 1000L)
            if (df.format(cal.time) != today) continue
            val h = cal.get(Calendar.HOUR_OF_DAY)
            hourlyBuckets[h] += ((s.duration + 59) / 60).coerceAtLeast(1)
        }
        val todaySeries = TrendSeries((0..23).map { h ->
            TrendPoint(label = h.toString(), value = hourlyBuckets[h].toFloat())
        })

        // 7D — one data point per day, last 7 days oldest→newest
        val dowLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val weekPoints: List<TrendPoint> = (6 downTo 0).map { daysAgo ->
            val c = Calendar.getInstance(tz).apply { time = todayDate; add(Calendar.DAY_OF_YEAR, -daysAgo) }
            val key = df.format(c.time)
            val mins = dayByDate[key]?.workMinutes ?: 0
            val dow = c.get(Calendar.DAY_OF_WEEK) // Sun=1
            TrendPoint(label = dowLabels[(dow + 5) % 7], value = mins.toFloat())
        }
        val weekSeries = TrendSeries(weekPoints)

        // 28D — 4 weekly totals, oldest week first
        val monthPoints: List<TrendPoint> = (3 downTo 0).map { weeksAgo ->
            var weekMins = 0
            var weekLabel = ""
            for (dayInWeek in 6 downTo 0) {
                val offset = -(weeksAgo * 7 + dayInWeek)
                val c = Calendar.getInstance(tz).apply { time = todayDate; add(Calendar.DAY_OF_YEAR, offset) }
                val key = df.format(c.time)
                weekMins += dayByDate[key]?.workMinutes ?: 0
                if (dayInWeek == 6) weekLabel = "Wk${4 - weeksAgo}"
            }
            TrendPoint(label = weekLabel, value = weekMins.toFloat())
        }
        val monthSeries = TrendSeries(monthPoints)

        // ALL TIME — aggregate by calendar month, oldest→newest
        val monthlyMap = TreeMap<String, Int>()
        for (d in days) {
            val key = d.date.substring(0, 7) // "YYYY-MM"
            monthlyMap[key] = (monthlyMap[key] ?: 0) + d.workMinutes
        }
        val monthFmt = SimpleDateFormat("MMM", Locale.US).apply { timeZone = tz }
        val parseFmt = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = tz }
        val allTimePoints: List<TrendPoint> = monthlyMap.entries.map { (key, mins) ->
            val label = parseFmt.parse(key)?.let { monthFmt.format(it) } ?: key.substring(5)
            TrendPoint(label = label, value = mins.toFloat())
        }
        val allTimeSeries = TrendSeries(allTimePoints.ifEmpty { listOf(TrendPoint("—", 0f)) })

        return ChartTrend(today = todaySeries, week = weekSeries, month = monthSeries, allTime = allTimeSeries)
    }

    /** Snap a calendar to the Sunday on or before its current date (mutates and returns it). */
    private fun sundayOnOrBefore(cal: Calendar): Calendar {
        val dow = cal.get(Calendar.DAY_OF_WEEK) // Sunday = 1
        cal.add(Calendar.DAY_OF_YEAR, -(dow - Calendar.SUNDAY))
        return cal
    }

    private fun buildEmptyHabitCells(today: String, weeks: Int, tz: TimeZone): List<HeatCell> {
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = tz }
        val todayDate = df.parse(today) ?: return emptyList()
        val start = Calendar.getInstance(tz).apply {
            time = todayDate
            add(Calendar.WEEK_OF_YEAR, -(weeks - 1))
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        val cells = mutableListOf<HeatCell>()
        val iter = start.clone() as Calendar
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                if (iter.time.after(todayDate)) break
                cells += HeatCell(date = df.format(iter.time), sessions = 0, minutes = 0)
                iter.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cells
    }
}
