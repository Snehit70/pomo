package com.pomo.crew

import android.content.Context
import com.pomo.db.HistoryCacheRepository
import com.pomo.util.UtilPreferenceManager

public class CrewRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = UtilPreferenceManager(appContext)
    private val historyRepository = HistoryCacheRepository(appContext)
    private val crewStore = CrewStore(appContext)
    private val relayStore = LocalCrewRelayStore(appContext)

    public fun identity(): CrewIdentity = CrewIdentity(
        privateKey = prefs.crewIdentityPrivateKey,
        publicKey = prefs.crewIdentityPublicKey,
    )

    public suspend fun currentBoard(): CrewBoard? {
        val membership = crewStore.loadMembership() ?: return null
        publishSelfSnapshot(membership)
        val rows = CrewLeaderboardAggregator.rank(
            crewId = membership.crewId,
            snapshots = relayStore.pull(membership.crewId, membership.key),
            selfIdentityPublicKey = identity().publicKey,
        )
        return CrewBoard(
            crewId = membership.crewId,
            joinCode = membership.joinCode,
            rows = rows,
        )
    }

    public suspend fun createSoloCrew(displayName: String): CrewBoard {
        val name = displayName.trim().ifBlank { "Me" }
        val payload = CrewJoinCodeCodec.newPayload()
        val joinCode = CrewJoinCodeCodec.encode(payload)
        val membership = CrewMembership(
            crewId = payload.crewId,
            joinCode = joinCode,
            relays = payload.relays,
            key = payload.key,
            displayName = name,
        )
        crewStore.saveMembership(membership)
        publishSelfSnapshot(membership)
        return currentBoard() ?: CrewBoard(payload.crewId, joinCode, emptyList())
    }

    private suspend fun publishSelfSnapshot(membership: CrewMembership) {
        val identity = identity()
        val history = historyRepository.getHistoryPayload()
        val focusMinutes = history.values.sumOf { it.work_minutes }
        val snapshot = CrewSnapshot(
            crewId = membership.crewId,
            identityPublicKey = identity.publicKey,
            displayName = membership.displayName,
            allTimeFocusMinutes = focusMinutes,
            publishedAtEpochSeconds = System.currentTimeMillis() / 1000L,
        )
        relayStore.publish(
            crewId = membership.crewId,
            identityPublicKey = identity.publicKey,
            payload = CrewSnapshotCodec.encodeEncrypted(snapshot, membership.key, identity),
            relays = membership.relays,
        )
    }
}
