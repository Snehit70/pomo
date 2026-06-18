package com.pomo.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

public class CrewJoinCodeCodecTest {
    @Test
    public fun encodeDecode_roundTripRecoversPayload() {
        val payload = CrewJoinPayload(
            crewId = "crew-1",
            relays = listOf("wss://relay.example", "wss://backup.example"),
            key = "abc123",
        )

        val decoded = CrewJoinCodeCodec.decode(CrewJoinCodeCodec.encode(payload))

        assertEquals(payload, decoded)
    }

    @Test
    public fun decode_malformedCodeIsRejected() {
        assertNull(CrewJoinCodeCodec.decode("not-a-crew-code"))
        assertNull(CrewJoinCodeCodec.decode("pomo-crew.not-base64"))
    }

    @Test
    public fun newPayload_usesDefaultsAndRandomValues() {
        val payload = CrewJoinCodeCodec.newPayload()

        assertNotNull(CrewJoinCodeCodec.decode(CrewJoinCodeCodec.encode(payload)))
        assertEquals(CrewDefaults.DEFAULT_RELAYS, payload.relays)
    }
}
