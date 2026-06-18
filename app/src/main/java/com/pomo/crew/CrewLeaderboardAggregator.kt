package com.pomo.crew

public object CrewLeaderboardAggregator {
    public fun rank(
        crewId: String,
        snapshots: List<CrewSnapshot>,
        selfIdentityPublicKey: String,
        mode: CrewRankingMode = CrewRankingMode.AllTime,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): List<CrewBoardRow> {
        return snapshots
            .filter { it.crewId == crewId }
            .groupBy { it.identityPublicKey }
            .values
            .mapNotNull { identitySnapshots -> identitySnapshots.maxByOrNull { it.publishedAtEpochSeconds } }
            .filterNot { snapshot ->
                mode == CrewRankingMode.AllTime && snapshot.isDroppedFromAllTime(nowEpochSeconds)
            }
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
                    isStale = snapshot.isStale(nowEpochSeconds),
                    isDroppedFromAllTime = snapshot.isDroppedFromAllTime(nowEpochSeconds),
                )
            }
    }

    private fun CrewSnapshot.isStale(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds - lastActiveEpochSeconds >= STALE_AFTER_SECONDS

    private fun CrewSnapshot.isDroppedFromAllTime(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds - lastActiveEpochSeconds >= DROP_FROM_ALL_TIME_AFTER_SECONDS

    private const val STALE_AFTER_SECONDS: Long = 7L * 24L * 60L * 60L
    private const val DROP_FROM_ALL_TIME_AFTER_SECONDS: Long = 30L * 24L * 60L * 60L
}
