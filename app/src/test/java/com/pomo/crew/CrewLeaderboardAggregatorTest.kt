package com.pomo.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class CrewLeaderboardAggregatorTest {
    @Test
    public fun rank_sortsByAllTimeFocusMinutesAndMarksSelf() {
        val now = 10_000_000L
        val rows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = listOf(
                snapshot("crew-1", "self", "Me", 50, publishedAt = now),
                snapshot("crew-1", "friend", "Friend", 125, publishedAt = now),
                snapshot("other", "other", "Other", 500, publishedAt = now),
            ),
            nowEpochSeconds = now,
        )

        assertEquals(2, rows.size)
        assertEquals("Friend", rows[0].displayName)
        assertEquals(125, rows[0].allTimeFocusMinutes)
        assertEquals("Me", rows[1].displayName)
        assertTrue(rows[1].isSelf)
    }

    @Test
    public fun rank_keepsLatestSnapshotPerIdentity() {
        val now = 10_000_000L
        val rows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = listOf(
                snapshot("crew-1", "friend", "Old Friend", 300, publishedAt = now - 1),
                snapshot("crew-1", "friend", "Friend", 25, publishedAt = now),
                snapshot("crew-1", "self", "Me", 50, publishedAt = now - 1),
            ),
            nowEpochSeconds = now,
        )

        assertEquals(2, rows.size)
        assertEquals("Me", rows[0].displayName)
        assertEquals("Friend", rows[1].displayName)
        assertEquals(25, rows[1].allTimeFocusMinutes)
    }

    @Test
    public fun rank_ordersMultipleMembersByAllTimeFocusMinutes() {
        val now = 10_000_000L
        val rows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = listOf(
                snapshot("crew-1", "self", "Me", 90, publishedAt = now),
                snapshot("crew-1", "friend-a", "Asha", 120, publishedAt = now),
                snapshot("crew-1", "friend-b", "Bo", 15, publishedAt = now),
            ),
            nowEpochSeconds = now,
        )

        assertEquals(listOf("Asha", "Me", "Bo"), rows.map { it.displayName })
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
    }

    @Test
    public fun rank_todayModeOrdersByTodayFocusMinutes() {
        val now = 10_000_000L
        val snapshots = listOf(
            snapshot("crew-1", "self", "Me", minutes = 500, publishedAt = now, todayMinutes = 10),
            snapshot("crew-1", "friend", "Friend", minutes = 50, publishedAt = now, todayMinutes = 90),
        )

        val allTimeRows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = snapshots,
            mode = CrewRankingMode.AllTime,
            nowEpochSeconds = now,
        )
        val todayRows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = snapshots,
            mode = CrewRankingMode.Today,
            nowEpochSeconds = now,
        )

        assertEquals(listOf("Me", "Friend"), allTimeRows.map { it.displayName })
        assertEquals(listOf("Friend", "Me"), todayRows.map { it.displayName })
    }

    @Test
    public fun rank_marksSevenDaySilentMembersStaleAndDropsThirtyDaySilentMembersFromAllTime() {
        val now = 10_000_000L
        val snapshots = listOf(
            snapshot("crew-1", "fresh", "Fresh", minutes = 10, publishedAt = now),
            snapshot("crew-1", "stale", "Stale", minutes = 90, publishedAt = now - (8 * DAY_SECONDS)),
            snapshot("crew-1", "gone", "Gone", minutes = 500, publishedAt = now - (31 * DAY_SECONDS)),
        )

        val allTimeRows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "fresh",
            snapshots = snapshots,
            mode = CrewRankingMode.AllTime,
            nowEpochSeconds = now,
        )
        val todayRows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "fresh",
            snapshots = snapshots,
            mode = CrewRankingMode.Today,
            nowEpochSeconds = now,
        )

        assertEquals(listOf("Stale", "Fresh"), allTimeRows.map { it.displayName })
        assertTrue(requireNotNull(allTimeRows.firstOrNull { it.displayName == "Stale" }).isStale)
        assertEquals(listOf("Fresh", "Gone", "Stale"), todayRows.map { it.displayName })
        assertTrue(requireNotNull(todayRows.firstOrNull { it.displayName == "Gone" }).isDroppedFromAllTime)
    }

    private fun snapshot(
        crewId: String,
        identity: String,
        name: String,
        minutes: Int,
        publishedAt: Long = 1,
        todayMinutes: Int = 0,
    ): CrewSnapshot =
        CrewSnapshot(
            crewId = crewId,
            identityPublicKey = identity,
            displayName = name,
            allTimeFocusMinutes = minutes,
            publishedAtEpochSeconds = publishedAt,
            todayFocusMinutes = todayMinutes,
            currentStreak = 1,
            todaySessionCount = 1,
            lastActiveEpochSeconds = publishedAt,
        )

    private companion object {
        private const val DAY_SECONDS: Long = 24L * 60L * 60L
    }
}
