package com.pomo.sync.protocol

internal fun interface OperationSigner {
    fun sign(
        operation: UnsignedOperation,
        canonicalPayload: ByteArray,
        canonicalUnsigned: ByteArray,
        operationId: ProtocolBytes,
    ): ByteArray
}

internal fun interface OperationVerifier {
    /** Decodes and authenticates one raw [COSE_Sign1, fact payload] wire. */
    fun verify(signedEnvelope: ByteArray): AuthenticatedOperation
}

internal fun interface OperationStore {
    /** Must return only after the authenticated bytes are durably committed. */
    fun append(operation: AuthenticatedOperation)
}

internal fun interface CheckpointVerifier {
    /** Returns normally only for an authenticated Checkpoint. */
    fun verify(checkpoint: KernelCheckpoint)
}

internal class OperationKernel(
    private val signer: OperationSigner,
    private val verifier: OperationVerifier,
    private val store: OperationStore,
    private val checkpointVerifier: CheckpointVerifier,
) {
    private data class FeedState(
        var head: Long = 0,
        var headId: ProtocolBytes? = null,
        var forkedAt: Long? = null,
        val accepted: MutableMap<Long, AuthenticatedOperation> = linkedMapOf(),
        val candidates: MutableMap<Long, AuthenticatedOperation> = linkedMapOf(),
        val pending: MutableMap<Long, Pair<AuthenticatedOperation, IngestDisposition>> = linkedMapOf(),
        val checkpointIds: MutableMap<Long, ProtocolBytes> = linkedMapOf(),
    )

    private val feeds: MutableMap<String, FeedState> = linkedMapOf()
    private val knownIds: MutableSet<ProtocolBytes> = linkedSetOf()
    private val quarantined: MutableSet<ProtocolBytes> = linkedSetOf()
    private val checkpointPreferences: MutableMap<String, String> = linkedMapOf()
    private val materializedPreferences: MutableMap<String, String> = linkedMapOf()

    fun author(request: AuthorRequest): AuthorResult {
        val missing = mutableSetOf<String>()
        if (!request.authorized) missing += "AUTHORIZATION"
        if (!request.deviceReady) missing += "DEVICE_READY"
        if ("PROFILE_FRONTIER" !in request.completePrerequisites) missing += "PROFILE_FRONTIER"
        val feed = feeds[feedKey(request.deviceId, request.incarnationId)]
        if (feed?.forkedAt != null) missing += "UNFORKED_FEED"
        if (feed?.pending?.isNotEmpty() == true) missing += "COMPLETE_LOCAL_FEED"
        if (missing.isNotEmpty()) return AuthorResult.Blocked(missing)

        val payload = OperationCodec.encodePreference(request.preference)
        val operation =
            UnsignedOperation(
                memberId = request.memberId,
                deviceId = request.deviceId,
                incarnationId = request.incarnationId,
                sequence = (feed?.head ?: 0) + 1,
                previousOperationId = feed?.headId,
                frontier =
                    request.frontier.sortedWith { left, right ->
                        compareBytes(left.deviceId.copy(), right.deviceId.copy()).takeIf { it != 0 }
                            ?: compareBytes(left.incarnationId.copy(), right.incarnationId.copy())
                    },
                authorizationEpoch = request.authorizationEpoch,
                payloadHash = OperationCodec.payloadHash(payload),
            )
        val canonical = OperationCodec.encodeUnsigned(operation)
        val operationId = OperationCodec.operationId(canonical)
        return runCatching {
            val wire = signer.sign(operation, payload, canonical, operationId)
            val authenticated = verifier.verify(wire)
            validateAuthenticated(wire, authenticated, operation, payload, canonical, operationId)
            store.append(authenticated)
            val disposition = ingestAuthenticated(authenticated)
            require(
                disposition != IngestDisposition.REJECTED_INVALID &&
                    disposition != IngestDisposition.REJECTED_UNSUPPORTED_SUITE,
            ) {
                "Locally authored Operation failed ingestion"
            }
            AuthorResult.Authored(authenticated, disposition)
        }.getOrElse { AuthorResult.Blocked(setOf("AUTHORING_COMMIT")) }
    }

    fun ingest(signedEnvelope: ByteArray): IngestDisposition {
        val authenticated =
            runCatching {
                verifier.verify(signedEnvelope).also { validateAuthenticated(signedEnvelope, it) }
            }.getOrElse { return IngestDisposition.REJECTED_INVALID }
        if (authenticated.operation.suite != PomoSuite.ID ||
            authenticated.operation.suiteGeneration != PomoSuite.INITIAL_GENERATION
        ) {
            return IngestDisposition.REJECTED_UNSUPPORTED_SUITE
        }
        if (runCatching { store.append(authenticated) }.isFailure) return IngestDisposition.REJECTED_INVALID
        return ingestAuthenticated(authenticated)
    }

    private fun ingestAuthenticated(authenticated: AuthenticatedOperation): IngestDisposition {
        val operation = authenticated.operation
        if (operation.suite != PomoSuite.ID || operation.suiteGeneration != PomoSuite.INITIAL_GENERATION) {
            return IngestDisposition.REJECTED_UNSUPPORTED_SUITE
        }
        if (authenticated.operationId in knownIds) return IngestDisposition.DUPLICATE

        val key = feedKey(operation.deviceId, operation.incarnationId)
        val feed = feeds[key] ?: FeedState()
        val checkpointId = feed.checkpointIds[operation.sequence]
        val existing = feed.candidates[operation.sequence]
        if ((checkpointId != null && checkpointId != authenticated.operationId) ||
            (existing != null && existing.operationId != authenticated.operationId)
        ) {
            knownIds += authenticated.operationId
            quarantineFork(feed, operation.sequence, authenticated, existing?.operationId ?: checkpointId)
            return IngestDisposition.QUARANTINED_FORK
        }
        if (feed.forkedAt?.let { operation.sequence >= it } == true) {
            feeds.putIfAbsent(key, feed)
            feed.candidates[operation.sequence] = authenticated
            knownIds += authenticated.operationId
            quarantined += authenticated.operationId
            return IngestDisposition.QUARANTINED_FORK
        }
        if (operation.sequence == feed.head + 1 && operation.previousOperationId != feed.headId) {
            return IngestDisposition.REJECTED_INVALID
        }
        if (operation.sequence <= feed.head) return IngestDisposition.REJECTED_INVALID
        feeds.putIfAbsent(key, feed)
        feed.candidates[operation.sequence] = authenticated
        knownIds += authenticated.operationId
        val result = attemptAccept(feed, authenticated)
        if (result == IngestDisposition.ACCEPTED) {
            drainAll()
            rematerialize()
        }
        return result
    }

    fun summarize(): KernelSummary {
        val heads = linkedMapOf<String, Pair<Long, ProtocolBytes?>>()
        val gaps = linkedSetOf<String>()
        val waits = linkedSetOf<String>()
        val forks = linkedSetOf<String>()
        var accepted = 0
        var pending = 0
        feeds.toSortedMap().forEach { (key, feed) ->
            heads[key] = feed.head to feed.headId
            accepted += feed.checkpointIds.size + feed.accepted.size
            pending += feed.pending.size
            if (feed.pending.isNotEmpty()) {
                val nextSequence = feed.head + 1
                if (feed.pending[nextSequence]?.second == IngestDisposition.PENDING_CAUSAL) {
                    waits += "$key@$nextSequence"
                } else {
                    gaps += "$key@$nextSequence"
                }
            }
            feed.forkedAt?.let { forks += "$key@$it" }
        }
        return KernelSummary(heads, gaps, waits, forks, accepted, pending, quarantined.size)
    }

    fun restore(
        checkpoint: KernelCheckpoint,
        trailing: List<ByteArray>,
    ): RestoreResult {
        if (checkpoint.suite != PomoSuite.ID || checkpoint.suiteGeneration != PomoSuite.INITIAL_GENERATION ||
            runCatching { checkpointVerifier.verify(checkpoint) }.isFailure
        ) {
            return RestoreResult.REJECTED_CHECKPOINT
        }
        val restored = linkedMapOf<String, FeedState>()
        val restoredPreferences = linkedMapOf<String, String>()
        if (
            runCatching {
                var previousKey: ByteArray? = null
                checkpoint.materializedPreferences.forEach { preference ->
                    OperationCodec.encodePreference(
                        PreferenceSet(preference.key, PreferenceValue.Text(preference.value)),
                    )
                    val keyBytes = preference.key.toByteArray(Charsets.UTF_8)
                    previousKey?.let {
                        require(compareBytes(it, keyBytes) < 0) {
                            "Checkpoint preference keys must be unique and bytewise sorted"
                        }
                    }
                    previousKey = keyBytes
                    restoredPreferences[preference.key] = preference.value
                }
            }.isFailure
        ) {
            return RestoreResult.REJECTED_CHECKPOINT
        }
        for (checkpointFeed in checkpoint.feeds) {
            val key = feedKey(checkpointFeed.deviceId, checkpointFeed.incarnationId)
            if (key in restored) return RestoreResult.REJECTED_CHECKPOINT
            if (checkpointFeed.coveredOperationIds.size != checkpointFeed.coveredOperationIds.toSet().size) {
                return RestoreResult.REJECTED_CHECKPOINT
            }
            val ids =
                checkpointFeed.coveredOperationIds
                    .mapIndexed { index, id -> index.toLong() + 1 to id }
                    .toMap(linkedMapOf())
            restored[key] =
                FeedState(
                    head = ids.size.toLong(),
                    headId = ids[ids.size.toLong()],
                    checkpointIds = ids.toMutableMap(),
                )
        }
        val staged = OperationKernel(signer, verifier, store, checkpointVerifier)
        staged.feeds.putAll(restored)
        staged.feeds.values.forEach { staged.knownIds += it.checkpointIds.values }
        staged.checkpointPreferences.putAll(restoredPreferences)
        staged.rematerialize()
        for (wire in trailing) {
            val authenticated =
                runCatching {
                    verifier.verify(wire).also { staged.validateAuthenticated(wire, it) }
                }.getOrElse { return RestoreResult.REJECTED_CHECKPOINT }
            if (staged.ingestAuthenticated(authenticated) != IngestDisposition.ACCEPTED) {
                return RestoreResult.REJECTED_CHECKPOINT
            }
        }
        feeds.clear()
        feeds.putAll(staged.feeds)
        knownIds.clear()
        knownIds.addAll(staged.knownIds)
        quarantined.clear()
        quarantined.addAll(staged.quarantined)
        checkpointPreferences.clear()
        checkpointPreferences.putAll(staged.checkpointPreferences)
        materializedPreferences.clear()
        materializedPreferences.putAll(staged.materializedPreferences)
        return RestoreResult.RESTORED
    }

    fun materializedPreference(key: String): String? = materializedPreferences[key]

    private fun validateAuthenticated(
        wire: ByteArray,
        authenticated: AuthenticatedOperation,
        expectedOperation: UnsignedOperation? = null,
        expectedPayload: ByteArray? = null,
        expectedCanonical: ByteArray? = null,
        expectedId: ProtocolBytes? = null,
    ) {
        require(authenticated.signedEnvelope.contentEquals(wire))
        val canonical = OperationCodec.encodeUnsigned(authenticated.operation)
        require(canonical.contentEquals(authenticated.canonicalUnsigned))
        require(OperationCodec.operationId(canonical) == authenticated.operationId)
        require(OperationCodec.payloadHash(authenticated.canonicalPayload) == authenticated.operation.payloadHash)
        OperationCodec.decodePreference(authenticated.canonicalPayload)
        expectedOperation?.let { require(it == authenticated.operation) }
        expectedPayload?.let { require(it.contentEquals(authenticated.canonicalPayload)) }
        expectedCanonical?.let { require(it.contentEquals(authenticated.canonicalUnsigned)) }
        expectedId?.let { require(it == authenticated.operationId) }
    }

    private fun attemptAccept(
        feed: FeedState,
        authenticated: AuthenticatedOperation,
    ): IngestDisposition {
        val operation = authenticated.operation
        if (operation.sequence != feed.head + 1) {
            feed.pending[operation.sequence] = authenticated to IngestDisposition.PENDING_GAP
            return IngestDisposition.PENDING_GAP
        }
        if (operation.previousOperationId != feed.headId) return IngestDisposition.REJECTED_INVALID
        if (!causalReady(operation)) {
            feed.pending[operation.sequence] = authenticated to IngestDisposition.PENDING_CAUSAL
            return IngestDisposition.PENDING_CAUSAL
        }
        feed.pending.remove(operation.sequence)
        feed.accepted[operation.sequence] = authenticated
        feed.head = operation.sequence
        feed.headId = authenticated.operationId
        return IngestDisposition.ACCEPTED
    }

    private fun causalReady(operation: UnsignedOperation): Boolean =
        operation.frontier.all { dependency ->
            val feed = feeds[feedKey(dependency.deviceId, dependency.incarnationId)]
            feed != null && feed.head >= dependency.sequence &&
                (feed.accepted[dependency.sequence]?.operationId ?: feed.checkpointIds[dependency.sequence]) == dependency.headOperationId
        }

    private fun drainAll() {
        var progressed: Boolean
        do {
            progressed = false
            feeds.values.forEach { feed ->
                val next = feed.pending[feed.head + 1]?.first ?: return@forEach
                if (next.operation.previousOperationId != feed.headId) {
                    feed.pending.remove(next.operation.sequence)
                    feed.candidates.remove(next.operation.sequence)
                    knownIds.remove(next.operationId)
                    return@forEach
                }
                if (attemptAccept(feed, next) == IngestDisposition.ACCEPTED) progressed = true
            }
        } while (progressed)
    }

    private fun quarantineFork(
        feed: FeedState,
        sequence: Long,
        incoming: AuthenticatedOperation,
        conflictingId: ProtocolBytes?,
    ) {
        feed.forkedAt = minOf(feed.forkedAt ?: sequence, sequence)
        quarantined += incoming.operationId
        conflictingId?.let(quarantined::add)
        feed.accepted.filterKeys { it >= sequence }.values.forEach { quarantined += it.operationId }
        feed.pending.filterKeys { it >= sequence }.values.forEach { quarantined += it.first.operationId }
        val invalidatedCheckpoint = feed.checkpointIds.keys.any { it >= sequence }
        feed.checkpointIds.keys.removeAll { it >= sequence }
        if (invalidatedCheckpoint) checkpointPreferences.clear()
        feed.accepted.keys.removeAll { it >= sequence }
        feed.pending.keys.removeAll { it >= sequence }
        feed.head = minOf(feed.head, sequence - 1)
        feed.headId = feed.accepted[feed.head]?.operationId ?: feed.checkpointIds[feed.head]
        rematerialize()
    }

    private fun rematerialize() {
        materializedPreferences.clear()
        materializedPreferences.putAll(checkpointPreferences)
        feeds.values.flatMap { it.accepted.values }
            .sortedWith { left, right -> compareBytes(left.operationId.copy(), right.operationId.copy()) }
            .forEach { authenticated ->
                val preference = OperationCodec.decodePreference(authenticated.canonicalPayload)
                val value = preference.value as PreferenceValue.Text
                materializedPreferences[preference.key] = value.value
            }
    }

    private fun compareBytes(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun feedKey(
        deviceId: ProtocolBytes,
        incarnationId: ProtocolBytes,
    ): String = "$deviceId:$incarnationId"
}
