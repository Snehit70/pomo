package com.pomo.crew

import android.content.Context
import com.pomo.db.HistoryCacheRepository
import com.pomo.timer.TimerState
import com.pomo.util.DateLogic
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.sample

public class CrewRepository(context: Context) {
    private val appContext = context.applicationContext
    private val identityStore = CrewIdentityStore(appContext)
    private val historyRepository = HistoryCacheRepository(appContext)
    private val crewStore = CrewStore(appContext)
    private val relayStore = LocalCrewRelayStore(appContext)

    public fun identity(): CrewIdentity = identityStore.identity()
    private val identityPublicKey: String
        get() = identityStore.publicKey()

    public suspend fun currentBoard(mode: CrewRankingMode = CrewRankingMode.Today): CrewBoard? {
        val membership = crewStore.loadMembership() ?: return null
        val memberships = crewStore.loadMemberships() + crewStore.loadArchivedMemberships()
        return relayStore.loadProjection(membership.crewId)
            .toBoard(membership, memberships, mode)
    }

    public fun currentArchivedMemberships(): List<CrewMembershipSummary> =
        crewStore.loadArchivedMemberships()
            .sortedBy { it.crewName }
            .map { membership ->
                CrewMembershipSummary(
                    crewId = membership.crewId,
                    crewName = membership.crewName,
                    displayName = membership.displayName,
                    isActive = false,
                    isArchived = true,
                )
            }

    public suspend fun publishCurrentSnapshot(): Boolean {
        val memberships = crewStore.loadMemberships()
        if (memberships.isEmpty()) return false
        memberships.forEach { membership -> publishSelfSnapshot(membership) }
        return true
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    public fun observeCurrentBoard(rankingMode: Flow<CrewRankingMode>): Flow<CrewBoard> {
        val membership = crewStore.loadMembership() ?: return emptyFlow()
        val memberships = crewStore.loadMemberships()
        return relayStore.observeProjection(membership.crewId)
            .combine(rankingMode) { projection, mode -> projection.toBoard(membership, memberships, mode) }
            .sample(100L)
    }

    public fun refreshCurrentCrew(): Flow<CrewRelayResult> {
        val membership = crewStore.loadMembership() ?: return emptyFlow()
        return relayStore.refresh(membership.crewId, membership.key, membership.relays)
    }

    public fun observeLiveSnapshots(): Flow<CrewSnapshot> {
        val membership = crewStore.loadMembership() ?: return emptyFlow()
        return relayStore.observe(membership.crewId, membership.key, membership.relays)
    }

    public suspend fun republishCurrentCrewIfStale(maxAgeSeconds: Long = REPUBLISH_MAX_AGE_SECONDS): Boolean {
        val membership = crewStore.loadMembership() ?: return false
        val lastSuccessfulPublish = relayStore.lastPublishSuccessEpochSeconds(membership.crewId)
        val now = System.currentTimeMillis() / 1000L
        if (lastSuccessfulPublish != null && now - lastSuccessfulPublish < maxAgeSeconds) return false
        publishSelfSnapshot(membership)
        return true
    }

    /**
     * Publish a catch-up snapshot for any crew whose last successful publish predates the
     * newest completed work block in local history. Covers blocks written to Room while the
     * service was dead (so no onTimerComplete publish fired) — including blocks that completed
     * on a previous day, which a same-day completed-count comparison can't detect.
     */
    public suspend fun republishStaleLocalHistory(): Boolean {
        val memberships = crewStore.loadMemberships()
        if (memberships.isEmpty()) return false
        val newestWorkEnd = historyRepository.getHistoryPayload().values
            .flatMap { it.sessions }
            .filter { it.type == TimerState.PHASE_WORK && it.completed }
            .maxOfOrNull { it.start + it.duration }
            ?: return false
        var published = false
        memberships.forEach { membership ->
            val lastPublish = relayStore.lastPublishSuccessEpochSeconds(membership.crewId)
            if (lastPublish == null || lastPublish < newestWorkEnd) {
                publishSelfSnapshot(membership)
                published = true
            }
        }
        return published
    }

    public suspend fun createSoloCrew(displayName: String, crewName: String = "${displayName.ifBlank { "My" }} Crew"): CrewBoard {
        val name = CrewValidation.normalizeDisplayName(displayName)
            ?: crewStore.loadMembership()?.displayName
            ?: "Me"
        val payload = CrewJoinCodeCodec.newPayload(crewName)
        val joinCode = CrewJoinCodeCodec.encode(payload)
        val membership = CrewMembership(
            crewId = payload.crewId,
            crewName = payload.crewName,
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
                crewName = payload.crewName,
                joinCode = joinCode,
                rows = emptyList(),
                memberships = listOf(CrewMembershipSummary(payload.crewId, payload.crewName, name, isActive = true)),
                displayName = name,
            )
        }
        publishSelfSnapshot(membership)
        return currentBoard() ?: CrewBoard(
            crewId = payload.crewId,
            crewName = payload.crewName,
            joinCode = joinCode,
            rows = emptyList(),
            memberships = listOf(CrewMembershipSummary(payload.crewId, payload.crewName, name, isActive = true)),
            displayName = name,
        )
    }

