package com.pomo.stats

import com.pomo.db.DayStatsEntity
import com.pomo.db.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

public class StatsAggregatorTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val isoDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = utc }

    private fun startSec(iso: String): Long = isoDateTime.parse(iso)!!.time / 1000L
    private fun ms(iso: String): Long = isoDateTime.parse(iso)!!.time

    private fun work(iso: String, durationSec: Int = 25 * 60, completed: Boolean = true): SessionEntity {
        val s = startSec(iso)
        val date = iso.substring(0, 10)
        return SessionEntity(start = s, date = date, type = "work", duration = durationSec, completed = completed)
    }

    private fun day(date: String, completed: Int, work: Int) =
        DayStatsEntity(date = date, completed = completed, workMinutes = work, breakMinutes = 0)

    @Test
    public fun empty_returnsEmptySnapshotWithDailyGoal() {
        val snap = StatsAggregator.aggregate(
            days = emptyList(),
            sessions = emptyList(),
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertTrue(snap.isEmpty)
        assertEquals(0, snap.lifetime.sessions)
        assertEquals(0, snap.lifetime.focusMinutes)
        assertNull(snap.lifetime.firstDate)
        assertEquals(8, snap.goal.dailyGoal)
        assertEquals(0, snap.goal.daysHit)
        assertEquals(RhythmPattern.None, snap.rhythm.pattern)
        assertNull(snap.rhythm.peakHour)
    }

    @Test
    public fun lifetime_sumsDaysAndPicksEarliestActive() {
        val days = listOf(
            day("2026-05-15", completed = 3, work = 75),
            day("2026-05-10", completed = 0, work = 0), // not active
            day("2026-05-12", completed = 5, work = 125),
            day("2026-05-17", completed = 2, work = 50),
        )
        val snap = StatsAggregator.aggregate(
            days = days,
            sessions = emptyList(),
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertEquals(10, snap.lifetime.sessions)
        assertEquals(250, snap.lifetime.focusMinutes)
        assertEquals("2026-05-12", snap.lifetime.firstDate)
        // 2026-05-12 .. 2026-05-18 inclusive = 7 days
        assertEquals(7, snap.lifetime.daysWithApp)
    }

    @Test
    public fun rhythm_clustersInMorningWhenPeakIsMorning() {
        val sessions = listOf(
            work("2026-05-15T09:00:00"),
            work("2026-05-15T10:00:00"),
            work("2026-05-16T10:00:00"),
            work("2026-05-17T10:00:00"),
            work("2026-05-17T11:00:00"),
        )
        val snap = StatsAggregator.aggregate(
            days = listOf(day("2026-05-17", 5, 125)),
            sessions = sessions,
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertEquals(10, snap.rhythm.peakHour)
        assertEquals(RhythmPattern.Morning, snap.rhythm.pattern)
    }

    @Test
    public fun rhythm_scatteredWhenSpread() {
        val sessions = listOf(
            work("2026-05-15T03:00:00"),
            work("2026-05-15T09:00:00"),
            work("2026-05-15T14:00:00"),
            work("2026-05-15T19:00:00"),
            work("2026-05-15T22:00:00"),
            work("2026-05-16T06:00:00"),
            work("2026-05-16T13:00:00"),
            work("2026-05-16T20:00:00"),
        )
        val snap = StatsAggregator.aggregate(
            days = listOf(day("2026-05-16", 8, 200)),
            sessions = sessions,
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertEquals(RhythmPattern.Scattered, snap.rhythm.pattern)
    }

    @Test
    public fun weekShape_mondayIndexZeroSundayIndexSix() {
        // 2026-05-18 is Monday; 2026-05-17 is Sunday.
        val sessions = listOf(
            work("2026-05-18T10:00:00"), // Monday → idx 0
            work("2026-05-17T10:00:00"), // Sunday → idx 6
            work("2026-05-17T11:00:00"), // Sunday → idx 6 again, strongest
            work("2026-05-17T12:00:00"), // Sunday → idx 6
        )
        val snap = StatsAggregator.aggregate(
            days = listOf(day("2026-05-18", 4, 100)),
            sessions = sessions,
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertEquals(6, snap.weekShape.strongestDayIndex)
        assertTrue(snap.weekShape.buckets[0] > 0)
        assertTrue(snap.weekShape.buckets[6] > snap.weekShape.buckets[0])
    }

    @Test
    public fun habitWindow_currentStreakAnchorsAtToday() {
        val days = listOf(
            day("2026-05-18", 3, 75),
            day("2026-05-17", 4, 100),
            day("2026-05-16", 2, 50),
            day("2026-05-14", 1, 25), // gap on 5-15
        )
        val snap = StatsAggregator.aggregate(
            days = days,
            sessions = emptyList(),
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertEquals(3, snap.habit.currentStreak)
        assertEquals(3, snap.habit.bestStreak)
        // Window now starts at first activity (2026-05-14 → Sunday 2026-05-10) and grows to
        // a 12-week cap, so 2026-05-10..18 spans 2 week-columns rather than a fixed 12.
        assertEquals(2, snap.habit.weeks)
        assertTrue(snap.habit.cells.isNotEmpty())
    }

    @Test
    public fun goal_hit30DayWindowCountsOnlyDaysAtOrAboveGoal() {
        val days = listOf(
            day("2026-05-18", 8, 200),
            day("2026-05-17", 10, 250),
            day("2026-05-16", 7, 175),
            day("2026-05-10", 12, 300),
        )
        val snap = StatsAggregator.aggregate(
            days = days,
            sessions = emptyList(),
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertEquals(8, snap.goal.dailyGoal)
        assertEquals(3, snap.goal.daysHit)
        assertEquals(30, snap.goal.totalDays)
    }

    @Test
    public fun records_pickBestDayAndBestWeek() {
        // Sunday-anchored weeks:
        //  week of 2026-05-10 (Sun) → 2026-05-10..16
        //  week of 2026-05-17 (Sun) → 2026-05-17..23
        val days = listOf(
            day("2026-05-10", 5, 125),  // wk 5-10
            day("2026-05-12", 8, 200),  // wk 5-10
            day("2026-05-15", 6, 150),  // wk 5-10
            day("2026-05-17", 4, 100),  // wk 5-17
            day("2026-05-18", 3, 75),   // wk 5-17
        )
        val snap = StatsAggregator.aggregate(
            days = days,
            sessions = emptyList(),
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertNotNull(snap.records.bestDay)
        assertEquals("2026-05-12", snap.records.bestDay!!.date)
        assertEquals(8, snap.records.bestDay!!.sessions)
        assertNotNull(snap.records.bestWeek)
        assertEquals("2026-05-10", snap.records.bestWeek!!.weekStart)
        assertEquals(19, snap.records.bestWeek!!.sessions)
    }

    @Test
    public fun rhythm_ignoresAbortedAndBreakSessions() {
        val work = work("2026-05-15T10:00:00")
        val aborted = SessionEntity(
            start = startSec("2026-05-15T11:00:00"),
            date = "2026-05-15",
            type = "work",
            duration = 25 * 60,
            completed = false,
        )
        val breakSession = SessionEntity(
            start = startSec("2026-05-15T12:00:00"),
            date = "2026-05-15",
            type = "short",
            duration = 5 * 60,
            completed = true,
        )
        val snap = StatsAggregator.aggregate(
            days = listOf(day("2026-05-15", 1, 25)),
            sessions = listOf(work, aborted, breakSession),
            dailyGoal = 8,
            today = "2026-05-18",
            nowMs = ms("2026-05-18T10:00:00"),
            tz = utc,
        )
        assertEquals(10, snap.rhythm.peakHour)
        assertTrue(snap.rhythm.buckets[10] > 0)
        assertEquals(0, snap.rhythm.buckets[11])
        assertEquals(0, snap.rhythm.buckets[12])
    }
}
