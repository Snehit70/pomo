package com.pomo.crew

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CrewSecretCipher : CrewStoreCipher {
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun encryptString(plaintext: String): String {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            ENVELOPE_VERSION,
            encode(cipher.iv),
            encode(ciphertext),
        ).joinToString(".")
    }

    override fun decryptString(envelope: String): String? {
        return runCatching {
            val parts = envelope.split('.')
            require(parts.size == 3 && parts[0] == ENVELOPE_VERSION)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(GCM_TAG_BITS, decode(parts[1])),
            )
            String(cipher.doFinal(decode(parts[2])), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private companion object {
        private const val KEYSTORE_PROVIDER: String = "AndroidKeyStore"
        private const val KEY_ALIAS: String = "pomo_crew_v2_wrapping_key"
        private const val CIPHER_ALGORITHM: String = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS: Int = 128
        private const val ENVELOPE_VERSION: String = "v1"
    }
}
