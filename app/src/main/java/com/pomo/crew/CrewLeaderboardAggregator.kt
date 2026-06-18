package com.pomo.crew

public object CrewLeaderboardAggregator {
    public fun rank(
        crewId: String,
        snapshots: List<CrewSnapshot>,
        selfIdentityPublicKey: String,
        mode: CrewRankingMode = CrewRankingMode.AllTime,
    ): List<CrewBoardRow> {
        return snapshots
            .filter { it.crewId == crewId }
            .groupBy { it.identityPublicKey }
            .values
            .mapNotNull { identitySnapshots -> identitySnapshots.maxByOrNull { it.publishedAtEpochSeconds } }
            .sortedWith(
                compareByDescending<CrewSnapshot> {
                    when (mode) {
                        CrewRankingMode.AllTime -> it.allTimeFocusMinutes
                        CrewRankingMode.Today -> it.todayFocusMinutes
                    }
                }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.identityPublicKey },
            )
            .mapIndexed { index, snapshot ->
                CrewBoardRow(
                    rank = index + 1,
                    identityPublicKey = snapshot.identityPublicKey,
                    displayName = snapshot.displayName,
                    allTimeFocusMinutes = snapshot.allTimeFocusMinutes,
                    todayFocusMinutes = snapshot.todayFocusMinutes,
                    currentStreak = snapshot.currentStreak,
                    todaySessionCount = snapshot.todaySessionCount,
                    lastActiveEpochSeconds = snapshot.lastActiveEpochSeconds,
                    isSelf = snapshot.identityPublicKey == selfIdentityPublicKey,
                )
            }
    }
}
