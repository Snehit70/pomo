package com.pomo.crew

import android.content.Context
import com.pomo.db.HistoryCacheRepository
import com.pomo.util.UtilPreferenceManager
import com.pomo.util.DateLogic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

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
        val memberships = crewStore.loadMemberships()
        val rows = CrewLeaderboardAggregator.rank(
            crewId = membership.crewId,
            snapshots = relayStore.pull(membership.crewId, membership.key, membership.relays),
            selfIdentityPublicKey = identity().publicKey,
            mode = CrewRankingMode.Today,
        )
        return CrewBoard(
            crewId = membership.crewId,
            joinCode = membership.joinCode,
            rows = rows,
            memberships = memberships.summaries(activeCrewId = membership.crewId),
            displayName = membership.displayName,
        )
    }

    public suspend fun publishCurrentSnapshot(): Boolean {
        val memberships = crewStore.loadMemberships()
        if (memberships.isEmpty()) return false
        memberships.forEach { membership -> publishSelfSnapshot(membership) }
        return true
    }

    public fun observeCurrentBoard(): Flow<CrewBoard> {
        val membership = crewStore.loadMembership() ?: return emptyFlow()
        return relayStore.observe(membership.crewId, membership.key, membership.relays)
            .mapNotNull { currentBoard() }
    }

    public suspend fun createSoloCrew(displayName: String): CrewBoard {
        val name = displayName.trim().ifBlank { crewStore.loadMembership()?.displayName ?: "Me" }
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
        if (displayName.isNotBlank()) {
            crewStore.updateDisplayName(name).forEach { updatedMembership ->
                publishSelfSnapshot(updatedMembership)
            }
            return currentBoard() ?: CrewBoard(
                crewId = payload.crewId,
                joinCode = joinCode,
                rows = emptyList(),
                memberships = listOf(CrewMembershipSummary(payload.crewId, name, isActive = true)),
                displayName = name,
            )
        }
        publishSelfSnapshot(membership)
        return currentBoard() ?: CrewBoard(
            crewId = payload.crewId,
            joinCode = joinCode,
            rows = emptyList(),
            memberships = listOf(CrewMembershipSummary(payload.crewId, name, isActive = true)),
            displayName = name,
        )
    }

    public suspend fun joinCrew(joinCode: String, displayName: String): CrewBoard? {
        val payload = CrewJoinCodeCodec.decode(joinCode.trim()) ?: return null
        val existingName = crewStore.loadMemberships().firstOrNull()?.displayName
        val name = displayName.trim().ifBlank { existingName ?: "Me" }
        val membership = CrewMembership(
            crewId = payload.crewId,
            joinCode = CrewJoinCodeCodec.encode(payload),
            relays = payload.relays,
            key = payload.key,
            displayName = name,
        )
        crewStore.saveMembership(membership)
        if (displayName.isNotBlank()) {
            crewStore.updateDisplayName(name).forEach { updatedMembership ->
                publishSelfSnapshot(updatedMembership)
            }
            return currentBoard()
        }
        publishSelfSnapshot(membership)
        return currentBoard()
    }

    public suspend fun switchCrew(crewId: String): CrewBoard? {
        if (!crewStore.selectCrew(crewId)) return currentBoard()
        return currentBoard()
    }

    public suspend fun leaveCrew(crewId: String): CrewBoard? {
        crewStore.leaveCrew(crewId) ?: return currentBoard()
        return currentBoard()
    }

    public suspend fun updateDisplayName(displayName: String): CrewBoard? {
        if (displayName.isBlank()) return currentBoard()
        val memberships = crewStore.updateDisplayName(displayName)
        memberships.forEach { publishSelfSnapshot(it) }
        return currentBoard()
    }

    private suspend fun publishSelfSnapshot(membership: CrewMembership) {
        val identity = identity()
        val history = historyRepository.getHistoryPayload()
        val today = historyRepository.getEffectiveDateString()
        val activeDates = history
            .filter { it.value.completed > 0 }
            .keys
            .toSet()
        val focusMinutes = history.values.sumOf { it.work_minutes }
        val todayEntry = history[today]
        val nowSeconds = System.currentTimeMillis() / 1000L
        val snapshot = CrewSnapshot(
            crewId = membership.crewId,
            identityPublicKey = identity.publicKey,
            displayName = membership.displayName,
            allTimeFocusMinutes = focusMinutes,
            publishedAtEpochSeconds = nowSeconds,
            todayFocusMinutes = todayEntry?.work_minutes ?: 0,
            currentStreak = DateLogic.currentStreak(activeDates, System.currentTimeMillis()),
            todaySessionCount = todayEntry?.completed ?: 0,
            lastActiveEpochSeconds = nowSeconds,
        )
        relayStore.publish(
            crewId = membership.crewId,
            identityPublicKey = identity.publicKey,
            payload = CrewSnapshotCodec.encodeEncrypted(snapshot, membership.key, identity),
            relays = membership.relays,
        )
    }

    private fun List<CrewMembership>.summaries(activeCrewId: String): List<CrewMembershipSummary> =
        sortedBy { it.crewId }.map { membership ->
            CrewMembershipSummary(
                crewId = membership.crewId,
                displayName = membership.displayName,
                isActive = membership.crewId == activeCrewId,
            )
        }
}
