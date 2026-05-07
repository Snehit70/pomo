package com.pomoremote.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

public class DateLogicTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val isoDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = utc }

    private fun ms(iso: String): Long = isoDateTime.parse(iso)!!.time

    @Test
    public fun effectiveDate_afterDayStart_returnsToday() {
        // 2026-05-07 10:00 UTC, dayStart=3 → "2026-05-07"
        assertEquals("2026-05-07", DateLogic.effectiveDate(ms("2026-05-07T10:00:00"), 3, utc))
    }

    @Test
    public fun effectiveDate_beforeDayStart_returnsYesterday() {
        // 2026-05-07 02:00 UTC, dayStart=3 → "2026-05-06"
        assertEquals("2026-05-06", DateLogic.effectiveDate(ms("2026-05-07T02:00:00"), 3, utc))
    }

    @Test
    public fun effectiveDate_zeroDayStart_alwaysToday() {
        assertEquals("2026-05-07", DateLogic.effectiveDate(ms("2026-05-07T00:30:00"), 0, utc))
    }

    @Test
    public fun currentStreak_emptyHistory_isZero() {
        assertEquals(0, DateLogic.currentStreak(emptySet(), ms("2026-05-07T10:00:00"), 3, utc))
    }

    @Test
    public fun currentStreak_todayActive_extendsToToday() {
        val active = setOf("2026-05-07", "2026-05-06", "2026-05-05")
        assertEquals(3, DateLogic.currentStreak(active, ms("2026-05-07T10:00:00"), 3, utc))
    }

    @Test
    public fun currentStreak_todayInactive_falsBackToYesterday() {
        // Today (2026-05-07) inactive, but yesterday and prior are active → 2
        val active = setOf("2026-05-06", "2026-05-05")
        assertEquals(2, DateLogic.currentStreak(active, ms("2026-05-07T10:00:00"), 3, utc))
    }

    @Test
    public fun currentStreak_gap_breaksStreak() {
        // Yesterday active, day before missing → only 1
        val active = setOf("2026-05-06", "2026-05-04")
        assertEquals(1, DateLogic.currentStreak(active, ms("2026-05-07T10:00:00"), 3, utc))
    }

    @Test
    public fun currentStreak_dayStartRollover_treatsLateNightAsYesterday() {
        // 2026-05-07 02:00, dayStart=3 → "today" is 2026-05-06.
        // Active set covers 5/6 + 5/5 + 5/4 → streak 3.
        val active = setOf("2026-05-06", "2026-05-05", "2026-05-04")
        assertEquals(3, DateLogic.currentStreak(active, ms("2026-05-07T02:00:00"), 3, utc))
    }

    @Test
    public fun bestStreak_empty_isZero() {
        assertEquals(0, DateLogic.bestStreak(emptySet()))
    }

    @Test
    public fun bestStreak_single_isOne() {
        assertEquals(1, DateLogic.bestStreak(setOf("2026-05-01")))
    }

    @Test
    public fun bestStreak_findsLongestRunAcrossGaps() {
        val active = setOf(
            "2026-05-01", "2026-05-02",
            "2026-05-04", "2026-05-05", "2026-05-06", "2026-05-07",
            "2026-05-09",
        )
        // Longest run is 5/4..5/7 = 4
        assertEquals(4, DateLogic.bestStreak(active))
    }
}
