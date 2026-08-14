package com.pomo.sync.crypto

import com.pomo.sync.protocol.AuthenticatedOperation
import com.pomo.sync.protocol.CborValue
import com.pomo.sync.protocol.DeterministicCbor
import com.pomo.sync.protocol.OperationCodec
import com.pomo.sync.protocol.OperationSigner
import com.pomo.sync.protocol.OperationVerifier
import com.pomo.sync.protocol.ProtocolBytes
import com.pomo.sync.protocol.UnsignedOperation
import java.security.PrivateKey
import java.security.PublicKey

/** Transport wire shared with Chrome: [tagged COSE_Sign1 Operation, fact payload]. */
internal object CoseOperationWire {
    fun deviceId(wire: ByteArray): ProtocolBytes {
        val fields = wireFields(wire)
        val cose = DeterministicCbor.encode(fields[0])
        return OperationCodec.decodeUnsignedForVerification(CoseSign1.embeddedPayload(cose)).deviceId
    }

    fun sign(
        operation: UnsignedOperation,
        canonicalPayload: ByteArray,
        privateKey: PrivateKey,
    ): AuthenticatedOperation {
        OperationCodec.decodePreference(canonicalPayload)
        require(OperationCodec.payloadHash(canonicalPayload) == operation.payloadHash)
        val canonicalUnsigned = OperationCodec.encodeUnsigned(operation)
        val cose = CoseSign1.sign(operation, canonicalUnsigned, privateKey)
        val wire =
            DeterministicCbor.encode(
                CborValue.Array(
                    listOf(
                        DeterministicCbor.decodeCanonical(cose),
                        CborValue.Bytes(canonicalPayload),
                    ),
                ),
            )
        return AuthenticatedOperation(
            operation,
            canonicalUnsigned,
            OperationCodec.operationId(canonicalUnsigned),
            canonicalPayload.copyOf(),
            wire,
        )
    }

    fun verify(
        wire: ByteArray,
        publicKey: PublicKey,
    ): AuthenticatedOperation {
        val fields = wireFields(wire)
        val cose = DeterministicCbor.encode(fields[0])
        val payload =
            (fields[1] as? CborValue.Bytes)?.value
                ?: throw IllegalArgumentException("Operation fact payload must be a byte string")
        val canonicalUnsigned = CoseSign1.embeddedPayload(cose)
        val operation = OperationCodec.decodeUnsignedForVerification(canonicalUnsigned)
        OperationCodec.decodePreference(payload)
        require(OperationCodec.payloadHash(payload) == operation.payloadHash)
        CoseSign1.verify(cose, operation, canonicalUnsigned, publicKey)
        return AuthenticatedOperation(
            operation,
            canonicalUnsigned,
            OperationCodec.operationId(canonicalUnsigned),
            payload,
            wire.copyOf(),
        )
    }

    private fun wireFields(wire: ByteArray): List<CborValue> {
        val fields =
            (DeterministicCbor.decodeCanonical(wire) as? CborValue.Array)?.values
                ?: throw IllegalArgumentException("Authenticated Operation wire must be an array")
        require(fields.size == 2)
        return fields
    }
}

internal class CoseKernelSigner(private val privateKey: PrivateKey) : OperationSigner {
    override fun sign(
        operation: UnsignedOperation,
        canonicalPayload: ByteArray,
        canonicalUnsigned: ByteArray,
        operationId: ProtocolBytes,
    ): ByteArray {
        require(OperationCodec.encodeUnsigned(operation).contentEquals(canonicalUnsigned))
        require(OperationCodec.operationId(canonicalUnsigned) == operationId)
        return CoseOperationWire.sign(operation, canonicalPayload, privateKey).signedEnvelope
    }
}

internal class CoseKernelVerifier(
    private val resolvePublicKey: (ProtocolBytes) -> PublicKey?,
) : OperationVerifier {
    override fun verify(signedEnvelope: ByteArray): AuthenticatedOperation {
        val deviceId = CoseOperationWire.deviceId(signedEnvelope)
        val publicKey =
            resolvePublicKey(deviceId)
                ?: throw IllegalArgumentException("Unknown Device signing key")
        return CoseOperationWire.verify(signedEnvelope, publicKey)
    }
}
