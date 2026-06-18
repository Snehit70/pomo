package com.pomo.crew

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

public object CrewNostrKeys {
    private val secp256k1: Secp256k1 = Secp256k1.get()

    public fun generatePrivateKeyHex(): String {
        while (true) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            if (secp256k1.secKeyVerify(bytes)) return bytes.toHex()
        }
    }

    public fun publicKeyHex(privateKeyHex: String): String {
        val uncompressed = secp256k1.pubkeyCreate(privateKeyHex.hexToBytes())
        return uncompressed.copyOfRange(1, 33).toHex()
    }

    public fun signSchnorr(messageHex: String, privateKeyHex: String): String =
        secp256k1.signSchnorr(messageHex.hexToBytes(), privateKeyHex.hexToBytes(), null).toHex()

    public fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    public fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
