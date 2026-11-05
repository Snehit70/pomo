package com.pomo.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class SessionImportPayloadsTest {
    private val now: Long = 1_700_000_000L

    @Test
    public fun parseAndValidate_acceptsValidCompletedWork() {
        val result =
            SessionImportPayloads.parseAndValidate(
                """
                {
                  "source": "desk",
                  "sessions": [
                    {
                      "client_id": "a1",
                      "type": "work",
                      "duration": 1500,
                      "completed": true,
                      "start": ${now - 2000}
                    }
                  ]
                }
                """.trimIndent(),
                nowSeconds = now,
            )

        assertEquals(1, result.accepted.size)
        assertEquals(0, result.rejected.size)
        assertEquals("a1", result.accepted[0].clientId)
        assertEquals("work", result.accepted[0].session.type)
        assertEquals(1500, result.accepted[0].session.duration)
        assertEquals(now - 2000, result.accepted[0].session.start)
        assertFalse(result.accepted[0].alreadyPresent)
    }

    @Test
    public fun parseAndValidate_rejectsInvalidTypeDurationAndIncomplete() {
        val result =
            SessionImportPayloads.parseAndValidate(
                """
                {
                  "sessions": [
                    {"client_id":"t","type":"focus","duration":60,"completed":true},
                    {"client_id":"d","type":"work","duration":0,"completed":true},
                    {"client_id":"c","type":"work","duration":59,"completed":false},
                    {"client_id":"","type":"work","duration":60,"completed":true}
                  ]
                }
                """.trimIndent(),
                nowSeconds = now,
            )

        assertEquals(0, result.accepted.size)
        assertEquals(4, result.rejected.size)
        assertEquals("invalid type", result.rejected[0].error)
        assertEquals("duration must be > 0", result.rejected[1].error)
        assertEquals("partial under 60s", result.rejected[2].error)
        assertEquals("client_id required", result.rejected[3].error)
    }

    @Test
    public fun parseAndValidate_acceptsPartialWorkFromDeskSkip() {
        val result =
            SessionImportPayloads.parseAndValidate(
                """
                {
                  "sessions": [
                    {"client_id":"p","type":"work","duration":600,"completed":false,"start":${now - 700}},
                    {"client_id":"b","type":"short","duration":120,"completed":false},
                    {"client_id":"n","type":"work","duration":600}
                  ]
                }
                """.trimIndent(),
                nowSeconds = now,
            )

        assertEquals(1, result.accepted.size)
        assertEquals("p", result.accepted[0].clientId)
        assertFalse(result.accepted[0].session.completed)
        assertEquals(600, result.accepted[0].session.duration)
        assertEquals(2, result.rejected.size)
        assertEquals("partial must be work", result.rejected[0].error)
        assertEquals("completed required", result.rejected[1].error)
    }

    @Test
    public fun parseAndValidate_assignsMissingStartsPreservingOrderWithoutCollision() {
        val result =
            SessionImportPayloads.parseAndValidate(
                """
                {
                  "sessions": [
                    {"client_id":"s1","type":"work","duration":1500,"completed":true},
                    {"client_id":"s2","type":"short","duration":300,"completed":true},
                    {"client_id":"s3","type":"work","duration":1500,"completed":true}
                  ]
                }
                """.trimIndent(),
                nowSeconds = now,
            )

        assertEquals(3, result.accepted.size)
        val starts = result.accepted.map { it.session.start }
        assertEquals(starts.toSet().size, starts.size)
        assertTrue(starts[0] < starts[1])
        assertTrue(starts[1] < starts[2])
        // Newest ends at now: last start + duration == now
        assertEquals(now, starts[2] + 1500)
        assertEquals(starts[2], starts[1] + 300)
        assertEquals(starts[1], starts[0] + 1500)
    }

    @Test
    public fun parseAndValidate_duplicateStartOrClientIdIsIdempotentAccept() {
        val start = now - 1000
        val result =
            SessionImportPayloads.parseAndValidate(
                """
                {
                  "sessions": [
                    {"client_id":"dup","type":"work","duration":600,"completed":true,"start":$start},
                    {"client_id":"other","type":"work","duration":600,"completed":true,"start":$start}
                  ]
                }
                """.trimIndent(),
                nowSeconds = now,
                knownStarts = setOf(start),
                knownClientIds = setOf("dup"),
            )

        assertEquals(2, result.accepted.size)
        assertTrue(result.accepted.all { it.alreadyPresent })
        assertEquals(0, result.rejected.size)
    }

    @Test
    public fun parseAndValidate_duplicateClientIdInRequestIsTerminalAccept() {
        val result =
            SessionImportPayloads.parseAndValidate(
                """
                {
                  "sessions": [
                    {"client_id":"dup","type":"work","duration":600,"completed":true},
                    {"client_id":"dup","type":"work","duration":300,"completed":true}
                  ]
                }
                """.trimIndent(),
                nowSeconds = now,
            )

        assertEquals(2, result.accepted.size)
        assertFalse(result.accepted[0].alreadyPresent)
        assertTrue(result.accepted[1].alreadyPresent)
        assertEquals(0, result.rejected.size)
    }

    @Test
    public fun parseAndValidate_rejectsStartsOutsidePlausibleWindow() {
        val tooOld = now - SessionImportPayloads.MAX_START_AGE_SECONDS - 10
        val tooFuture = now + SessionImportPayloads.MAX_START_FUTURE_SKEW_SECONDS + 10
        val result =
            SessionImportPayloads.parseAndValidate(
                """
                {
                  "sessions": [
                    {"client_id":"old","type":"work","duration":60,"completed":true,"start":$tooOld},
                    {"client_id":"fut","type":"work","duration":60,"completed":true,"start":$tooFuture}
                  ]
                }
                """.trimIndent(),
                nowSeconds = now,
            )

        assertEquals(0, result.accepted.size)
        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.all { it.error == "start out of range" })
    }

    @Test(expected = IllegalArgumentException::class)
    public fun parseAndValidate_malformedJsonIsRejected() {
        SessionImportPayloads.parseAndValidate("{not json", nowSeconds = now)
    }
}
