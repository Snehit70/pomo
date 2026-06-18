package com.pomo.crew

import org.junit.Assert.assertEquals
import org.junit.Test

public class CrewRelayTransportTest {
    @Test
    public fun filterValidRelayUrls_rejectsMalformedOverridesAndDedupes() {
        val relays = CrewRelayTransport.filterValidRelayUrls(
            listOf(
                "wss://relay.example",
                "wss://",
                "https://relay.example",
                "not-a-url",
                "wss://relay.example",
            ),
        )

        assertEquals(listOf("wss://relay.example"), relays)
    }
}
