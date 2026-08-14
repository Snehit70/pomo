package com.pomo.sync.crypto

import com.pomo.sync.protocol.OperationCodec
import com.pomo.sync.protocol.PreferenceSet
import com.pomo.sync.protocol.PreferenceValue
import com.pomo.sync.protocol.ProtocolBytes
import com.pomo.sync.protocol.UnsignedOperation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

public class CoseAndHpkeTest {
    @Test
    public fun coseSign1BindsProtectedProfilePayloadAndDevice() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val payload = OperationCodec.encodePreference(PreferenceSet("timer.sound", PreferenceValue.Text("bell")))
        val operation = operation(payload)
        val canonical = OperationCodec.encodeUnsigned(operation)
        val envelope = CoseSign1.sign(operation, canonical, pair.private)
        val authenticatedWire = CoseOperationWire.sign(operation, payload, pair.private)

        CoseSign1.verify(envelope, operation, canonical, pair.public)
        val operationId = OperationCodec.operationId(canonical)
        val kernelEnvelope =
            CoseKernelSigner(pair.private).sign(
                operation,
                payload,
                canonical,
                operationId,
            )
        val kernelVerified =
            CoseKernelVerifier { deviceId ->
                if (deviceId == operation.deviceId) pair.public else null
            }.verify(kernelEnvelope)
        assertTrue(kernelVerified.operationId == operationId)
        val verified = CoseOperationWire.verify(authenticatedWire.signedEnvelope, pair.public)
        assertArrayEquals(payload, verified.canonicalPayload)
        assertTrue(verified.operationId == OperationCodec.operationId(canonical))
        assertThrowsAny { CoseSign1.verify(envelope, operation.copy(deviceId = id(9)), canonical, pair.public) }
        assertThrowsAny {
            CoseSign1.verify(envelope.copyOf().also { it[it.lastIndex] = (it.last() xor 1) }, operation, canonical, pair.public)
        }
    }

    @Test
    public fun hpkeBaseModeRoundTripsAndAuthenticatesContext() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val recipient = generator.generateKeyPair()
        val sealed =
            HpkeP256.seal(
                recipient.public,
                info = "content-epoch".toByteArray(),
                aad = "header".toByteArray(),
                plaintext = "wrapped key".toByteArray(),
            )
        assertArrayEquals(
            "wrapped key".toByteArray(),
            HpkeP256.open(
                recipient.private,
                recipient.public,
                sealed,
                info = "content-epoch".toByteArray(),
                aad = "header".toByteArray(),
            ),
        )
        assertThrowsAny {
            HpkeP256.open(recipient.private, recipient.public, sealed, "content-epoch".toByteArray(), "wrong".toByteArray())
        }
    }

    private fun operation(payload: ByteArray): UnsignedOperation =
        UnsignedOperation(
            memberId = id(1),
            deviceId = id(2),
            incarnationId = ProtocolBytes.of(ByteArray(16) { 3 }, 16),
            sequence = 1,
            previousOperationId = null,
            frontier = emptyList(),
            authorizationEpoch = 1,
            payloadHash = OperationCodec.payloadHash(payload),
        )

    private fun id(value: Byte): ProtocolBytes = ProtocolBytes.of(ByteArray(32) { value }, 32)

    private fun assertThrowsAny(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
