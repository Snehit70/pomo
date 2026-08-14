package com.pomo.sync.protocol

import com.pomo.sync.crypto.CoseKernelSigner
import com.pomo.sync.crypto.CoseKernelVerifier
import com.pomo.sync.crypto.CoseOperationWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

public class OperationKernelTest {
    private val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    @Test
    public fun preferenceTracerBulletAuthorsStoresIngestsAndMaterializesRawWire() {
        val stored = mutableListOf<AuthenticatedOperation>()
        val kernel = kernel(OperationStore { stored += it })
        val authored = kernel.author(authorRequest("bell")) as AuthorResult.Authored

        assertEquals(IngestDisposition.ACCEPTED, authored.disposition)
        assertEquals(1, stored.size)
        assertEquals(IngestDisposition.DUPLICATE, kernel.ingest(authored.value.signedEnvelope))
        assertEquals("bell", kernel.materializedPreference("timer.sound"))
        assertEquals(1, kernel.summarize().accepted)
    }

    @Test
    public fun durableStoreFailureCannotMutateKernelState() {
        val kernel = kernel(OperationStore { throw IllegalStateException("disk unavailable") })
        val wire = authenticated(operation(1, null, payload("bell")), payload("bell")).signedEnvelope

        assertEquals(IngestDisposition.REJECTED_INVALID, kernel.ingest(wire))
        assertTrue(kernel.summarize().heads.isEmpty())
    }

