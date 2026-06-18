package com.pomo.crew

public data class CrewIdentity(
    val privateKey: String,
    val publicKey: String,
)

public data class CrewJoinPayload(
    val crewId: String,
    val relays: List<String>,
    val key: String,
)

public data class CrewSnapshot(
    val crewId: String,
    val identityPublicKey: String,
    val displayName: String,
    val allTimeFocusMinutes: Int,
    val publishedAtEpochSeconds: Long,
)

public data class CrewBoardRow(
    val rank: Int,
    val identityPublicKey: String,
    val displayName: String,
    val allTimeFocusMinutes: Int,
    val isSelf: Boolean,
)

public data class CrewBoard(
    val crewId: String,
    val joinCode: String,
    val rows: List<CrewBoardRow>,
)

public data class CrewMembership(
    val crewId: String,
    val joinCode: String,
    val relays: List<String>,
    val key: String,
    val displayName: String,
)
