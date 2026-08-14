package com.pomo.sync.protocol

import java.nio.charset.StandardCharsets
import java.text.Normalizer

internal class ProtocolBytes private constructor(bytes: ByteArray) {
    private val storage: ByteArray = bytes.copyOf()

    val size: Int get() = storage.size

    fun copy(): ByteArray = storage.copyOf()

    override fun equals(other: Any?): Boolean = other is ProtocolBytes && storage.contentEquals(other.storage)

    override fun hashCode(): Int = storage.contentHashCode()

    override fun toString(): String = storage.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun of(
            bytes: ByteArray,
            expectedSize: Int,
        ): ProtocolBytes {
            require(bytes.size == expectedSize) { "Expected $expectedSize bytes" }
            return ProtocolBytes(bytes)
        }
    }
}

internal data class FeedFrontier(
    val deviceId: ProtocolBytes,
    val incarnationId: ProtocolBytes,
    val sequence: Long,
    val headOperationId: ProtocolBytes,
) {
    init {
        require(deviceId.size == PomoSuite.ID_BYTES)
        require(incarnationId.size == PomoSuite.INCARNATION_BYTES)
        require(sequence > 0)
        require(headOperationId.size == PomoSuite.ID_BYTES)
    }
}

internal sealed interface PreferenceValue {
    data class Text(val value: String) : PreferenceValue

    data class Integer(val value: Long) : PreferenceValue

    data class Boolean(val value: kotlin.Boolean) : PreferenceValue
}

internal data class PreferenceSet(
    val key: String,
    val value: PreferenceValue,
) {
    init {
        require(Normalizer.isNormalized(key, Normalizer.Form.NFC)) { "Preference key must be NFC" }
        require(key.toByteArray(StandardCharsets.UTF_8).size in 1..128) { "Invalid preference key" }
    }
}

internal data class UnsignedOperation(
    val suite: Int = PomoSuite.ID,
    val suiteGeneration: Long = PomoSuite.INITIAL_GENERATION,
    val memberId: ProtocolBytes,
    val deviceId: ProtocolBytes,
    val incarnationId: ProtocolBytes,
    val sequence: Long,
    val previousOperationId: ProtocolBytes?,
    val frontier: List<FeedFrontier>,
    val authorizationEpoch: Long,
    val payloadSchema: Int = PomoSuite.PREFERENCE_SCHEMA,
    val kind: Int = PomoSuite.PREFERENCE_SET_KIND,
    val payloadHash: ProtocolBytes,
) {
    init {
        require(memberId.size == PomoSuite.ID_BYTES)
        require(deviceId.size == PomoSuite.ID_BYTES)
        require(incarnationId.size == PomoSuite.INCARNATION_BYTES)
        require(sequence > 0)
        require((sequence == 1L) == (previousOperationId == null))
        require(previousOperationId == null || previousOperationId.size == PomoSuite.ID_BYTES)
        require(authorizationEpoch >= 0)
        require(payloadHash.size == PomoSuite.ID_BYTES)
        require(frontier.distinctBy { it.deviceId.toString() to it.incarnationId.toString() }.size == frontier.size)
    }
}

internal data class AuthenticatedOperation(
    val operation: UnsignedOperation,
    val canonicalUnsigned: ByteArray,
    val operationId: ProtocolBytes,
    val canonicalPayload: ByteArray,
    val signedEnvelope: ByteArray,
)

internal data class AuthorRequest(
    val memberId: ProtocolBytes,
    val deviceId: ProtocolBytes,
    val incarnationId: ProtocolBytes,
    val authorizationEpoch: Long,
    val frontier: List<FeedFrontier>,
    val preference: PreferenceSet,
    val authorized: Boolean,
    val deviceReady: Boolean,
    val completePrerequisites: Set<String>,
) {
    init {
        require(memberId.size == PomoSuite.ID_BYTES)
        require(deviceId.size == PomoSuite.ID_BYTES)
        require(incarnationId.size == PomoSuite.INCARNATION_BYTES)
        require(authorizationEpoch >= 0)
    }
}

internal sealed interface AuthorResult {
    data class Authored(
        val value: AuthenticatedOperation,
        val disposition: IngestDisposition,
    ) : AuthorResult

    data class Blocked(val missing: Set<String>) : AuthorResult
}

internal enum class IngestDisposition {
    ACCEPTED,
    DUPLICATE,
    PENDING_GAP,
    PENDING_CAUSAL,
    QUARANTINED_FORK,
    REJECTED_INVALID,
    REJECTED_UNSUPPORTED_SUITE,
}

internal data class OperationReclassification(
    val operation: AuthenticatedOperation,
    val disposition: IngestDisposition,
)

internal data class KernelSummary(
    val heads: Map<String, Pair<Long, ProtocolBytes?>>,
    val gaps: Set<String>,
    val causalWaits: Set<String>,
    val forks: Set<String>,
    val accepted: Int,
    val pending: Int,
    val quarantined: Int,
    val rejected: Int,
    val dispositionCounts: Map<IngestDisposition, Int>,
)

internal data class CheckpointFeed(
    val deviceId: ProtocolBytes,
    val incarnationId: ProtocolBytes,
    val coveredOperationIds: List<ProtocolBytes>,
)

internal data class KernelCheckpoint(
    val suite: Int,
    val suiteGeneration: Long,
    val feeds: List<CheckpointFeed>,
    val materializedPreferences: List<CheckpointPreference>,
)

internal data class CheckpointPreference(
    val key: String,
    val value: String,
)

internal enum class RestoreResult {
    RESTORED,
    REJECTED_CHECKPOINT,
}

internal object PomoSuite {
    const val ID: Int = 1
    const val INITIAL_GENERATION: Long = 1
    const val ID_BYTES: Int = 32
    const val INCARNATION_BYTES: Int = 16
    const val PREFERENCE_SCHEMA: Int = 1
    const val PREFERENCE_SET_KIND: Int = 1
    const val ESP256: Int = -9
    const val COSE_SIGN1_TAG: Long = 18
    const val COSE_ALGORITHM_LABEL: Long = 1
    const val COSE_CRITICAL_LABEL: Long = 2
    const val COSE_SUITE_LABEL: Long = -65_537
    const val COSE_GENERATION_LABEL: Long = -65_538
    const val COSE_OBJECT_KIND_LABEL: Long = -65_539
    const val COSE_SCHEMA_LABEL: Long = -65_540
    const val COSE_DEVICE_ID_LABEL: Long = -65_541
    const val COSE_OPERATION_KIND: Long = 1
    const val COSE_OPERATION_SCHEMA: Long = 1
    const val COSE_EXTERNAL_AAD: String = "Pomo/Operation/1"
    const val RAW_P256_SIGNATURE_BYTES: Int = 64
    const val HPKE_KEM_ID: Int = 0x0010
    const val HPKE_KDF_ID: Int = 0x0001
    const val HPKE_AEAD_ID: Int = 0x0001
    const val HPKE_ENCAPSULATED_KEY_BYTES: Int = 65
    const val HPKE_KEY_BYTES: Int = 16
    const val GCM_NONCE_BYTES: Int = 12
    const val GCM_TAG_BITS: Int = 128
    const val ARGON2_MEMORY_KIB: Int = 65_536
    const val ARGON2_ITERATIONS: Int = 3
    const val ARGON2_LANES: Int = 4
    const val ARGON2_SALT_BYTES: Int = 16
    const val ARGON2_OUTPUT_BYTES: Int = 32
}
