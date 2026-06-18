package com.pomo.crew

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

public object CrewIdentityKeys {
    private const val SIGNATURE_ALGORITHM: String = "SHA256withRSA"
    private const val KEY_ALGORITHM: String = "RSA"
    private const val KEY_BITS: Int = 2048

    public fun generate(): CrewIdentity {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(KEY_BITS)
        val pair = generator.generateKeyPair()
        return CrewIdentity(
            privateKey = encode(pair.private.encoded),
            publicKey = encode(pair.public.encoded),
        )
    }

    public fun isValidPrivateKey(privateKey: String): Boolean =
        runCatching { privateKey(privateKey) }.isSuccess

    public fun sign(message: ByteArray, privateKey: String): String {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey(privateKey))
        signer.update(message)
        return encode(signer.sign())
    }

    public fun verify(message: ByteArray, signature: String, publicKey: String): Boolean {
        return try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey(publicKey))
            verifier.update(message)
            verifier.verify(decode(signature))
        } catch (_: Exception) {
            false
        }
    }

    private fun privateKey(privateKey: String): PrivateKey {
        val spec = PKCS8EncodedKeySpec(decode(privateKey))
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(spec)
    }

    private fun publicKey(publicKey: String): PublicKey {
        val spec = X509EncodedKeySpec(decode(publicKey))
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(spec)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)
}
