package com.pomo.sync.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object OperationCodec {
    fun encodeUnsigned(operation: UnsignedOperation): ByteArray = encodeUnsigned(operation, allowUnsupportedSuite = false)

    internal fun encodeUnsignedForVerification(operation: UnsignedOperation): ByteArray =
        encodeUnsigned(operation, allowUnsupportedSuite = true)

    private fun encodeUnsigned(
        operation: UnsignedOperation,
        allowUnsupportedSuite: Boolean,
    ): ByteArray {
        if (!allowUnsupportedSuite) {
            require(operation.suite == PomoSuite.ID)
            require(operation.suiteGeneration == PomoSuite.INITIAL_GENERATION)
        }
        require(operation.payloadSchema == PomoSuite.PREFERENCE_SCHEMA)
        require(operation.kind == PomoSuite.PREFERENCE_SET_KIND)
        val sortedFrontier =
            operation.frontier.sortedWith { left, right ->
                compareBytes(left.deviceId.copy(), right.deviceId.copy()).takeIf { it != 0 }
                    ?: compareBytes(left.incarnationId.copy(), right.incarnationId.copy())
            }
        val value =
            CborValue.Array(
                listOf(
                    CborValue.Integer(operation.suite.toLong()),
                    CborValue.Integer(operation.suiteGeneration),
                    CborValue.Bytes(operation.memberId.copy()),
                    CborValue.Bytes(operation.deviceId.copy()),
                    CborValue.Bytes(operation.incarnationId.copy()),
                    CborValue.Integer(operation.sequence),
                    operation.previousOperationId?.let { CborValue.Bytes(it.copy()) } ?: CborValue.Null,
                    CborValue.Array(sortedFrontier.map(::encodeFrontier)),
                    CborValue.Integer(operation.authorizationEpoch),
                    CborValue.Integer(operation.payloadSchema.toLong()),
                    CborValue.Integer(operation.kind.toLong()),
                    CborValue.Bytes(operation.payloadHash.copy()),
                ),
            )
        return DeterministicCbor.encode(value)
    }

    fun operationId(canonicalUnsigned: ByteArray): ProtocolBytes {
        DeterministicCbor.decodeCanonical(canonicalUnsigned)
        val domain =
            DeterministicCbor.encode(
                CborValue.Array(
                    listOf(
                        CborValue.Text("Pomo Operation ID"),
                        CborValue.Integer(PomoSuite.ID.toLong()),
                        CborValue.Bytes(canonicalUnsigned),
                    ),
                ),
            )
        return ProtocolBytes.of(MessageDigest.getInstance("SHA-256").digest(domain), PomoSuite.ID_BYTES)
    }

    fun decodeUnsigned(canonicalUnsigned: ByteArray): UnsignedOperation =
        decodeUnsigned(canonicalUnsigned, allowUnsupportedSuite = false)

    internal fun decodeUnsignedForVerification(canonicalUnsigned: ByteArray): UnsignedOperation =
        decodeUnsigned(canonicalUnsigned, allowUnsupportedSuite = true)

    private fun decodeUnsigned(
        canonicalUnsigned: ByteArray,
        allowUnsupportedSuite: Boolean,
    ): UnsignedOperation {
        val fields =
            (DeterministicCbor.decodeCanonical(canonicalUnsigned) as? CborValue.Array)?.values
                ?: throw IllegalArgumentException("Operation must be an array")
        require(fields.size == 12) { "Operation must contain twelve fields" }
        val frontierValues =
            (fields[7] as? CborValue.Array)?.values
                ?: throw IllegalArgumentException("Operation frontier must be an array")
        val frontier =
            frontierValues.map { raw ->
                val entry =
                    (raw as? CborValue.Array)?.values
                        ?: throw IllegalArgumentException("Frontier entry must be an array")
                require(entry.size == 4)
                FeedFrontier(
                    bytes(entry[0], PomoSuite.ID_BYTES, "frontier device"),
                    bytes(entry[1], PomoSuite.INCARNATION_BYTES, "frontier incarnation"),
                    unsigned(entry[2], "frontier sequence").also { require(it > 0) },
                    bytes(entry[3], PomoSuite.ID_BYTES, "frontier head"),
                )
            }
        val operation =
            UnsignedOperation(
                suite = unsigned(fields[0], "suite").toInt(),
                suiteGeneration = unsigned(fields[1], "suite generation"),
                memberId = bytes(fields[2], PomoSuite.ID_BYTES, "member ID"),
                deviceId = bytes(fields[3], PomoSuite.ID_BYTES, "device ID"),
                incarnationId = bytes(fields[4], PomoSuite.INCARNATION_BYTES, "incarnation ID"),
                sequence = unsigned(fields[5], "sequence"),
                previousOperationId =
                    if (fields[6] == CborValue.Null) {
                        null
                    } else {
                        bytes(
                            fields[6],
                            PomoSuite.ID_BYTES,
                            "previous Operation ID",
                        )
                    },
                frontier = frontier,
                authorizationEpoch = unsigned(fields[8], "authorization epoch"),
                payloadSchema = unsigned(fields[9], "payload schema").toInt(),
                kind = unsigned(fields[10], "Operation kind").toInt(),
                payloadHash = bytes(fields[11], PomoSuite.ID_BYTES, "payload hash"),
            )
        require(encodeUnsigned(operation, allowUnsupportedSuite).contentEquals(canonicalUnsigned)) {
            "Operation meaning is not canonical"
        }
        return operation
    }

    fun encodePreference(preference: PreferenceSet): ByteArray {
        require(preference.key.toByteArray(StandardCharsets.UTF_8).size in 1..128) { "Preference key exceeds profile" }
        return DeterministicCbor.encode(
            CborValue.Array(
                listOf(
                    CborValue.Integer(PomoSuite.PREFERENCE_SET_KIND.toLong()),
                    CborValue.Text(preference.key),
                    when (val value = preference.value) {
                        is PreferenceValue.Text -> {
                            require(value.value.toByteArray(StandardCharsets.UTF_8).size <= 4096) {
                                "Preference value exceeds profile"
                            }
                            CborValue.Text(value.value)
                        }
                        else -> throw IllegalArgumentException("Tracer bullet preference value must be text")
                    },
                ),
            ),
        )
    }

    fun decodePreference(canonicalPayload: ByteArray): PreferenceSet {
        val fields =
            (DeterministicCbor.decodeCanonical(canonicalPayload) as? CborValue.Array)?.values
                ?: throw IllegalArgumentException("Preference payload must be an array")
        require(fields.size == 3 && fields[0] == CborValue.Integer(PomoSuite.PREFERENCE_SET_KIND.toLong())) {
            "Unsupported preference payload"
        }
        val key =
            (fields[1] as? CborValue.Text)?.value
                ?: throw IllegalArgumentException("Preference key must be text")
        val value =
            (fields[2] as? CborValue.Text)?.value
                ?: throw IllegalArgumentException("Preference value must be text")
        require(key.toByteArray(StandardCharsets.UTF_8).size in 1..128) { "Preference key exceeds profile" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 4096) { "Preference value exceeds profile" }
        return PreferenceSet(key, PreferenceValue.Text(value))
    }

    fun payloadHash(canonicalPayload: ByteArray): ProtocolBytes {
        DeterministicCbor.decodeCanonical(canonicalPayload)
        return ProtocolBytes.of(MessageDigest.getInstance("SHA-256").digest(canonicalPayload), PomoSuite.ID_BYTES)
    }

    private fun encodeFrontier(frontier: FeedFrontier): CborValue =
        CborValue.Array(
            listOf(
                CborValue.Bytes(frontier.deviceId.copy()),
                CborValue.Bytes(frontier.incarnationId.copy()),
                CborValue.Integer(frontier.sequence),
                CborValue.Bytes(frontier.headOperationId.copy()),
            ),
        )

    private fun compareBytes(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        for (index in left.indices) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun unsigned(
        value: CborValue,
        name: String,
    ): Long {
        val integer =
            (value as? CborValue.Integer)?.value
                ?: throw IllegalArgumentException("$name must be an integer")
        require(integer >= 0) { "$name must be unsigned" }
        return integer
    }

    private fun bytes(
        value: CborValue,
        size: Int,
        name: String,
    ): ProtocolBytes {
        val encoded =
            (value as? CborValue.Bytes)?.value
                ?: throw IllegalArgumentException("$name must be a byte string")
        return ProtocolBytes.of(encoded, size)
    }
}
