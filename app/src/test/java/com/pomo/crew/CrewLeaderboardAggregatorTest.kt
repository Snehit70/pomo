package com.pomo.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class CrewLeaderboardAggregatorTest {
    @Test
    public fun rank_sortsByAllTimeFocusMinutesAndMarksSelf() {
        val rows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = listOf(
                snapshot("crew-1", "self", "Me", 50),
                snapshot("crew-1", "friend", "Friend", 125),
                snapshot("other", "other", "Other", 500),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals("Friend", rows[0].displayName)
        assertEquals(125, rows[0].allTimeFocusMinutes)
        assertEquals("Me", rows[1].displayName)
        assertTrue(rows[1].isSelf)
    }

    @Test
    public fun rank_keepsLatestSnapshotPerIdentity() {
        val rows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = listOf(
                snapshot("crew-1", "friend", "Old Friend", 300, publishedAt = 1),
                snapshot("crew-1", "friend", "Friend", 25, publishedAt = 2),
                snapshot("crew-1", "self", "Me", 50, publishedAt = 1),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals("Me", rows[0].displayName)
        assertEquals("Friend", rows[1].displayName)
        assertEquals(25, rows[1].allTimeFocusMinutes)
    }

    @Test
    public fun rank_ordersMultipleMembersByAllTimeFocusMinutes() {
        val rows = CrewLeaderboardAggregator.rank(
            crewId = "crew-1",
            selfIdentityPublicKey = "self",
            snapshots = listOf(
                snapshot("crew-1", "self", "Me", 90),
                snapshot("crew-1", "friend-a", "Asha", 120),
                snapshot("crew-1", "friend-b", "Bo", 15),
            ),
        )

        assertEquals(listOf("Asha", "Me", "Bo"), rows.map { it.displayName })
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
    }

    private fun snapshot(
        crewId: String,
        identity: String,
        name: String,
        minutes: Int,
        publishedAt: Long = 1,
    ): CrewSnapshot =
        CrewSnapshot(
            crewId = crewId,
            identityPublicKey = identity,
            displayName = name,
            allTimeFocusMinutes = minutes,
            publishedAtEpochSeconds = publishedAt,
        )
}