    public suspend fun joinCrew(joinCode: String, displayName: String): CrewBoard? {
        val payload = CrewJoinCodeCodec.decode(joinCode.trim()) ?: return null
        val existingName = crewStore.loadMemberships().firstOrNull()?.displayName
        val name = CrewValidation.normalizeDisplayName(displayName) ?: existingName ?: "Me"
        val membership = CrewMembership(
            crewId = payload.crewId,
            crewName = payload.crewName,
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
        relayStore.deleteProjection(crewId)
        return currentBoard()
    }

    public suspend fun updateDisplayName(displayName: String): CrewBoard? {
        if (displayName.isBlank()) return currentBoard()
        val memberships = crewStore.updateDisplayName(displayName)
        memberships.forEach { publishSelfSnapshot(it) }
        return currentBoard()
    }

    public suspend fun setMemberHidden(identityPublicKey: String, hidden: Boolean): CrewBoard? {
        val membership = crewStore.loadMembership() ?: return null
        relayStore.setHidden(membership.crewId, identityPublicKey, hidden)
        return currentBoard()
    }

    public fun createRecovery(passphrase: CharArray): String = CrewRecoveryCodec.encode(
        CrewRecoveryPayload(
            identityPrivateKey = identity().privateKey,
            memberships = crewStore.loadMemberships(),
        ),
        passphrase,
    )

    public fun restoreRecovery(recovery: String, passphrase: CharArray): Boolean {
        val payload = CrewRecoveryCodec.decode(recovery, passphrase) ?: return false
        val previousIdentity = identity().privateKey
        val previousMemberships = crewStore.loadMemberships()
        return runCatching {
            identityStore.replaceIdentity(payload.identityPrivateKey)
            crewStore.replaceMemberships(payload.memberships)
            true
        }.getOrElse {
            identityStore.replaceIdentity(previousIdentity)
            if (previousMemberships.isNotEmpty()) crewStore.replaceMemberships(previousMemberships)
            false
        }
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
        val nowSeconds = System.currentTimeMillis() / 1000L
        val zoneId = ZoneId.systemDefault()
        val nowInstant = Instant.ofEpochSecond(nowSeconds)
        val dailyAggregates = history.entries
            .sortedByDescending { it.key }
            .take(CrewValidation.MAX_DAILY_AGGREGATES)
            .map { (date, entry) ->
                CrewDailyAggregate(
                    localDate = date,
                    focusMinutes = entry.work_minutes,
                    completedWorkBlocks = entry.completed,
                )
            }
        val lastFocusedAt = history.values
            .flatMap { it.sessions }
            .filter { it.type == TimerState.PHASE_WORK && it.completed }
            .maxOfOrNull { it.start + it.duration }
            ?: 0L
        val snapshot = CrewSnapshot(
            crewId = membership.crewId,
            identityPublicKey = identity.publicKey,
            displayName = membership.displayName,
            allTimeFocusMinutes = focusMinutes,
            publishedAtEpochSeconds = nowSeconds,
            localDate = today,
            utcOffsetMinutes = zoneId.rules.getOffset(nowInstant).totalSeconds / 60,
            dailyAggregates = dailyAggregates,
            currentStreak = DateLogic.currentStreak(activeDates, System.currentTimeMillis()),
            lastFocusedAtEpochSeconds = lastFocusedAt,
        )
        relayStore.publish(
            snapshot = snapshot,
            payload = CrewSnapshotCodec.encodeEncrypted(snapshot, membership.key, identity),
            relays = membership.relays,
        )
    }

    private fun CrewProjection.toBoard(
        membership: CrewMembership,
        memberships: List<CrewMembership>,
        mode: CrewRankingMode,
    ): CrewBoard {
        val visibleSnapshots = snapshots.filterNot { it.identityPublicKey in hiddenIdentityPublicKeys }
        val hiddenMembers = snapshots
            .filter { it.identityPublicKey in hiddenIdentityPublicKeys }
            .sortedWith(
                compareByDescending<CrewSnapshot> { it.selectedFocusMinutes(mode) }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.identityPublicKey },
            )
            .map { snapshot ->
                CrewHiddenMember(
                    identityPublicKey = snapshot.identityPublicKey,
                    displayName = snapshot.displayName,
                    selectedFocusMinutes = snapshot.selectedFocusMinutes(mode),
                )
            }
        return CrewBoard(
            crewId = membership.crewId,
            crewName = membership.crewName,
            joinCode = membership.joinCode,
            rows = CrewLeaderboardAggregator.rank(
                crewId = membership.crewId,
                snapshots = visibleSnapshots,
                selfIdentityPublicKey = identityPublicKey,
                mode = mode,
            ),
            hiddenMembers = hiddenMembers,
            rankingMode = mode,
            lastUpdatedEpochSeconds = snapshots.maxOfOrNull { it.publishedAtEpochSeconds },
            successfulRelayCount = relayStates.count { it.lastSuccessEpochSeconds != null },
            totalRelayCount = membership.relays.size,
            memberships = memberships.summaries(activeCrewId = membership.crewId),
            displayName = membership.displayName,
        )
    }

