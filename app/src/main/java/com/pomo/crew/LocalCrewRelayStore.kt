package com.pomo.crew

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.util.Locale

public class LocalCrewRelayStore(context: Context) {
    private val appContext = context.applicationContext
    private val identityStore = CrewIdentityStore(appContext)
    private val projectionStore = CrewProjectionStore(appContext)

    public suspend fun publish(
        snapshot: CrewSnapshot,
        payload: String,
        relays: List<String>,
    ): Boolean {
        projectionStore.upsertLatest(snapshot)
        val results = transport().publishResults(snapshot.crewId, payload, relays)
        results.forEach { result ->
            projectionStore.recordPublishResult(
                crewId = snapshot.crewId,
                result = result,
                nowEpochSeconds = snapshot.publishedAtEpochSeconds,
            )
        }
        return results.any { it.accepted }
    }

    public suspend fun pull(
        crewId: String,
        crewKey: String,
        relays: List<String>,
    ): List<CrewSnapshot> {
        refresh(crewId, crewKey, relays).toList()
        return projectionStore.load(crewId).snapshots
    }

    public suspend fun preview(
        crewId: String,
        crewKey: String,
        relays: List<String>,
    ): CrewJoinPreview? {
        val results = transport().pullIncrementally(crewId, relays).toList()
        if (results.isEmpty()) return null

        val latestSnapshots =
            results
                .flatMap { result -> result.events }
                .mapNotNull { event -> decodeEvent(event, crewKey, persist = false) }
                .groupBy { snapshot -> snapshot.identityPublicKey }
                .values
                .mapNotNull { snapshots -> snapshots.maxByOrNull { it.publishedAtEpochSeconds } }
        if (latestSnapshots.isEmpty() && results.all { it.error != null }) return null

        val participating = latestSnapshots.filter { snapshot -> snapshot.todayFocusMinutes > 0 }
        val knownDisplayNames =
            latestSnapshots.mapTo(mutableSetOf()) { snapshot ->
                snapshot.displayName.trim().lowercase(Locale.ROOT)
            }
        return CrewJoinPreview(
            activeMembers = participating.size,
            todayFocusMinutes = participating.sumOf { snapshot -> snapshot.todayFocusMinutes },
            medianMemberFocusMinutes =
                participating
                    .map { snapshot -> snapshot.todayFocusMinutes }
                    .sorted()
                    .median(),
            knownDisplayNames = knownDisplayNames,
        )
    }

    public fun refresh(
        crewId: String,
        crewKey: String,
        relays: List<String>,
    ): Flow<CrewRelayResult> =
        flow {
            val relayTransport = transport()
            emitAll(
                relayTransport.pullIncrementally(crewId, relays)
                    .onEach { result ->
                        result.events.forEach { event -> acceptEvent(event, crewKey) }
                        projectionStore.recordRelayResult(crewId, result.relayUrl, result.error)
                    },
            )
        }

    public fun observe(
        crewId: String,
        crewKey: String,
        relays: List<String>,
    ): Flow<CrewSnapshot> =
        flow {
            val relayTransport = transport()
            emitAll(relayTransport.observe(crewId, relays).mapNotNull { event -> acceptEvent(event, crewKey) })
        }

    public fun observeProjection(crewId: String): Flow<CrewProjection> = projectionStore.observe(crewId)

    public suspend fun loadProjection(crewId: String): CrewProjection = projectionStore.load(crewId)

    public fun lastPublishSuccessEpochSeconds(crewId: String): Long? = projectionStore.lastPublishSuccessEpochSeconds(crewId)

    public suspend fun setHidden(
        crewId: String,
        identityPublicKey: String,
        hidden: Boolean,
    ) {
        projectionStore.setHidden(crewId, identityPublicKey, hidden)
    }

    public suspend fun deleteProjection(crewId: String) {
        projectionStore.delete(crewId)
    }

    private suspend fun acceptEvent(
        event: CrewRelayEvent,
        crewKey: String,
    ): CrewSnapshot? {
        return decodeEvent(event, crewKey, persist = true)
    }

    private suspend fun decodeEvent(
        event: CrewRelayEvent,
        crewKey: String,
        persist: Boolean,
    ): CrewSnapshot? {
        val snapshot =
            withContext(Dispatchers.Default) {
                CrewSnapshotCodec.decodeEncrypted(event.content, crewKey)
            } ?: return null
        val now = System.currentTimeMillis() / 1000L
        if (snapshot.identityPublicKey != event.authorPublicKey) return null
        if (event.createdAtEpochSeconds > now + MAX_CLOCK_SKEW_SECONDS) return null
        if (snapshot.publishedAtEpochSeconds > now + MAX_CLOCK_SKEW_SECONDS) return null
        if (snapshot.publishedAtEpochSeconds > event.createdAtEpochSeconds + MAX_CLOCK_SKEW_SECONDS) return null
        if (persist) projectionStore.upsertLatest(snapshot)
        return snapshot
    }

    private suspend fun transport(): CrewRelayTransport =
        withContext(Dispatchers.Default) {
            CrewRelayTransport(identityStore.identity().privateKey)
        }

    private fun List<Int>.median(): Int {
        if (isEmpty()) return 0
        val middle = size / 2
        return if (size % 2 == 1) this[middle] else (this[middle - 1] + this[middle]) / 2
    }

    private companion object {
        private const val MAX_CLOCK_SKEW_SECONDS: Long = 5 * 60L
    }
}
