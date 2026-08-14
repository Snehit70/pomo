package com.pomo.sync.crypto

import com.pomo.sync.protocol.CborValue
import com.pomo.sync.protocol.DeterministicCbor
import com.pomo.sync.protocol.PomoSuite
import com.pomo.sync.protocol.UnsignedOperation
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey

/** Fixed, non-negotiable POMO-SUITE-1 COSE_Sign1 profile for Operations. */
internal object CoseSign1 {
    private val criticalLabels: List<Long> =
        listOf(
            PomoSuite.COSE_SUITE_LABEL,
            PomoSuite.COSE_GENERATION_LABEL,
            PomoSuite.COSE_OBJECT_KIND_LABEL,
            PomoSuite.COSE_SCHEMA_LABEL,
            PomoSuite.COSE_DEVICE_ID_LABEL,
        )

    fun sign(
        operation: UnsignedOperation,
        canonicalUnsigned: ByteArray,
        privateKey: PrivateKey,
    ): ByteArray {
        val protected = protectedHeaders(operation)
        val signature = PomoCrypto.signP256LowS(privateKey, signatureStructure(protected, canonicalUnsigned))
        return DeterministicCbor.encode(
            CborValue.Tagged(
                PomoSuite.COSE_SIGN1_TAG,
                CborValue.Array(
                    listOf(
                        CborValue.Bytes(protected),
                        CborValue.Map(emptyList()),
                        CborValue.Bytes(canonicalUnsigned),
                        CborValue.Bytes(signature),
                    ),
                ),
            ),
        )
    }

    /** Returns normally only for the one exact POMO-SUITE-1 protected profile. */
    fun verify(
        envelope: ByteArray,
        operation: UnsignedOperation,
        canonicalUnsigned: ByteArray,
        publicKey: PublicKey,
    ) {
        val tagged =
            DeterministicCbor.decodeCanonical(envelope) as? CborValue.Tagged
                ?: throw IllegalArgumentException("COSE_Sign1 tag is required")
        require(tagged.tag == PomoSuite.COSE_SIGN1_TAG)
        val fields =
            (tagged.value as? CborValue.Array)?.values
                ?: throw IllegalArgumentException("COSE_Sign1 value must be an array")
        require(fields.size == 4) { "COSE_Sign1 must contain four fields" }
        val protected =
            (fields[0] as? CborValue.Bytes)?.value
                ?: throw IllegalArgumentException("COSE protected headers must be a byte string")
        val unprotected =
            fields[1] as? CborValue.Map
                ?: throw IllegalArgumentException("COSE unprotected headers must be a map")
        require(unprotected.entries.isEmpty()) { "POMO-SUITE-1 forbids unprotected headers" }
        val payload =
            (fields[2] as? CborValue.Bytes)?.value
                ?: throw IllegalArgumentException("Detached COSE payloads are forbidden")
        require(payload.contentEquals(canonicalUnsigned)) { "COSE payload does not match Operation" }
        val signature =
            (fields[3] as? CborValue.Bytes)?.value
                ?: throw IllegalArgumentException("COSE signature must be a byte string")

        val expectedProtected = protectedHeaders(operation)
        require(protected.contentEquals(expectedProtected)) { "Unexpected COSE protected headers" }
        PomoCrypto.verifyP256LowS(publicKey, signatureStructure(protected, payload), signature)
    }

    fun embeddedPayload(envelope: ByteArray): ByteArray {
        val tagged =
            DeterministicCbor.decodeCanonical(envelope) as? CborValue.Tagged
                ?: throw IllegalArgumentException("COSE_Sign1 tag is required")
        require(tagged.tag == PomoSuite.COSE_SIGN1_TAG)
        val fields =
            (tagged.value as? CborValue.Array)?.values
                ?: throw IllegalArgumentException("COSE_Sign1 value must be an array")
        require(fields.size == 4)
        return (fields[2] as? CborValue.Bytes)?.value
            ?: throw IllegalArgumentException("Detached COSE payloads are forbidden")
    }

    fun protectedHeaders(operation: UnsignedOperation): ByteArray =
        DeterministicCbor.encode(
            CborValue.Map(
                listOf(
                    CborValue.Integer(PomoSuite.COSE_ALGORITHM_LABEL) to CborValue.Integer(PomoSuite.ESP256.toLong()),
                    CborValue.Integer(PomoSuite.COSE_CRITICAL_LABEL) to
                        CborValue.Array(
                            criticalLabels.map(CborValue::Integer),
                        ),
                    CborValue.Integer(PomoSuite.COSE_SUITE_LABEL) to CborValue.Integer(operation.suite.toLong()),
                    CborValue.Integer(PomoSuite.COSE_GENERATION_LABEL) to CborValue.Integer(operation.suiteGeneration),
                    CborValue.Integer(PomoSuite.COSE_OBJECT_KIND_LABEL) to CborValue.Integer(PomoSuite.COSE_OPERATION_KIND),
                    CborValue.Integer(PomoSuite.COSE_SCHEMA_LABEL) to CborValue.Integer(PomoSuite.COSE_OPERATION_SCHEMA),
                    CborValue.Integer(PomoSuite.COSE_DEVICE_ID_LABEL) to CborValue.Bytes(operation.deviceId.copy()),
                ),
            ),
        )

    fun signatureStructure(
        protected: ByteArray,
        payload: ByteArray,
    ): ByteArray =
        DeterministicCbor.encode(
            CborValue.Array(
                listOf(
                    CborValue.Text("Signature1"),
                    CborValue.Bytes(protected),
                    CborValue.Bytes(PomoSuite.COSE_EXTERNAL_AAD.toByteArray(StandardCharsets.UTF_8)),
                    CborValue.Bytes(payload),
                ),
            ),
        )
}
