package com.pomo.crew

public data class CrewIdentity(
    val privateKey: String,
    val publicKey: String,
)

public data class CrewJoinPayload(
    val crewId: String,
    val crewName: String,
    val relays: List<String>,
    val key: String,
    val version: Int = CrewDefaults.PROTOCOL_VERSION,
)

public data class CrewJoinPreview(
    val activeMembers: Int,
    val todayFocusMinutes: Int,
    val medianMemberFocusMinutes: Int,
    val knownDisplayNames: Set<String>,
)

public data class CrewDailyAggregate(
    val localDate: String,
    val focusMinutes: Int,
    val completedWorkBlocks: Int,
)

/**
 * Everything a member shares beyond the leaderboard's own needs, so their stats page can be
 * drawn at the same fidelity as your own.
 *
 * Every field is optional and rides inside protocol version 2: builds that predate it ignore the
 * whole object (Gson drops unknown keys), and builds that postdate a member's build see nulls.
 * Bumping [CrewDefaults.PROTOCOL_VERSION] instead would make older clients reject the snapshot
 * outright and silently drop that member from their board.
 */
public data class CrewStatsExtras(
    /** All-time focus minutes bucketed by hour of day, 24 slots, member's local clock. */
    val hourBuckets: List<Int>? = null,
    /** All-time focus minutes by weekday, 7 slots, Monday first. */
    val weekdayBuckets: List<Int>? = null,
    val allTimeWorkBlocks: Int? = null,
    /** All-time local dates containing at least one completed Work block. */
    val allTimeActiveDays: Int? = null,
    val bestStreak: Int? = null,
    val firstFocusLocalDate: String? = null,
    /** Dense daily history: index 0 is [historyStartDate], one entry per day up to today. */
    val historyStartDate: String? = null,
    val historyFocusMinutes: List<Int>? = null,
    val historyWorkBlocks: List<Int>? = null,
    /** All-time records, sent whole because the shared history window may not contain them. */
    val bestDayLocalDate: String? = null,
    val bestDayFocusMinutes: Int? = null,
    val bestDayWorkBlocks: Int? = null,
    val bestWeekStartDate: String? = null,
    val bestWeekFocusMinutes: Int? = null,
    val bestWeekWorkBlocks: Int? = null,
    val bestMonthStartDate: String? = null,
    val bestMonthFocusMinutes: Int? = null,
    val bestMonthWorkBlocks: Int? = null,
    /** Aggregated tag counts only; raw session timestamps and tag-to-session links are not shared. */
    val tagBreakdown: List<CrewTagAggregate>? = null,
)

public data class CrewTagRangeStats(
    val sessions: Int = 0,
    val focusMinutes: Int = 0,
)

/** One named tag, or the neutral overflow category, summarized over the Stats range choices. */
public data class CrewTagAggregate(
    /** Empty only when [isOther] is true. */
    val tag: String,
    val isOther: Boolean = false,
    val today: CrewTagRangeStats = CrewTagRangeStats(),
    val yesterday: CrewTagRangeStats = CrewTagRangeStats(),
    val sevenDays: CrewTagRangeStats = CrewTagRangeStats(),
    val thirtyDays: CrewTagRangeStats = CrewTagRangeStats(),
    val allTime: CrewTagRangeStats = CrewTagRangeStats(),
)

public data class CrewSnapshot(
    val crewId: String,
    val identityPublicKey: String,
    val displayName: String,
    val avatarBase64: String? = null,
    val allTimeFocusMinutes: Int,
    val publishedAtEpochSeconds: Long,
    val localDate: String,
    val utcOffsetMinutes: Int,
    val dailyAggregates: List<CrewDailyAggregate>,
    val currentStreak: Int,
    val lastFocusedAtEpochSeconds: Long,
    val version: Int = CrewDefaults.PROTOCOL_VERSION,
    val stats: CrewStatsExtras? = null,
) {
    public val todayFocusMinutes: Int
        get() = dailyAggregates.firstOrNull { it.localDate == localDate }?.focusMinutes ?: 0

    public val todaySessionCount: Int
        get() = dailyAggregates.firstOrNull { it.localDate == localDate }?.completedWorkBlocks ?: 0
}

public data class CrewBoardRow(
    val rank: Int?,
    val identityPublicKey: String,
    val displayName: String,
    val avatarBase64: String? = null,
    val allTimeFocusMinutes: Int,
    val todayFocusMinutes: Int,
    val sevenDayFocusMinutes: Int,
    val thirtyDayFocusMinutes: Int,
    val selectedFocusMinutes: Int,
    val currentStreak: Int,
    val todaySessionCount: Int,
    val selectedSessionCount: Int,
    val lastFocusedAtEpochSeconds: Long,
    val dailyAggregates: List<CrewDailyAggregate>,
    val isSelf: Boolean,
    val isStale: Boolean = false,
    val isInactive: Boolean = false,
    /** The member's own calendar date when they published — their "today", not yours. */
    val localDate: String = "",
    val stats: CrewStatsExtras? = null,
)

/**
 * The window the leaderboard ranks over. [Day] covers any single past date; snapshots carry
 * [CrewValidation.MAX_DAILY_AGGREGATES] days of history, so dates older than that rank as zero.
 */
public sealed interface CrewRankingMode {
    public data object Today : CrewRankingMode

    public data object Yesterday : CrewRankingMode

    public data object SevenDays : CrewRankingMode

    public data object ThirtyDays : CrewRankingMode

    public data object AllTime : CrewRankingMode

    public data class Day(val localDate: String) : CrewRankingMode
}

public data class CrewBoard(
    val crewId: String,
    val crewName: String,
    val joinCode: String,
    val rows: List<CrewBoardRow>,
    val hiddenMembers: List<CrewHiddenMember> = emptyList(),
    val rankingMode: CrewRankingMode = CrewRankingMode.Today,
    val lastUpdatedEpochSeconds: Long? = null,
    val successfulRelayCount: Int = 0,
    val totalRelayCount: Int = 0,
    val memberships: List<CrewMembershipSummary> = emptyList(),
    val displayName: String = "",
)

public data class CrewHiddenMember(
    val identityPublicKey: String,
    val displayName: String,
    val selectedFocusMinutes: Int,
)

public data class CrewMembership(
    val crewId: String,
    val crewName: String,
    val joinCode: String,
    val relays: List<String>,
    val key: String,
    val displayName: String,
    val protocolVersion: Int = CrewDefaults.PROTOCOL_VERSION,
    val isArchived: Boolean = false,
)

public data class CrewMembershipSummary(
    val crewId: String,
    val crewName: String,
    val displayName: String,
    val isActive: Boolean,
    val isArchived: Boolean = false,
)
