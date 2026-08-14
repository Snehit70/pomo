package com.pomo.sync.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pomo.db.AppDatabase
import com.pomo.sync.crypto.CoseKernelSigner
import com.pomo.sync.crypto.CoseKernelVerifier
import com.pomo.sync.crypto.CoseOperationWire
import com.pomo.sync.protocol.AuthenticatedOperation
import com.pomo.sync.protocol.CheckpointVerifier
import com.pomo.sync.protocol.IngestDisposition
import com.pomo.sync.protocol.OperationCodec
import com.pomo.sync.protocol.OperationKernel
import com.pomo.sync.protocol.OperationReclassification
import com.pomo.sync.protocol.PreferenceSet
import com.pomo.sync.protocol.PreferenceValue
import com.pomo.sync.protocol.ProtocolBytes
import com.pomo.sync.protocol.UnsignedOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

private val localCommitBoundaries =
    SyncCommitBoundary.entries.filter { it != SyncCommitBoundary.AFTER_QUARANTINE }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class RoomOperationStoreTest {
    private val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    @Test
    public fun localAuthorCommitsWireHeadProjectionAndOutboxAtomically() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val operation = authenticated(sequence = 1, previous = null, value = "bell")

            store.commit(operation, IngestDisposition.ACCEPTED, localAuthor = true)

            val restarted = RoomOperationStore(database).restartSnapshot()
            assertEquals(1, restarted.operations.size)
            assertTrue(restarted.operations.single().signedWire.contentEquals(operation.signedEnvelope))
            assertEquals(1L, restarted.heads.single().sequence)
            assertEquals("bell", restarted.projection.single().preferenceValue)
            assertEquals(operation.operationId.toString(), restarted.pendingOutbox.single().operationId)
            assertTrue(restarted.pendingOutbox.single().signedWire.contentEquals(operation.signedEnvelope))
            assertEquals(1, restarted.dispositionCounts[IngestDisposition.ACCEPTED.name])
        }
    }

    @Test
    public fun duplicateDeliveryAddsNoSecondEffectOrOutboxObligation() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val operation = authenticated(sequence = 1, previous = null, value = "bell")
            store.commit(operation, IngestDisposition.ACCEPTED, localAuthor = true)

            store.commit(operation, IngestDisposition.DUPLICATE, localAuthor = false)

            val restarted = store.restartSnapshot()
            assertEquals(1, restarted.operations.size)
            assertEquals(1, restarted.projection.size)
            assertEquals(1, restarted.pendingOutbox.size)
            assertEquals(1, restarted.dispositionCounts[IngestDisposition.DUPLICATE.name])
        }
    }

    @Test
    public fun pendingOperationSurvivesRestartWithoutInventingHeadOrProjection() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val pending = authenticated(sequence = 2, previous = id(9), value = "bell")

            store.commit(pending, IngestDisposition.PENDING_GAP, localAuthor = false)

            val restarted = RoomOperationStore(database).restartSnapshot()
            assertEquals(IngestDisposition.PENDING_GAP.name, restarted.operations.single().disposition)
            assertTrue(restarted.heads.isEmpty())
            assertTrue(restarted.projection.isEmpty())
            assertTrue(restarted.pendingOutbox.isEmpty())
        }
    }

    @Test
    public fun pendingCandidateTransitionsAtomicallyWithoutTreatingRedeliveryAsPromotion() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val pending = authenticated(sequence = 1, previous = null, value = "bell")
            store.commit(pending, IngestDisposition.PENDING_CAUSAL, localAuthor = false)
            store.commit(pending, IngestDisposition.PENDING_CAUSAL, localAuthor = false)

            store.transitionPending(pending, IngestDisposition.ACCEPTED)

            val restarted = store.restartSnapshot()
            assertEquals(IngestDisposition.ACCEPTED.name, restarted.operations.single().disposition)
            assertEquals(1L, restarted.heads.single().sequence)
            assertEquals("bell", restarted.projection.single().preferenceValue)
            assertEquals(1, restarted.dispositionCounts[IngestDisposition.PENDING_CAUSAL.name])
            assertEquals(1, restarted.dispositionCounts[IngestDisposition.DUPLICATE.name])
            assertEquals(1, restarted.dispositionCounts[IngestDisposition.ACCEPTED.name])
        }
    }

    @Test
    public fun rejectedWiresAreAuditedWithoutBecomingOperations() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val rejected = authenticated(sequence = 1, previous = null, value = "bell")

            store.commit(rejected, IngestDisposition.REJECTED_INVALID, localAuthor = false)
            store.recordRejected(byteArrayOf(0x80.toByte()))

            val restarted = store.restartSnapshot()
            assertTrue(restarted.operations.isEmpty())
            assertEquals(2, restarted.dispositionCounts[IngestDisposition.REJECTED_INVALID.name])
        }
    }

    @Test
    public fun kernelVerifierFailurePersistsRejectedCountAcrossRestart() {
        database().use { database ->
            val kernel = kernel(RoomOperationStore(database))

            assertEquals(IngestDisposition.REJECTED_INVALID, kernel.ingest(byteArrayOf()))

            val restarted = RoomOperationStore(database).restartSnapshot()
            assertTrue(restarted.operations.isEmpty())
            assertEquals(1, restarted.dispositionCounts[IngestDisposition.REJECTED_INVALID.name])
            assertEquals(1, kernel.summarize().dispositionCounts[IngestDisposition.REJECTED_INVALID])
            assertEquals(1, kernel.summarize().rejected)
        }
    }

    @Test
    public fun forkRetainsRowsQuarantinesTailAndRewindsProjection() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val first = authenticated(sequence = 1, previous = null, value = "bell")
            val second = authenticated(sequence = 2, previous = first.operationId, value = "chime")
            val fork = authenticated(sequence = 2, previous = first.operationId, value = "gong")
            store.commit(first, IngestDisposition.ACCEPTED, localAuthor = false)
            store.commit(second, IngestDisposition.ACCEPTED, localAuthor = false)

            store.commit(fork, IngestDisposition.QUARANTINED_FORK, localAuthor = false)

            val restarted = store.restartSnapshot()
            assertEquals(3, restarted.operations.size)
            assertEquals(2, restarted.operations.count { it.disposition == IngestDisposition.QUARANTINED_FORK.name })
            assertEquals(1L, restarted.heads.single().sequence)
            assertEquals(2L, restarted.heads.single().forkedAt)
            assertEquals("bell", restarted.projection.single().preferenceValue)
        }
    }

    @Test
    public fun forkAmongPendingCandidatesKeepsCurrentDurablePrefix() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val first = authenticated(sequence = 1, previous = null, value = "bell")
            val pending = authenticated(sequence = 3, previous = id(8), value = "chime")
            val fork = authenticated(sequence = 3, previous = id(8), value = "gong")
            store.commit(first, IngestDisposition.ACCEPTED, localAuthor = false)
            store.commit(pending, IngestDisposition.PENDING_GAP, localAuthor = false)

            store.commit(fork, IngestDisposition.QUARANTINED_FORK, localAuthor = false)

            val restarted = store.restartSnapshot()
            assertEquals(1L, restarted.heads.single().sequence)
            assertEquals(first.operationId.toString(), restarted.heads.single().operationId)
            assertEquals(3L, restarted.heads.single().forkedAt)
            assertEquals("bell", restarted.projection.single().preferenceValue)
        }
    }

    @Test
    public fun laterQuarantinedOperationCannotLoosenExistingForkMarker() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val first = authenticated(sequence = 1, previous = null, value = "bell")
            val second = authenticated(sequence = 2, previous = first.operationId, value = "chime")
            val fork = authenticated(sequence = 2, previous = first.operationId, value = "gong")
            val later = authenticated(sequence = 4, previous = fork.operationId, value = "whistle")
            store.commit(first, IngestDisposition.ACCEPTED, localAuthor = false)
            store.commit(second, IngestDisposition.ACCEPTED, localAuthor = false)
            store.commit(fork, IngestDisposition.QUARANTINED_FORK, localAuthor = false)

            store.commit(later, IngestDisposition.QUARANTINED_FORK, localAuthor = false)

            val restarted = store.restartSnapshot()
            assertEquals(1L, restarted.heads.single().sequence)
            assertEquals(first.operationId.toString(), restarted.heads.single().operationId)
            assertEquals(2L, restarted.heads.single().forkedAt)
            assertEquals("bell", restarted.projection.single().preferenceValue)
        }
    }

    @Test
    public fun localPendingPromotionCreatesOutboxInSameAtomicBatch() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val localPending = authenticated(sequence = 1, previous = null, value = "bell")
            val dependency =
                authenticated(
                    sequence = 1,
                    previous = null,
                    value = "ready",
                    device = 4,
                    incarnation = 5,
                )
            store.commit(localPending, IngestDisposition.PENDING_CAUSAL, localAuthor = true)
            val pendingRestart = RoomOperationStore(database).restartSnapshot()
            assertTrue(pendingRestart.operations.single().localAuthor)
            assertTrue(pendingRestart.pendingOutbox.isEmpty())

            store.commit(
                dependency,
                IngestDisposition.ACCEPTED,
                localAuthor = false,
                reclassifications =
                    listOf(OperationReclassification(localPending, IngestDisposition.ACCEPTED)),
            )

            val restarted = RoomOperationStore(database).restartSnapshot()
            assertEquals(2, restarted.operations.size)
            assertTrue(restarted.operations.all { it.disposition == IngestDisposition.ACCEPTED.name })
            assertEquals(localPending.operationId.toString(), restarted.pendingOutbox.single().operationId)
            assertTrue(restarted.pendingOutbox.single().signedWire.contentEquals(localPending.signedEnvelope))
        }
    }

    @Test
    public fun kernelAtomicallyPersistsEveryPendingOperationDrainedByOneIngest() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val kernel = kernel(store)
            val first = authenticated(sequence = 1, previous = null, value = "bell")
            val second = authenticated(sequence = 2, previous = first.operationId, value = "chime")
            assertEquals(IngestDisposition.PENDING_GAP, kernel.ingest(second.signedEnvelope))

            assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(first.signedEnvelope))

            val restarted = RoomOperationStore(database).restartSnapshot()
            assertEquals(2, restarted.operations.size)
            assertTrue(restarted.operations.all { it.disposition == IngestDisposition.ACCEPTED.name })
            assertEquals(2L, restarted.heads.single().sequence)
            assertEquals(kernel.materializedPreference("timer.sound"), restarted.projection.single().preferenceValue)
            assertEquals(2, restarted.dispositionCounts[IngestDisposition.ACCEPTED.name])
            assertEquals(1, restarted.dispositionCounts[IngestDisposition.PENDING_GAP.name])
        }
    }

    @Test
    public fun crashDuringDrainedPendingReclassificationRollsBackWholeBatch() {
        database().use { database ->
            var failBatch = false
            var operationBoundaries = 0
            val store =
                RoomOperationStore(
                    database,
                    SyncFaultInjector { boundary ->
                        if (failBatch && boundary == SyncCommitBoundary.AFTER_OPERATION) {
                            operationBoundaries += 1
                            if (operationBoundaries == 2) throw InjectedFailure(boundary)
                        }
                    },
                )
            val kernel = kernel(store)
            val first = authenticated(sequence = 1, previous = null, value = "bell")
            val second = authenticated(sequence = 2, previous = first.operationId, value = "chime")
            assertEquals(IngestDisposition.PENDING_GAP, kernel.ingest(second.signedEnvelope))
            failBatch = true

            assertEquals(IngestDisposition.REJECTED_INVALID, kernel.ingest(first.signedEnvelope))

            val restarted = RoomOperationStore(database).restartSnapshot()
            assertEquals(1, restarted.operations.size)
            assertEquals(second.operationId.toString(), restarted.operations.single().operationId)
            assertEquals(IngestDisposition.PENDING_GAP.name, restarted.operations.single().disposition)
            assertTrue(restarted.heads.isEmpty())
            assertTrue(restarted.projection.isEmpty())
        }
    }

    @Test
    public fun restartRetainsOutboxUntilExplicitDeliveryAcknowledgement() {
        database().use { database ->
            val store = RoomOperationStore(database)
            val operation = authenticated(sequence = 1, previous = null, value = "bell")
            store.commit(operation, IngestDisposition.ACCEPTED, localAuthor = true)

            assertEquals(1, RoomOperationStore(database).restartSnapshot().pendingOutbox.size)
            RoomOperationStore(database).markDelivered(operation.operationId.toString())

            val restarted = RoomOperationStore(database).restartSnapshot()
            assertTrue(restarted.pendingOutbox.isEmpty())
            assertEquals(1, restarted.operations.size)
            assertEquals("bell", restarted.projection.single().preferenceValue)
        }
    }

    @Test
    public fun everyLocalCommitBoundaryRollsBackAllRows() {
        localCommitBoundaries.forEach { boundary ->
            database().use { database ->
                val store =
                    RoomOperationStore(
                        database,
                        SyncFaultInjector { reached ->
                            if (reached == boundary) throw InjectedFailure(boundary)
                        },
                    )
                val operation = authenticated(sequence = 1, previous = null, value = "bell")

                assertTrue(
                    runCatching {
                        store.commit(operation, IngestDisposition.ACCEPTED, localAuthor = true)
                    }.exceptionOrNull() is InjectedFailure,
                )

                val dao = database.syncDao()
                assertEquals(0, dao.operationCount())
                assertEquals(0, dao.headCount())
                assertEquals(0, dao.projectionCount())
                assertEquals(0, dao.outboxCount())
                assertEquals(0, dao.dispositionEventCount())
            }
        }
    }

    @Test
    public fun forkCrashAtQuarantineBoundaryRollsBackEveryForkEffect() {
        database().use { database ->
            val first = authenticated(sequence = 1, previous = null, value = "bell")
            val second = authenticated(sequence = 2, previous = first.operationId, value = "chime")
            val fork = authenticated(sequence = 2, previous = first.operationId, value = "gong")
            val store = RoomOperationStore(database)
            store.commit(first, IngestDisposition.ACCEPTED, localAuthor = false)
            store.commit(second, IngestDisposition.ACCEPTED, localAuthor = false)
            val before = store.restartSnapshot()
            val crashing =
                RoomOperationStore(
                    database,
                    SyncFaultInjector { boundary ->
                        if (boundary == SyncCommitBoundary.AFTER_QUARANTINE) {
                            throw InjectedFailure(boundary)
                        }
                    },
                )

            assertTrue(
                runCatching {
                    crashing.commit(fork, IngestDisposition.QUARANTINED_FORK, localAuthor = false)
                }.exceptionOrNull() is InjectedFailure,
            )
            assertRestartSnapshotsEqual(before, store.restartSnapshot())
        }
    }

    private fun database(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun kernel(store: RoomOperationStore): OperationKernel =
        OperationKernel(
            CoseKernelSigner(pair.private),
            CoseKernelVerifier { pair.public },
            store,
            CheckpointVerifier { },
        )

    private fun authenticated(
        sequence: Long,
        previous: ProtocolBytes?,
        value: String,
        device: Byte = 2,
        incarnation: Byte = 3,
    ): AuthenticatedOperation {
        val payload = OperationCodec.encodePreference(PreferenceSet("timer.sound", PreferenceValue.Text(value)))
        val operation =
            UnsignedOperation(
                memberId = id(1),
                deviceId = id(device),
                incarnationId = ProtocolBytes.of(ByteArray(16) { incarnation }, 16),
                sequence = sequence,
                previousOperationId = previous,
                frontier = emptyList(),
                authorizationEpoch = 1,
                payloadHash = OperationCodec.payloadHash(payload),
            )
        return CoseOperationWire.sign(operation, payload, pair.private)
    }

    private fun id(value: Byte): ProtocolBytes = ProtocolBytes.of(ByteArray(32) { value }, 32)

    private fun assertRestartSnapshotsEqual(
        expected: SyncRestartSnapshot,
        actual: SyncRestartSnapshot,
    ) {
        assertEquals(
            expected.operations.map { it.operationId to it.disposition },
            actual.operations.map { it.operationId to it.disposition },
        )
        assertEquals(expected.heads, actual.heads)
        assertEquals(expected.projection, actual.projection)
        assertEquals(expected.pendingOutbox.map { it.operationId }, actual.pendingOutbox.map { it.operationId })
        assertEquals(expected.dispositionCounts, actual.dispositionCounts)
    }

    private class InjectedFailure(boundary: SyncCommitBoundary) : RuntimeException(boundary.name)
}
