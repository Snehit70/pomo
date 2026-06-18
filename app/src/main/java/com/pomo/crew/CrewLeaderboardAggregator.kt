package com.pomo.crew

public object CrewLeaderboardAggregator {
    public fun rank(
        crewId: String,
        snapshots: List<CrewSnapshot>,
        selfIdentityPublicKey: String,
    ): List<CrewBoardRow> {
        return snapshots
            .filter { it.crewId == crewId }
            .sortedWith(
                compareByDescending<CrewSnapshot> { it.allTimeFocusMinutes }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.identityPublicKey },
            )
            .mapIndexed { index, snapshot ->
                CrewBoardRow(
                    rank = index + 1,
                    identityPublicKey = snapshot.identityPublicKey,
                    displayName = snapshot.displayName,
                    allTimeFocusMinutes = snapshot.allTimeFocusMinutes,
                    isSelf = snapshot.identityPublicKey == selfIdentityPublicKey,
                )
            }
    }
}
