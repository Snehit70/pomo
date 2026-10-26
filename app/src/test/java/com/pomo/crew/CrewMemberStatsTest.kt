package com.pomo.crew

import com.pomo.stats.RhythmPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

public class CrewMemberStatsTest {
    private val today: LocalDate = LocalDate.of(2026, 6, 21)
    private val nowMs: Long = 1_781_000_000_000L

    @Test
    public fun snapshotWithoutExtrasStaysValid() {
        assertTrue(CrewValidation.isValidSnapshot(snapshot(stats = null)))
    }

    @Test
    public fun malformedBucketsRejectTheSnapshot() {
        val stats = CrewStatsExtras(hourBuckets = List(23) { 0 })
        assertFalse(CrewValidation.isValidSnapshot(snapshot(stats = stats)))
    }

    @Test
    public fun negativeAllTimeActiveDaysRejectsTheSnapshot() {
        assertFalse(CrewValidation.isValidSnapshot(snapshot(stats = CrewStatsExtras(allTimeActiveDays = -1))))
    }

    @Test
    public fun historyFieldsMustArriveTogetherAndMatchInLength() {
        val partial = CrewStatsExtras(historyStartDate = today.toString())
        assertFalse(CrewValidation.isValidSnapshot(snapshot(stats = partial)))

        val mismatched =
            CrewStatsExtras(
                historyStartDate = today.toString(),
                historyFocusMinutes = listOf(30, 40),
                historyWorkBlocks = listOf(1),
            )
        assertFalse(CrewValidation.isValidSnapshot(snapshot(stats = mismatched)))
    }

    @Test
    public fun memberStatsUseSharedLifetimeAndRecordsRatherThanTheHistoryWindow() {
        val stats =
            CrewStatsExtras(
                hourBuckets =
                    IntArray(24).also {
                        it[9] = 300
                        it[10] = 240
                    }.toList(),
                weekdayBuckets = IntArray(7).also { it[2] = 400 }.toList(),
                allTimeWorkBlocks = 210,
                allTimeActiveDays = 140,
                bestStreak = 19,
                firstFocusLocalDate = "2025-06-21",
                historyStartDate = today.minusDays(2).toString(),
                historyFocusMinutes = listOf(50, 0, 90),
                historyWorkBlocks = listOf(2, 0, 3),
                // Their best day predates the shared window: it must survive the trip anyway.
                bestDayLocalDate = "2025-11-02",
                bestDayFocusMinutes = 480,
                bestDayWorkBlocks = 16,
                bestWeekStartDate = "2025-10-26",
                bestWeekFocusMinutes = 1_500,
                bestWeekWorkBlocks = 50,
                bestMonthStartDate = "2025-09-01",
                bestMonthFocusMinutes = 6_200,
                bestMonthWorkBlocks = 205,
                tagBreakdown =
                    listOf(
                        CrewTagAggregate(
                            tag = "machine learning",
                            today = CrewTagRangeStats(sessions = 2, focusMinutes = 50),
                            allTime = CrewTagRangeStats(sessions = 43, focusMinutes = 2_122),
                        ),
                    ),
            )
        val row = board(snapshot(stats = stats))

        val memberStats = row.toStatsSnapshot(nowMs = nowMs, timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals(5_000, memberStats.lifetime.focusMinutes)
        assertEquals(210, memberStats.lifetime.sessions)
        assertEquals(140, memberStats.lifetime.activeDays)
        assertEquals("2025-06-21", memberStats.lifetime.firstDate)
        assertEquals(366, memberStats.lifetime.daysWithApp)
        assertEquals(9, memberStats.rhythm.peakHour)
        assertEquals(RhythmPattern.Morning, memberStats.rhythm.pattern)
        assertEquals(2, memberStats.weekShape.strongestDayIndex)
        assertEquals(480, memberStats.records.bestDay?.minutes)
        assertEquals(1_500, memberStats.records.bestWeek?.minutes)
        assertEquals("2025-09-01", memberStats.records.bestMonth?.monthStart)
        assertEquals(6_200, memberStats.records.bestMonth?.minutes)
        // Shared tag aggregates reach the crew member screen without any raw sessions.
        assertEquals("machine learning", row.stats?.tagBreakdown?.single()?.tag)
        assertEquals(43, row.stats?.tagBreakdown?.single()?.allTime?.sessions)
        assertEquals(19, memberStats.records.longestStreak)
        assertEquals(4, memberStats.habit.currentStreak)
        assertTrue(row.hasFullStats())
    }

    @Test
    public fun memberOnAnOlderBuildStillGetsAPageFromDailyAggregatesAlone() {
        val row = board(snapshot(stats = null))

        val memberStats = row.toStatsSnapshot(nowMs = nowMs, timeZone = TimeZone.getTimeZone("UTC"))

        assertFalse(row.hasFullStats())
        assertEquals(5_000, memberStats.lifetime.focusMinutes)
        assertEquals(2, memberStats.lifetime.activeDays)
        assertEquals(RhythmPattern.None, memberStats.rhythm.pattern)
        // Falls back to the 30 daily aggregates every snapshot has always carried.
        assertEquals(120, memberStats.records.bestDay?.minutes)
        // Thirty days of aggregates cannot prove an all-time monthly record.
        assertNull(memberStats.records.bestMonth)
    }

    @Test
    public fun malformedSharedTagAggregatesRejectTheSnapshot() {
        val duplicateTags =
            CrewStatsExtras(
                tagBreakdown =
                    listOf(
                        CrewTagAggregate(tag = "Work"),
                        CrewTagAggregate(tag = "Work"),
                    ),
            )
        assertFalse(CrewValidation.isValidSnapshot(snapshot(stats = duplicateTags)))

        val negativeCount =
            CrewStatsExtras(
                tagBreakdown =
                    listOf(
                        CrewTagAggregate(
                            tag = "Work",
                            allTime = CrewTagRangeStats(sessions = -1, focusMinutes = 20),
                        ),
                    ),
            )
        assertFalse(CrewValidation.isValidSnapshot(snapshot(stats = negativeCount)))
    }

    private fun board(snapshot: CrewSnapshot): CrewBoardRow =
        CrewLeaderboardAggregator.rank(
            crewId = snapshot.crewId,
            snapshots = listOf(snapshot),
            selfIdentityPublicKey = "ff".repeat(32),
            nowEpochSeconds = snapshot.publishedAtEpochSeconds,
        ).single()

    private fun snapshot(stats: CrewStatsExtras?): CrewSnapshot =
        CrewSnapshot(
            crewId = "cd".repeat(16),
            identityPublicKey = "01".repeat(32),
            displayName = "Ira",
            allTimeFocusMinutes = 5_000,
            publishedAtEpochSeconds = 1_781_000_000L,
            localDate = today.toString(),
            utcOffsetMinutes = 0,
            dailyAggregates =
                listOf(
                    CrewDailyAggregate(localDate = today.toString(), focusMinutes = 90, completedWorkBlocks = 3),
                    CrewDailyAggregate(localDate = today.minusDays(2).toString(), focusMinutes = 120, completedWorkBlocks = 4),
                ),
            currentStreak = 4,
            lastFocusedAtEpochSeconds = 1_781_000_000L,
            stats = stats,
        )
}
