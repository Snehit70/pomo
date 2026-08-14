package com.pomo.sync.crypto

import com.pomo.sync.protocol.PomoSuite
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class HpkeCiphertext(
    val encapsulatedKey: ByteArray,
    val ciphertextAndTag: ByteArray,
)

/** RFC 9180 Base mode: DHKEM(P-256, HKDF-SHA256)/HKDF-SHA256/AES-128-GCM. */
internal object HpkeP256 {
    private val hpkeVersion = "HPKE-v1".toByteArray(StandardCharsets.US_ASCII)
    private val kemSuiteId = "KEM".toByteArray(StandardCharsets.US_ASCII) + i2osp(PomoSuite.HPKE_KEM_ID, 2)
    private val suiteId =
        "HPKE".toByteArray(StandardCharsets.US_ASCII) +
            i2osp(PomoSuite.HPKE_KEM_ID, 2) +
            i2osp(PomoSuite.HPKE_KDF_ID, 2) +
            i2osp(PomoSuite.HPKE_AEAD_ID, 2)
    private val parameters: ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

    fun seal(
        recipientPublicKey: PublicKey,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): HpkeCiphertext {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return sealWithEphemeralKey(recipientPublicKey, generator.generateKeyPair(), info, aad, plaintext)
    }

    /** Deterministic seam used only by the shared RFC 9180 corpus. */
    fun sealWithEphemeralKey(
        recipientPublicKey: PublicKey,
        ephemeralKeyPair: KeyPair,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): HpkeCiphertext {
        val recipient = requireP256Public(recipientPublicKey)
        val ephemeral = requireP256Public(ephemeralKeyPair.public)
        requireP256Private(ephemeralKeyPair.private)
        val enc = serialize(ephemeral)
        val sharedSecret =
            kemSharedSecret(
                dh(ephemeralKeyPair.private, recipient),
                enc + serialize(recipient),
            )
        return try {
            val context = keySchedule(sharedSecret, info)
            HpkeCiphertext(enc, aesGcm(Cipher.ENCRYPT_MODE, context.key, context.baseNonce, aad, plaintext))
        } finally {
            sharedSecret.fill(0)
        }
    }

    fun open(
        recipientPrivateKey: PrivateKey,
        recipientPublicKey: PublicKey,
        sealed: HpkeCiphertext,
        info: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(sealed.encapsulatedKey.size == PomoSuite.HPKE_ENCAPSULATED_KEY_BYTES)
        require(sealed.ciphertextAndTag.size >= PomoSuite.GCM_TAG_BITS / 8)
        val recipientPrivate = requireP256Private(recipientPrivateKey)
        val recipientPublic = requireP256Public(recipientPublicKey)
        val encapsulated = publicKeyFromUncompressed(sealed.encapsulatedKey)
        val sharedSecret =
            kemSharedSecret(
                dh(recipientPrivate, encapsulated),
                sealed.encapsulatedKey + serialize(recipientPublic),
            )
        return try {
            val context = keySchedule(sharedSecret, info)
            aesGcm(Cipher.DECRYPT_MODE, context.key, context.baseNonce, aad, sealed.ciphertextAndTag)
        } finally {
            sharedSecret.fill(0)
        }
    }

    fun privateKeyFromScalar(scalar: ByteArray): PrivateKey {
        require(scalar.size == 32)
        val value = BigInteger(1, scalar)
        require(value.signum() > 0 && value < parameters.order) { "Invalid P-256 private scalar" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(value, parameters))
    }

    fun publicKeyFromUncompressed(encoded: ByteArray): PublicKey {
        require(encoded.size == PomoSuite.HPKE_ENCAPSULATED_KEY_BYTES && encoded[0] == 0x04.toByte()) {
            "P-256 public key must be uncompressed SEC1"
        }
        val point =
            ECPoint(
                BigInteger(1, encoded.copyOfRange(1, 33)),
                BigInteger(1, encoded.copyOfRange(33, 65)),
            )
        requirePointOnCurve(point)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }

