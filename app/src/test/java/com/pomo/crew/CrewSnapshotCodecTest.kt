package com.pomo.crew

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

public class CrewSnapshotCodecTest {
    private val gson = Gson()
    private val identity = CrewIdentityKeys.generate()
    private val snapshot = CrewSnapshot(
        crewId = "crew-1",
        identityPublicKey = identity.publicKey,
        displayName = "Snehit",
        allTimeFocusMinutes = 125,
        publishedAtEpochSeconds = 1234,
    )

    @Test
    public fun encodeEncrypted_roundTripRecoversSnapshotAndHidesPlaintext() {
        val payload = CrewSnapshotCodec.encodeEncrypted(snapshot, "crew-secret", identity)

        assertFalse(payload.contains("Snehit"))
        assertFalse(payload.contains("allTimeFocusMinutes"))
        assertEquals(snapshot, CrewSnapshotCodec.decodeEncrypted(payload, "crew-secret"))
    }

    @Test
    public fun decodeEncrypted_wrongKeyFailsCleanly() {
        val payload = CrewSnapshotCodec.encodeEncrypted(snapshot, "crew-secret", identity)

        assertNull(CrewSnapshotCodec.decodeEncrypted(payload, "wrong-secret"))
    }

    @Test
    public fun decodeEncrypted_tamperedCiphertextIsRejected() {
        val payload = CrewSnapshotCodec.encodeEncrypted(snapshot, "crew-secret", identity)
        val envelope = requireNotNull(CrewSnapshotCodec.decodeEnvelope(payload))
        val tampered = envelope.copy(ciphertext = envelope.ciphertext.replaceLastChar())

        assertNull(CrewSnapshotCodec.decodeEncrypted(gson.toJson(tampered), "crew-secret"))
    }

    @Test
    public fun decodeEncrypted_badSignatureIsRejected() {
        val payload = CrewSnapshotCodec.encodeEncrypted(snapshot, "crew-secret", identity)
        val envelope = requireNotNull(CrewSnapshotCodec.decodeEnvelope(payload))
        val otherIdentity = CrewIdentityKeys.generate()
        val otherPayload = CrewSnapshotCodec.encodeEncrypted(
            snapshot.copy(identityPublicKey = otherIdentity.publicKey),
            "crew-secret",
            otherIdentity,
        )
        val otherEnvelope = requireNotNull(CrewSnapshotCodec.decodeEnvelope(otherPayload))
        val tampered = envelope.copy(signature = otherEnvelope.signature)

        assertNull(CrewSnapshotCodec.decodeEncrypted(gson.toJson(tampered), "crew-secret"))
    }

    @Test
    public fun decodeEncrypted_nullEnvelopeFieldsAreRejected() {
        val payload = """
            {
              "version": 1,
              "crewId": null,
              "identityPublicKey": null,
              "nonce": null,
              "ciphertext": null,
              "signature": null
            }
        """.trimIndent()

        assertNull(CrewSnapshotCodec.decodeEncrypted(payload, "crew-secret"))
    }

    @Test
    public fun decodeEnvelope_keepsRelayPayloadOpaque() {
        val payload = CrewSnapshotCodec.encodeEncrypted(snapshot, "crew-secret", identity)
        val envelope = CrewSnapshotCodec.decodeEnvelope(payload)

        assertNotNull(envelope)
        assertEquals(snapshot.crewId, envelope?.crewId)
        assertEquals(snapshot.identityPublicKey, envelope?.identityPublicKey)
    }

    private fun String.replaceLastChar(): String {
        val replacement = if (last() == 'A') 'B' else 'A'
        return dropLast(1) + replacement
    }
}