    @Test
    public fun quarantinedForkRewindsAndRematerializes() {
        val bell = payload("bell")
        val kernel = kernel()
        val first = authenticated(operation(1, null, bell), bell)
        assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(first.signedEnvelope))
        val secondA = authenticated(operation(2, first.operationId, bell), bell)
        val chime = payload("chime")
        val secondB = authenticated(operation(2, first.operationId, chime), chime)
        assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(secondA.signedEnvelope))
        assertEquals(IngestDisposition.QUARANTINED_FORK, kernel.ingest(secondB.signedEnvelope))
        assertTrue(kernel.summarize().forks.isNotEmpty())
        assertEquals(1, kernel.summarize().accepted)
        assertEquals("bell", kernel.materializedPreference("timer.sound"))
    }

    @Test
    public fun blocksAuthoringBeforeDurablePrerequisites() {
        val result = kernel().author(authorRequest("bell").copy(authorized = false, completePrerequisites = emptySet()))
        assertTrue(result is AuthorResult.Blocked)
    }

    @Test
    public fun invalidPredecessorCannotPoisonCandidateOrKnownIdState() {
        val bell = payload("bell")
        val kernel = kernel()
        val first = authenticated(operation(1, null, bell), bell)
        assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(first.signedEnvelope))
        val invalid = authenticated(operation(2, id(9), bell), bell)
        assertEquals(IngestDisposition.REJECTED_INVALID, kernel.ingest(invalid.signedEnvelope))
        val valid = authenticated(operation(2, first.operationId, bell), bell)
        assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(valid.signedEnvelope))
        assertEquals(2, kernel.summarize().accepted)
    }

    @Test
    public fun delayedInvalidPredecessorIsRemovedWhenGapCloses() {
        val bell = payload("bell")
        val kernel = kernel()
        val first = authenticated(operation(1, null, bell), bell)
        assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(first.signedEnvelope))
        val future = authenticated(operation(3, id(9), bell), bell)
        assertEquals(IngestDisposition.PENDING_GAP, kernel.ingest(future.signedEnvelope))
        assertTrue(kernel.summarize().gaps.single().endsWith("@2"))
        val second = authenticated(operation(2, first.operationId, bell), bell)
        assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(second.signedEnvelope))
        assertEquals(2, kernel.summarize().accepted)
        assertEquals(0, kernel.summarize().pending)
        assertEquals(IngestDisposition.REJECTED_INVALID, kernel.ingest(future.signedEnvelope))
    }

    @Test
    public fun malformedAuthenticatedWireIsRejectedBeforeFeedStateChanges() {
        val kernel = kernel()
        assertEquals(IngestDisposition.REJECTED_INVALID, kernel.ingest(byteArrayOf(0x80.toByte())))
        assertTrue(kernel.summarize().heads.isEmpty())
    }

    @Test
    public fun rejectedRestoreLeavesActiveStateUntouched() {
        val bell = payload("bell")
        val kernel = kernel()
        val first = authenticated(operation(1, null, bell), bell)
        assertEquals(IngestDisposition.ACCEPTED, kernel.ingest(first.signedEnvelope))
        val invalidTrailing = authenticated(operation(2, id(9), bell), bell)
        val checkpoint = KernelCheckpoint(PomoSuite.ID, PomoSuite.INITIAL_GENERATION, emptyList(), emptyList())

        assertEquals(RestoreResult.REJECTED_CHECKPOINT, kernel.restore(checkpoint, listOf(invalidTrailing.signedEnvelope)))
        assertEquals("bell", kernel.materializedPreference("timer.sound"))
        assertEquals(1, kernel.summarize().accepted)
    }

    @Test
    public fun noTrailingRestorePreservesCheckpointMaterializedPreferences() {
        val coveredId = id(7)
        val checkpoint =
            KernelCheckpoint(
                PomoSuite.ID,
                PomoSuite.INITIAL_GENERATION,
                listOf(CheckpointFeed(id(2), incarnation(), listOf(coveredId))),
                listOf(CheckpointPreference("timer.sound", "bell")),
            )
        val kernel = kernel()

        assertEquals(RestoreResult.RESTORED, kernel.restore(checkpoint, emptyList()))
        assertEquals("bell", kernel.materializedPreference("timer.sound"))
        assertEquals(1, kernel.summarize().accepted)
    }

    @Test
    public fun trailingAcceptedOperationAppliesOverCheckpointProjection() {
        val coveredId = id(7)
        val checkpoint =
            KernelCheckpoint(
                PomoSuite.ID,
                PomoSuite.INITIAL_GENERATION,
                listOf(CheckpointFeed(id(2), incarnation(), listOf(coveredId))),
                listOf(CheckpointPreference("timer.sound", "bell")),
            )
        val chime = payload("chime")
        val trailing = authenticated(operation(2, coveredId, chime), chime)
        val kernel = kernel()

        assertEquals(RestoreResult.RESTORED, kernel.restore(checkpoint, listOf(trailing.signedEnvelope)))
        assertEquals("chime", kernel.materializedPreference("timer.sound"))
        assertEquals(2, kernel.summarize().accepted)
    }

    @Test
    public fun checkpointForkInvalidationClearsCheckpointProjection() {
        val checkpoint =
            KernelCheckpoint(
                PomoSuite.ID,
                PomoSuite.INITIAL_GENERATION,
                listOf(CheckpointFeed(id(2), incarnation(), listOf(id(7)))),
                listOf(CheckpointPreference("timer.sound", "bell")),
            )
        val kernel = kernel()
        assertEquals(RestoreResult.RESTORED, kernel.restore(checkpoint, emptyList()))
        val alternate = authenticated(operation(1, null, payload("chime")), payload("chime"))

        assertEquals(IngestDisposition.QUARANTINED_FORK, kernel.ingest(alternate.signedEnvelope))
        assertEquals(null, kernel.materializedPreference("timer.sound"))
    }

    @Test
    public fun restoreRejectsUnsortedOrDuplicateCheckpointPreferenceKeys() {
        val unsorted =
            KernelCheckpoint(
                PomoSuite.ID,
                PomoSuite.INITIAL_GENERATION,
                emptyList(),
                listOf(
                    CheckpointPreference("timer.sound", "bell"),
                    CheckpointPreference("focusDurationMinutes", "25"),
                ),
            )
        val duplicate =
            unsorted.copy(
                materializedPreferences =
                    listOf(
                        CheckpointPreference("timer.sound", "bell"),
                        CheckpointPreference("timer.sound", "chime"),
                    ),
            )

        assertEquals(RestoreResult.REJECTED_CHECKPOINT, kernel().restore(unsorted, emptyList()))
        assertEquals(RestoreResult.REJECTED_CHECKPOINT, kernel().restore(duplicate, emptyList()))
    }

    private fun kernel(store: OperationStore = OperationStore { }): OperationKernel =
        OperationKernel(
            CoseKernelSigner(pair.private),
            CoseKernelVerifier { pair.public },
            store,
            CheckpointVerifier { },
        )

    private fun authorRequest(value: String): AuthorRequest =
        AuthorRequest(
            memberId = id(1),
            deviceId = id(2),
            incarnationId = incarnation(),
            authorizationEpoch = 1,
            frontier = emptyList(),
            preference = PreferenceSet("timer.sound", PreferenceValue.Text(value)),
            authorized = true,
            deviceReady = true,
            completePrerequisites = setOf("PROFILE_FRONTIER"),
        )

    private fun authenticated(
        operation: UnsignedOperation,
        payload: ByteArray,
    ): AuthenticatedOperation = CoseOperationWire.sign(operation, payload, pair.private)

    private fun payload(value: String): ByteArray =
        OperationCodec.encodePreference(PreferenceSet("timer.sound", PreferenceValue.Text(value)))

    private fun operation(
        sequence: Long,
        previous: ProtocolBytes?,
        payload: ByteArray,
    ): UnsignedOperation =
        UnsignedOperation(
            memberId = id(1),
            deviceId = id(2),
            incarnationId = incarnation(),
            sequence = sequence,
            previousOperationId = previous,
            frontier = emptyList(),
            authorizationEpoch = 1,
            payloadHash = OperationCodec.payloadHash(payload),
        )

    private fun incarnation(): ProtocolBytes = ProtocolBytes.of(ByteArray(16) { 3 }, 16)

    private fun id(value: Byte): ProtocolBytes = ProtocolBytes.of(ByteArray(32) { value }, 32)
}