    fun serialize(publicKey: PublicKey): ByteArray {
        val key = requireP256Public(publicKey)
        return byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private data class Context(val key: ByteArray, val baseNonce: ByteArray)

    private fun kemSharedSecret(
        dh: ByteArray,
        kemContext: ByteArray,
    ): ByteArray {
        val eaePrk = labeledExtract(ByteArray(0), kemSuiteId, "eae_prk", dh)
        return try {
            labeledExpand(eaePrk, kemSuiteId, "shared_secret", kemContext, 32)
        } finally {
            eaePrk.fill(0)
            dh.fill(0)
        }
    }

    private fun keySchedule(
        sharedSecret: ByteArray,
        info: ByteArray,
    ): Context {
        val pskIdHash = labeledExtract(ByteArray(0), suiteId, "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(ByteArray(0), suiteId, "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(sharedSecret, suiteId, "secret", ByteArray(0))
        return try {
            Context(
                key = labeledExpand(secret, suiteId, "key", context, PomoSuite.HPKE_KEY_BYTES),
                baseNonce = labeledExpand(secret, suiteId, "base_nonce", context, PomoSuite.GCM_NONCE_BYTES),
            )
        } finally {
            pskIdHash.fill(0)
            infoHash.fill(0)
            secret.fill(0)
        }
    }

    private fun labeledExtract(
        salt: ByteArray,
        selectedSuiteId: ByteArray,
        label: String,
        ikm: ByteArray,
    ): ByteArray =
        hmac(
            if (salt.isEmpty()) ByteArray(32) else salt,
            hpkeVersion + selectedSuiteId + label.toByteArray(StandardCharsets.US_ASCII) + ikm,
        )

    private fun labeledExpand(
        prk: ByteArray,
        selectedSuiteId: ByteArray,
        label: String,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val labeledInfo =
            i2osp(length, 2) + hpkeVersion + selectedSuiteId +
                label.toByteArray(StandardCharsets.US_ASCII) + info
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val block = hmac(prk, previous + labeledInfo + byteArrayOf(counter.toByte()))
            block.copyInto(output, offset, 0, minOf(block.size, length - offset))
            offset += minOf(block.size, length - offset)
            previous.fill(0)
            previous = block
            counter += 1
        }
        previous.fill(0)
        return output
    }

    private fun hmac(
        key: ByteArray,
        value: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value)
    }

    private fun dh(
        privateKey: PrivateKey,
        publicKey: PublicKey,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val secret = agreement.generateSecret()
        require(secret.size <= 32)
        return ByteArray(32).also { secret.copyInto(it, 32 - secret.size) }
    }

    private fun aesGcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(PomoSuite.GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    private fun requireP256Private(key: PrivateKey): ECPrivateKey {
        val ec = key as? ECPrivateKey ?: throw IllegalArgumentException("Expected an EC private key")
        require(ec.params.order == parameters.order) { "Expected a P-256 private key" }
        return ec
    }

    private fun requireP256Public(key: PublicKey): ECPublicKey {
        val ec = key as? ECPublicKey ?: throw IllegalArgumentException("Expected an EC public key")
        require(ec.params.order == parameters.order) { "Expected a P-256 public key" }
        requirePointOnCurve(ec.w)
        return ec
    }

    private fun requirePointOnCurve(point: ECPoint) {
        val curve = parameters.curve
        val field =
            curve.field as? java.security.spec.ECFieldFp
                ?: throw IllegalStateException("P-256 must use a prime field")
        val prime = field.p
        val x = point.affineX
        val y = point.affineY
        require(x.signum() >= 0 && x < prime && y.signum() >= 0 && y < prime) { "P-256 point is out of range" }
        require(
            y.modPow(BigInteger.valueOf(2), prime) ==
                x.modPow(BigInteger.valueOf(3), prime)
                    .add(curve.a.multiply(x)).add(curve.b).mod(prime),
        ) { "P-256 point is not on the curve" }
    }

    private fun fixed(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val unsigned = if (encoded.size == 33 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, 33) else encoded
        require(unsigned.size <= 32)
        return ByteArray(32).also { unsigned.copyInto(it, 32 - unsigned.size) }
    }

    private fun i2osp(
        value: Int,
        length: Int,
    ): ByteArray {
        require(value >= 0 && (length == 2 && value <= 0xffff))
        return ByteArray(length) { index -> (value ushr (8 * (length - index - 1))).toByte() }
    }
}
