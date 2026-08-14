package com.pomo.sync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

public class PomoCryptoTest {
    @Test
    public fun hkdfSha256MatchesRfc5869CaseOne() {
        val output =
            PomoCrypto.hkdfSha256(
                ikm = ByteArray(22) { 0x0b },
                salt = "000102030405060708090a0b0c".hex(),
                info = "f0f1f2f3f4f5f6f7f8f9".hex(),
                outputBytes = 42,
            )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            output.toHex(),
        )
    }

    @Test
    public fun aesGcmAuthenticatesHeaderAndRejectsTampering() {
        val sealed =
            PomoCrypto.encryptAesGcm(
                key = ByteArray(32) { it.toByte() },
                nonce = ByteArray(12) { (it + 1).toByte() },
                aad = "header".toByteArray(),
                plaintext = "payload".toByteArray(),
            )
        assertArrayEquals(
            "payload".toByteArray(),
            PomoCrypto.decryptAesGcm(ByteArray(32) { it.toByte() }, sealed, "header".toByteArray()),
        )
        assertThrowsAny {
            PomoCrypto.decryptAesGcm(ByteArray(32) { it.toByte() }, sealed, "wrong".toByteArray())
        }
    }

    @Test
    public fun p256WireSignatureIsFixedWidthLowSAndRejectsHighS() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val message = "pomo-operation".toByteArray()
        val signature = PomoCrypto.signP256LowS(pair.private, message)
        PomoCrypto.verifyP256LowS(pair.public, message, signature)
        assertThrowsAny { PomoCrypto.verifyP256LowS(pair.public, message, signature.copyOf(63)) }

        val order =
            java.math.BigInteger(
                "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
                16,
            )
        val lowS = java.math.BigInteger(1, signature.copyOfRange(32, 64))
        val highS = fixed(order - lowS)
        val malleated = signature.copyOf().also { highS.copyInto(it, 32) }
        assertThrowsAny { PomoCrypto.verifyP256LowS(pair.public, message, malleated) }
    }

    private fun fixed(value: java.math.BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val unsigned = if (encoded.size == 33) encoded.copyOfRange(1, 33) else encoded
        return ByteArray(32).also { unsigned.copyInto(it, 32 - unsigned.size) }
    }

    private fun assertThrowsAny(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
