package com.pomo.crew

import com.google.gson.Gson
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

public object CrewSnapshotCodec {
    private const val VERSION: Int = 1
    private const val NONCE_BYTES: Int = 12
    private const val GCM_TAG_BITS: Int = 128
    private const val AES_ALGORITHM: String = "AES"
    private const val CIPHER_ALGORITHM: String = "AES/GCM/NoPadding"
    private val gson = Gson()

    public fun encodePlaintext(snapshot: CrewSnapshot): String = gson.toJson(snapshot)

    public fun decodePlaintext(payload: String): CrewSnapshot? = try {
        val snapshot = gson.fromJson(payload, CrewSnapshot::class.java)
        if (snapshot.crewId.isBlank() || snapshot.identityPublicKey.isBlank()) null else snapshot
    } catch (_: Exception) {
        null
    }

    public fun encodeEncrypted(
        snapshot: CrewSnapshot,
        crewKey: String,
        identity: CrewIdentity,
    ): String {
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        val ciphertext = encrypt(
            plaintext = encodePlaintext(snapshot).toByteArray(Charsets.UTF_8),
            crewKey = crewKey,
            nonce = nonce,
        )
        val envelopeWithoutSignature = CrewSnapshotEnvelope(
            version = VERSION,
            crewId = snapshot.crewId,
            identityPublicKey = snapshot.identityPublicKey,
            nonce = encode(nonce),
            ciphertext = encode(ciphertext),
            signature = "",
        )
        val signature = CrewIdentityKeys.sign(
            signatureMessage(envelopeWithoutSignature),
            identity.privateKey,
        )
        return gson.toJson(envelopeWithoutSignature.copy(signature = signature))
    }

    public fun decodeEncrypted(payload: String, crewKey: String): CrewSnapshot? {
        return try {
            val envelope = decodeEnvelope(payload) ?: return null
            if (envelope.version != VERSION) return null
            if (
                envelope.crewId.isBlank() ||
                envelope.identityPublicKey.isBlank() ||
                envelope.nonce.isBlank() ||
                envelope.ciphertext.isBlank() ||
                envelope.signature.isBlank()
            ) {
                return null
            }
            if (!CrewIdentityKeys.verify(signatureMessage(envelope.copy(signature = "")), envelope.signature, envelope.identityPublicKey)) {
                return null
            }
            val plaintext = decrypt(
                ciphertext = decode(envelope.ciphertext),
                crewKey = crewKey,
                nonce = decode(envelope.nonce),
            )
            val snapshot = decodePlaintext(String(plaintext, Charsets.UTF_8)) ?: return null
            if (
                snapshot.crewId == envelope.crewId &&
                snapshot.identityPublicKey == envelope.identityPublicKey
            ) {
                snapshot
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    public fun decodeEnvelope(payload: String): CrewSnapshotEnvelope? = try {
        gson.fromJson(payload, CrewSnapshotEnvelope::class.java)
    } catch (_: Exception) {
        null
    }

    private fun encrypt(plaintext: ByteArray, crewKey: String, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(crewKey), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, crewKey: String, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(crewKey), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(crewKey: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(crewKey.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, AES_ALGORITHM)
    }

    private fun signatureMessage(envelope: CrewSnapshotEnvelope): ByteArray =
        listOf(
            envelope.version.toString(),
            envelope.crewId,
            envelope.identityPublicKey,
            envelope.nonce,
            envelope.ciphertext,
        ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)
}

public data class CrewSnapshotEnvelope(
    val version: Int = 1,
    val crewId: String = "",
    val identityPublicKey: String = "",
    val nonce: String = "",
    val ciphertext: String = "",
    val signature: String = "",
)
