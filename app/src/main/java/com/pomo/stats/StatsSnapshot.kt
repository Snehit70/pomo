package com.pomo.stats

public data class TrendPoint(val label: String, val value: Float)

public data class TrendSeries(val points: List<TrendPoint>)

public data class ChartTrend(
    val today: TrendSeries,
    val week: TrendSeries,
    val month: TrendSeries,
    val allTime: TrendSeries,
) {
    public companion object {
        public val Empty: ChartTrend = ChartTrend(
            today = TrendSeries(emptyList()),
            week = TrendSeries(emptyList()),
            month = TrendSeries(emptyList()),
            allTime = TrendSeries(emptyList()),
        )
    }
}

public data class StatsSnapshot(
    val lifetime: Lifetime,
    val rhythm: HourRhythm,
    val weekShape: WeekShape,
    val habit: HabitWindow,
    val goal: GoalSummary,
    val records: Records,
    val chartTrend: ChartTrend,
) {
    public val isEmpty: Boolean get() = lifetime.sessions == 0

    public companion object {
        public val Empty: StatsSnapshot = StatsSnapshot(
            lifetime = Lifetime(focusMinutes = 0, sessions = 0, daysWithApp = 0, firstDate = null),
            rhythm = HourRhythm(buckets = IntArray(24), peakHour = null, pattern = RhythmPattern.None),
            weekShape = WeekShape(buckets = IntArray(7), strongestDayIndex = null),
            habit = HabitWindow(weeks = 12, cells = emptyList(), currentStreak = 0, bestStreak = 0),
            goal = GoalSummary(dailyGoal = 0, daysHit = 0, totalDays = 0),
            records = Records(bestDay = null, bestWeek = null, longestStreak = 0),
            chartTrend = ChartTrend.Empty,
        )
    }
}

public data class Lifetime(
    val focusMinutes: Int,
    val sessions: Int,
    val daysWithApp: Int,
    val firstDate: String?,
)

public data class HourRhythm(
    val buckets: IntArray,
    val peakHour: Int?,
    val pattern: RhythmPattern,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HourRhythm) return false
        return buckets.contentEquals(other.buckets) && peakHour == other.peakHour && pattern == other.pattern
    }
    override fun hashCode(): Int {
        var result = buckets.contentHashCode()
        result = 31 * result + (peakHour ?: -1)
        result = 31 * result + pattern.hashCode()
        return result
    }
}

public enum class RhythmPattern { Morning, Afternoon, Evening, Night, Scattered, None }

public data class WeekShape(
    val buckets: IntArray,
    val strongestDayIndex: Int?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WeekShape) return false
        return buckets.contentEquals(other.buckets) && strongestDayIndex == other.strongestDayIndex
    }
    override fun hashCode(): Int {
        var result = buckets.contentHashCode()
        result = 31 * result + (strongestDayIndex ?: -1)
        return result
    }
}

public data class HabitWindow(
    val weeks: Int,
    val cells: List<HeatCell>,
    val currentStreak: Int,
    val bestStreak: Int,
)

public data class HeatCell(
    val date: String,
    val sessions: Int,
    val minutes: Int,
)

public data class GoalSummary(
    val dailyGoal: Int,
    val daysHit: Int,
    val totalDays: Int,
)

public data class Records(
    val bestDay: BestDay?,
    val bestWeek: BestWeek?,
    val longestStreak: Int,
)

public data class BestDay(val date: String, val sessions: Int, val minutes: Int)
public data class BestWeek(val weekStart: String, val sessions: Int, val minutes: Int)
