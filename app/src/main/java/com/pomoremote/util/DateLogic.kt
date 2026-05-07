package com.pomoremote.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure date/streak helpers. No Android, no clock — caller passes nowMs.
 */
public object DateLogic {

    private const val DATE_PATTERN: String = "yyyy-MM-dd"

    /**
     * Logical date taking dayStartHour into account: anything before dayStartHour
     * still belongs to the previous day.
     */
    public fun effectiveDate(nowMs: Long, dayStartHour: Int, tz: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        if (cal.get(Calendar.HOUR_OF_DAY) < dayStartHour) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = tz }
        return df.format(cal.time)
    }

    /**
     * Current streak: count of consecutive logical days ending at "today" (or
     * yesterday if today is not yet active) where activeDates contains the date.
     */
    public fun currentStreak(
        activeDates: Set<String>,
        nowMs: Long,
        dayStartHour: Int,
        tz: TimeZone = TimeZone.getDefault(),
    ): Int {
        if (activeDates.isEmpty()) return 0
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        if (cal.get(Calendar.HOUR_OF_DAY) < dayStartHour) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = tz }
        val todayKey = df.format(cal.time)
        val todayActive = activeDates.contains(todayKey)
        if (!todayActive) cal.add(Calendar.DAY_OF_YEAR, -1)

        var streak = 0
        while (true) {
            val key = df.format(cal.time)
            if (activeDates.contains(key)) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return streak
    }

    /**
     * Best streak across all activeDates.
     */
    public fun bestStreak(activeDates: Set<String>): Int {
        if (activeDates.isEmpty()) return 0
        val df = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val parsed = activeDates.mapNotNull { runCatching { df.parse(it) }.getOrNull() }
            .sortedDescending()
        if (parsed.isEmpty()) return 0
        var best = 1
        var cur = 1
        for (i in 0 until parsed.size - 1) {
            val diffDays = (parsed[i].time - parsed[i + 1].time) / (1000L * 60 * 60 * 24)
            if (diffDays == 1L) {
                cur++
                best = maxOf(best, cur)
            } else {
                cur = 1
            }
        }
        return best
    }
}