    private fun List<CrewMembership>.summaries(activeCrewId: String): List<CrewMembershipSummary> =
        sortedBy { it.crewId }.map { membership ->
            CrewMembershipSummary(
                crewId = membership.crewId,
                crewName = membership.crewName,
                displayName = membership.displayName,
                isActive = membership.crewId == activeCrewId,
                isArchived = membership.isArchived,
            )
        }

    private companion object {
        private const val REPUBLISH_MAX_AGE_SECONDS: Long = 24L * 60L * 60L
    }
}

internal fun CrewSnapshot.selectedFocusMinutes(mode: CrewRankingMode): Int {
    val today = LocalDate.parse(localDate)
    return when (mode) {
        CrewRankingMode.Today -> todayFocusMinutes
        CrewRankingMode.SevenDays -> focusMinutesBetween(today.minusDays(6), today)
        CrewRankingMode.ThirtyDays -> focusMinutesBetween(today.minusDays(29), today)
        CrewRankingMode.AllTime -> allTimeFocusMinutes
    }
}

internal fun CrewSnapshot.focusMinutesBetween(startDate: LocalDate, endDate: LocalDate): Int =
    dailyAggregates.sumOf { aggregate ->
        val date = LocalDate.parse(aggregate.localDate)
        if (date < startDate || date > endDate) 0 else aggregate.focusMinutes
    }
