package com.pomo.sync.persistence

import com.pomo.db.AppDatabase
import com.pomo.sync.protocol.AuthenticatedOperation
import com.pomo.sync.protocol.IngestDisposition
import com.pomo.sync.protocol.OperationCodec
import com.pomo.sync.protocol.OperationReclassification
import com.pomo.sync.protocol.OperationStore
import com.pomo.sync.protocol.PreferenceValue

internal enum class SyncCommitBoundary {
    BEFORE_OPERATION,
    AFTER_OPERATION,
    AFTER_QUARANTINE,
    AFTER_HEAD,
    AFTER_PROJECTION_CLEAR,
    AFTER_PROJECTION,
    AFTER_OUTBOX,
    AFTER_DISPOSITION,
    BEFORE_COMMIT,
}

internal fun interface SyncFaultInjector {
    fun at(boundary: SyncCommitBoundary)
}

internal data class SyncRestartSnapshot(
    val operations: List<SyncOperationEntity>,
    val heads: List<SyncFeedHeadEntity>,
    val projection: List<SyncPreferenceProjectionEntity>,
    val pendingOutbox: List<SyncOutboxEntity>,
    val dispositionCounts: Map<String, Int>,
)

/** Dormant Room implementation of the #102 kernel's durable commit seam. */
internal class RoomOperationStore(
    private val database: AppDatabase,
    private val faultInjector: SyncFaultInjector = SyncFaultInjector { },
) : OperationStore {
    private val dao: SyncDao = database.syncDao()

    override fun commit(
        operation: AuthenticatedOperation,
        disposition: IngestDisposition,
        localAuthor: Boolean,
        reclassifications: List<OperationReclassification>,
    ) {
        database.runInTransaction {
            commitInTransaction(operation, disposition, localAuthor, transitionPending = false)
            reclassifications.forEach { transition ->
                commitInTransaction(
                    transition.operation,
                    transition.disposition,
                    localAuthor = false,
                    transitionPending = true,
                )
            }
        }
    }

    fun commit(
        operation: AuthenticatedOperation,
        disposition: IngestDisposition,
        localAuthor: Boolean,
    ) {
        commit(operation, disposition, localAuthor, emptyList())
    }

    fun transitionPending(
        operation: AuthenticatedOperation,
        disposition: IngestDisposition,
    ) {
        database.runInTransaction {
            commitInTransaction(operation, disposition, localAuthor = false, transitionPending = true)
        }
    }

    override fun reject(
        signedEnvelope: ByteArray,
        disposition: IngestDisposition,
    ) {
        require(
            disposition == IngestDisposition.REJECTED_INVALID ||
                disposition == IngestDisposition.REJECTED_UNSUPPORTED_SUITE,
        ) { "Raw wire disposition must be Rejected" }
        database.runInTransaction {
            dao.insertDisposition(
                SyncDispositionEventEntity(
                    operationId = null,
                    disposition = disposition.name,
                    signedWire = signedEnvelope.copyOf(),
                ),
            )
            faultInjector.at(SyncCommitBoundary.AFTER_DISPOSITION)
            faultInjector.at(SyncCommitBoundary.BEFORE_COMMIT)
        }
    }

    fun recordRejected(rawWire: ByteArray) {
        reject(rawWire, IngestDisposition.REJECTED_INVALID)
    }

    fun markDelivered(operationId: String) {
        require(operationId.matches(Regex("[0-9a-f]{64}"))) { "Operation ID must be lowercase hex" }
        database.runInTransaction {
            check(dao.deleteOutbox(operationId) == 1) { "Unknown delivery obligation" }
        }
    }

    fun restartSnapshot(): SyncRestartSnapshot {
        val counts = dao.dispositionCounts().associate { it.disposition to it.count }
        return SyncRestartSnapshot(
            operations = dao.allOperations(),
            heads = dao.allHeads(),
            projection = dao.projection(),
            pendingOutbox = dao.pendingOutbox(),
            dispositionCounts = IngestDisposition.entries.associate { it.name to (counts[it.name] ?: 0) },
        )
    }

    private fun commitInTransaction(
        authenticated: AuthenticatedOperation,
        disposition: IngestDisposition,
        localAuthor: Boolean,
        transitionPending: Boolean,
    ) {
        val operationId = authenticated.operationId.toString()
        val existing = dao.operation(operationId)
        if (existing != null) {
            validateSameOperation(existing, authenticated)
            if (!transitionPending) {
                dao.insertDisposition(event(authenticated, IngestDisposition.DUPLICATE))
                faultInjector.at(SyncCommitBoundary.AFTER_DISPOSITION)
                faultInjector.at(SyncCommitBoundary.BEFORE_COMMIT)
                return
            }
            require(
                existing.disposition == IngestDisposition.PENDING_GAP.name ||
                    existing.disposition == IngestDisposition.PENDING_CAUSAL.name,
            ) { "Only a persisted Pending Operation can transition" }
            require(
                disposition != IngestDisposition.PENDING_GAP &&
                    disposition != IngestDisposition.PENDING_CAUSAL &&
                    disposition != IngestDisposition.DUPLICATE,
            ) { "Pending transition must resolve to a terminal disposition" }
        } else {
            require(!transitionPending) { "Cannot transition an unknown Pending Operation" }
        }
        val effectiveLocalAuthor = existing?.localAuthor ?: localAuthor
        require(
            !effectiveLocalAuthor ||
                disposition == IngestDisposition.ACCEPTED ||
                disposition == IngestDisposition.PENDING_GAP ||
                disposition == IngestDisposition.PENDING_CAUSAL,
        ) {
            "Local Operation must be Accepted or Pending"
        }

        val entity = entity(authenticated, disposition, effectiveLocalAuthor)
        faultInjector.at(SyncCommitBoundary.BEFORE_OPERATION)
        persistOperation(existing, entity, disposition)

        when (disposition) {
            IngestDisposition.ACCEPTED -> accept(entity, effectiveLocalAuthor)
            IngestDisposition.QUARANTINED_FORK -> quarantineFork(entity)
            IngestDisposition.DUPLICATE -> error("New Operation cannot have Duplicate disposition")
            IngestDisposition.PENDING_GAP,
            IngestDisposition.PENDING_CAUSAL,
            IngestDisposition.REJECTED_INVALID,
            IngestDisposition.REJECTED_UNSUPPORTED_SUITE,
            -> Unit
        }

        dao.insertDisposition(event(authenticated, disposition))
        faultInjector.at(SyncCommitBoundary.AFTER_DISPOSITION)
        faultInjector.at(SyncCommitBoundary.BEFORE_COMMIT)
    }

    private fun persistOperation(
        existing: SyncOperationEntity?,
        entity: SyncOperationEntity,
        disposition: IngestDisposition,
    ) {
        if (existing == null) {
            if (
                disposition != IngestDisposition.REJECTED_INVALID &&
                disposition != IngestDisposition.REJECTED_UNSUPPORTED_SUITE
            ) {
                check(dao.insertOperation(entity) != -1L) { "Operation ID raced during commit" }
                faultInjector.at(SyncCommitBoundary.AFTER_OPERATION)
            }
        } else if (
            disposition == IngestDisposition.REJECTED_INVALID ||
            disposition == IngestDisposition.REJECTED_UNSUPPORTED_SUITE
        ) {
            check(dao.deleteOperation(entity.operationId) == 1)
            faultInjector.at(SyncCommitBoundary.AFTER_OPERATION)
        } else {
            check(dao.updateDisposition(entity.operationId, disposition.name) == 1)
            faultInjector.at(SyncCommitBoundary.AFTER_OPERATION)
        }
    }

    private fun accept(
        entity: SyncOperationEntity,
        localAuthor: Boolean,
    ) {
        dao.upsertHead(
            SyncFeedHeadEntity(
                entity.deviceId,
                entity.incarnationId,
                entity.sequence,
                entity.operationId,
                forkedAt = null,
            ),
        )
        faultInjector.at(SyncCommitBoundary.AFTER_HEAD)
        rebuildProjection()
        faultInjector.at(SyncCommitBoundary.AFTER_PROJECTION)
        if (localAuthor) {
            check(dao.insertOutbox(SyncOutboxEntity(entity.operationId, entity.signedWire.copyOf())) != -1L)
        }
        faultInjector.at(SyncCommitBoundary.AFTER_OUTBOX)
    }

    private fun quarantineFork(incoming: SyncOperationEntity) {
        val durableHead = dao.head(incoming.deviceId, incoming.incarnationId)
        val effectiveForkAt = minOf(durableHead?.forkedAt ?: incoming.sequence, incoming.sequence)
        dao.quarantineTail(incoming.deviceId, incoming.incarnationId, effectiveForkAt)
        faultInjector.at(SyncCommitBoundary.AFTER_QUARANTINE)
        val prefixSequence = minOf(durableHead?.sequence ?: 0, effectiveForkAt - 1)
        val prefix =
            if (prefixSequence > 0) {
                requireNotNull(dao.acceptedAt(incoming.deviceId, incoming.incarnationId, prefixSequence)) {
                    "Fork prefix must reference an accepted Operation"
                }
            } else {
                null
            }
        dao.upsertHead(
            SyncFeedHeadEntity(
                incoming.deviceId,
                incoming.incarnationId,
                prefixSequence,
                prefix?.operationId,
                forkedAt = effectiveForkAt,
            ),
        )
        faultInjector.at(SyncCommitBoundary.AFTER_HEAD)
        rebuildProjection()
        faultInjector.at(SyncCommitBoundary.AFTER_PROJECTION)
        faultInjector.at(SyncCommitBoundary.AFTER_OUTBOX)
    }

    private fun rebuildProjection() {
        dao.clearProjection()
        faultInjector.at(SyncCommitBoundary.AFTER_PROJECTION_CLEAR)
        dao.acceptedOperations().forEach { operation ->
            dao.upsertProjection(
                SyncPreferenceProjectionEntity(
                    operation.preferenceKey,
                    operation.preferenceValue,
                    operation.operationId,
                ),
            )
        }
    }

    private fun validateSameOperation(
        existing: SyncOperationEntity,
        authenticated: AuthenticatedOperation,
    ) {
        require(existing.signedWire.contentEquals(authenticated.signedEnvelope)) {
            "Operation ID collision has different authenticated bytes"
        }
        require(existing.deviceId == authenticated.operation.deviceId.toString())
        require(existing.incarnationId == authenticated.operation.incarnationId.toString())
        require(existing.sequence == authenticated.operation.sequence)
    }

    private fun entity(
        authenticated: AuthenticatedOperation,
        disposition: IngestDisposition,
        localAuthor: Boolean,
    ): SyncOperationEntity {
        val preference = OperationCodec.decodePreference(authenticated.canonicalPayload)
        val value = (preference.value as PreferenceValue.Text).value
        return SyncOperationEntity(
            operationId = authenticated.operationId.toString(),
            memberId = authenticated.operation.memberId.toString(),
            deviceId = authenticated.operation.deviceId.toString(),
            incarnationId = authenticated.operation.incarnationId.toString(),
            sequence = authenticated.operation.sequence,
            previousOperationId = authenticated.operation.previousOperationId?.toString(),
            signedWire = authenticated.signedEnvelope.copyOf(),
            preferenceKey = preference.key,
            preferenceValue = value,
            disposition = disposition.name,
            localAuthor = localAuthor,
        )
    }

    private fun event(
        operation: AuthenticatedOperation,
        disposition: IngestDisposition,
    ): SyncDispositionEventEntity =
        SyncDispositionEventEntity(
            operationId = operation.operationId.toString(),
            disposition = disposition.name,
            signedWire = operation.signedEnvelope.copyOf(),
        )
}
