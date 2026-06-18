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

    private fun snapshot(crewId: String, identity: String, name: String, minutes: Int): CrewSnapshot =
        CrewSnapshot(
            crewId = crewId,
            identityPublicKey = identity,
            displayName = name,
            allTimeFocusMinutes = minutes,
            publishedAtEpochSeconds = 1,
        )
}
