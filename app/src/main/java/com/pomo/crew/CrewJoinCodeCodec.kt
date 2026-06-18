package com.pomo.crew

import com.google.gson.Gson
import java.security.SecureRandom
import java.util.Base64

public object CrewJoinCodeCodec {
    private const val PREFIX: String = "pomo-crew."
    private const val VERSION: Int = 1
    private val gson = Gson()

    public fun newPayload(
        crewId: String = randomHex(16),
        relays: List<String> = CrewDefaults.DEFAULT_RELAYS,
        key: String = randomHex(32),
    ): CrewJoinPayload = CrewJoinPayload(
        crewId = crewId,
        relays = relays.filter { it.isNotBlank() },
        key = key,
    )

    public fun encode(payload: CrewJoinPayload): String {
        require(payload.crewId.isNotBlank())
        require(payload.relays.isNotEmpty())
        require(payload.key.isNotBlank())
        val encoded = EncodedJoinPayload(
            version = VERSION,
            crewId = payload.crewId,
            relays = payload.relays,
            key = payload.key,
        )
        val bytes = gson.toJson(encoded).toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    public fun decode(code: String): CrewJoinPayload? {
        if (!code.startsWith(PREFIX)) return null
        return try {
            val raw = code.removePrefix(PREFIX)
            val json = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
            val decoded = gson.fromJson(json, EncodedJoinPayload::class.java)
            if (decoded.version != VERSION) return null
            val relays = decoded.relays.orEmpty().filter { it.isNotBlank() }.ifEmpty { CrewDefaults.DEFAULT_RELAYS }
            if (decoded.crewId.isBlank() || decoded.key.isBlank()) return null
            CrewJoinPayload(decoded.crewId, relays, decoded.key)
        } catch (_: Exception) {
            null
        }
    }

    private data class EncodedJoinPayload(
        val version: Int = VERSION,
        val crewId: String = "",
        val relays: List<String>? = emptyList(),
        val key: String = "",
    )

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
