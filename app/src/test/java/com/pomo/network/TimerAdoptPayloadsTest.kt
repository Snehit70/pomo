package com.pomo.network

import com.pomo.timer.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class TimerAdoptPayloadsTest {
    @Test
    public fun parse_acceptsValidRunningWork() {
        val payload =
            TimerAdoptPayloads.parse(
                """
                {
                  "status": "running",
                  "phase": "work",
                  "remaining": 900,
                  "duration": 1500,
                  "start_time": 1700000000.0,
                  "completed": 2,
                  "daily_goal": 8,
                  "tag": "deep"
                }
                """.trimIndent(),
            )

        assertEquals(TimerState.STATUS_RUNNING, payload.status)
        assertEquals(TimerState.PHASE_WORK, payload.phase)
        assertEquals(900.0, payload.remaining, 0.0)
        assertEquals(1500.0, payload.duration, 0.0)
        assertEquals(1700000000.0, payload.start_time, 0.0)
        assertEquals(2, payload.completed)
        assertEquals(8, payload.daily_goal)
        assertEquals("deep", payload.tag)
    }

    @Test(expected = IllegalArgumentException::class)
    public fun parse_rejectsInvalidStatus() {
        TimerAdoptPayloads.parse(
            """{"status":"idle","phase":"work","remaining":1,"duration":10,"start_time":1,"completed":0,"daily_goal":8}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    public fun parse_rejectsRemainingAboveDuration() {
        TimerAdoptPayloads.parse(
            """{"status":"running","phase":"work","remaining":20,"duration":10,"start_time":1,"completed":0,"daily_goal":8}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    public fun parse_rejectsDurationAboveMaximum() {
        TimerAdoptPayloads.parse(
            """{"status":"stopped","phase":"work","remaining":0,"duration":86401,"start_time":0,"completed":0,"daily_goal":8}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    public fun parse_rejectsNonFiniteDuration() {
        TimerAdoptPayloads.parse(
            """{"status":"stopped","phase":"work","remaining":0,"duration":1e309,"start_time":0,"completed":0,"daily_goal":8}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    public fun parse_rejectsZeroStartTimeWhenRunning() {
        TimerAdoptPayloads.parse(
            """{"status":"running","phase":"work","remaining":10,"duration":100,"start_time":0,"completed":0,"daily_goal":8}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    public fun parse_rejectsMissingStartTimeWhenPaused() {
        // Missing start_time defaults to 0, which is invalid for live timers.
        TimerAdoptPayloads.parse(
            """{"status":"paused","phase":"work","remaining":10,"duration":100,"completed":0,"daily_goal":8}""",
        )
    }

    @Test
    public fun parse_acceptsZeroOrMissingStartTimeWhenStopped() {
        val withZero =
            TimerAdoptPayloads.parse(
                """{"status":"stopped","phase":"work","remaining":0,"duration":1500,"start_time":0,"completed":0,"daily_goal":8}""",
            )
        assertEquals(0.0, withZero.start_time, 0.0)

        val missing =
            TimerAdoptPayloads.parse(
                """{"status":"stopped","phase":"work","remaining":0,"duration":1500,"completed":1,"daily_goal":8}""",
            )
        assertEquals(0.0, missing.start_time, 0.0)
    }

    @Test
    public fun isSameSession_falseWhenStartTimeNotPositive() {
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_STOPPED
                phase = TimerState.PHASE_WORK
                start_time = 0.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"stopped","phase":"work","remaining":0,"duration":1500,"start_time":0,"completed":0,"daily_goal":8}""",
            )
        assertFalse(TimerAdoptPayloads.isSameSession(phone, payload))
    }

    @Test
    public fun canAdopt_whenPhoneStopped() {
        val phone = TimerState().apply { status = TimerState.STATUS_STOPPED }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":100,"duration":1500,"start_time":99.0,"completed":1,"daily_goal":8}""",
            )
        assertTrue(TimerAdoptPayloads.canAdopt(phone, payload))
    }

    @Test
    public fun canAdopt_whenSameSessionRunning() {
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 42.0
                remaining = 50.0
                duration = 1500.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":40,"duration":1500,"start_time":42.0,"completed":1,"daily_goal":8}""",
            )
        assertTrue(TimerAdoptPayloads.canAdopt(phone, payload))
        assertTrue(TimerAdoptPayloads.isSameSession(phone, payload))
    }

    @Test
    public fun canAdopt_falseWhenDifferentLiveSession() {
        // Different live sessions where desk remaining is not strictly less → 409.
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_PAUSED
                phase = TimerState.PHASE_WORK
                start_time = 100.0
                remaining = 40.0
                duration = 1500.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":80,"duration":1500,"start_time":200.0,"completed":1,"daily_goal":8}""",
            )
        assertFalse(TimerAdoptPayloads.canAdopt(phone, payload))
    }

    @Test
    public fun canAdopt_trueWhenDeskHasLessRemainingOnDifferentSession() {
        // Phone running 24:58, desk running 15:00, different start_time → adopt desk.
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 1000.0
                remaining = 1498.0
                duration = 1500.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":900,"duration":1500,"start_time":2000.0,"completed":1,"daily_goal":8}""",
            )
        assertTrue(TimerAdoptPayloads.canAdopt(phone, payload))
        assertFalse(TimerAdoptPayloads.isSameSession(phone, payload))
    }

    @Test
    public fun canAdopt_falseWhenDeskHasMoreRemainingOnDifferentSession() {
        // Phone remaining 60, desk remaining 120 → phone keeps clock (409).
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 100.0
                remaining = 60.0
                duration = 1500.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":120,"duration":1500,"start_time":200.0,"completed":1,"daily_goal":8}""",
            )
        assertFalse(TimerAdoptPayloads.canAdopt(phone, payload))
    }

    @Test
    public fun canAdopt_falseWhenEqualRemainingOnDifferentSession() {
        // Equal remaining, different session → phone keeps (strict less required).
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 100.0
                remaining = 500.0
                duration = 1500.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"paused","phase":"work","remaining":500,"duration":1500,"start_time":200.0,"completed":1,"daily_goal":8}""",
            )
        assertFalse(TimerAdoptPayloads.canAdopt(phone, payload))
    }

    @Test
    public fun adoptReason_phoneStopped() {
        val phone = TimerState().apply { status = TimerState.STATUS_STOPPED }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":100,"duration":1500,"start_time":99.0,"completed":1,"daily_goal":8}""",
            )
        assertEquals(TimerAdoptPayloads.REASON_PHONE_STOPPED, TimerAdoptPayloads.adoptReason(phone, payload))
    }

    @Test
    public fun adoptReason_sameSession() {
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 42.0
                remaining = 50.0
                duration = 1500.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":40,"duration":1500,"start_time":42.0,"completed":1,"daily_goal":8}""",
            )
        assertEquals(TimerAdoptPayloads.REASON_SAME_SESSION, TimerAdoptPayloads.adoptReason(phone, payload))
    }

    @Test
    public fun adoptReason_leastRemainingAndDeskNotShorter() {
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 100.0
                remaining = 500.0
                duration = 1500.0
            }
        val shorter =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":100,"duration":1500,"start_time":200.0,"completed":1,"daily_goal":8}""",
            )
        val longer =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":900,"duration":1500,"start_time":300.0,"completed":1,"daily_goal":8}""",
            )
        assertEquals(TimerAdoptPayloads.REASON_LEAST_REMAINING, TimerAdoptPayloads.adoptReason(phone, shorter))
        assertEquals(TimerAdoptPayloads.REASON_DESK_NOT_SHORTER, TimerAdoptPayloads.adoptReason(phone, longer))
    }

    @Test
    public fun adoptReason_notLiveWhenDeskStopped() {
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 100.0
                remaining = 500.0
                duration = 1500.0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """{"status":"stopped","phase":"work","remaining":0,"duration":1500,"start_time":0,"completed":1,"daily_goal":8}""",
            )
        assertEquals(TimerAdoptPayloads.REASON_NOT_LIVE, TimerAdoptPayloads.adoptReason(phone, payload))
    }

    @Test
    public fun canAdopt_matchesAdoptReasons() {
        val phone =
            TimerState().apply {
                status = TimerState.STATUS_RUNNING
                phase = TimerState.PHASE_WORK
                start_time = 100.0
                remaining = 500.0
                duration = 1500.0
            }
        val adoptPayload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":100,"duration":1500,"start_time":200.0,"completed":1,"daily_goal":8}""",
            )
        val rejectPayload =
            TimerAdoptPayloads.parse(
                """{"status":"running","phase":"work","remaining":900,"duration":1500,"start_time":300.0,"completed":1,"daily_goal":8}""",
            )
        assertTrue(TimerAdoptPayloads.canAdopt(phone, adoptPayload))
        assertTrue(
            TimerAdoptPayloads.adoptReason(phone, rejectPayload) ==
                TimerAdoptPayloads.REASON_DESK_NOT_SHORTER &&
                !TimerAdoptPayloads.canAdopt(phone, rejectPayload),
        )
    }

    @Test
    public fun applyTo_copiesPayloadIntoState() {
        val base =
            TimerState().apply {
                status = TimerState.STATUS_STOPPED
                phase = TimerState.PHASE_SHORT
                completed = 0
            }
        val payload =
            TimerAdoptPayloads.parse(
                """
                {"status":"paused","phase":"long","remaining":60,"duration":900,
                  "start_time":5.0,"completed":3,"daily_goal":10,"tag":"x"}
                """.trimIndent(),
            )
        val next = TimerAdoptPayloads.applyTo(base, payload, nowSeconds = 99L)
        assertEquals(TimerState.STATUS_PAUSED, next.status)
        assertEquals(TimerState.PHASE_LONG, next.phase)
        assertEquals(60.0, next.remaining, 0.0)
        assertEquals(900.0, next.duration, 0.0)
        assertEquals(5.0, next.start_time, 0.0)
        assertEquals(3, next.completed)
        assertEquals(10, next.goal)
        assertEquals("x", next.tag)
        assertEquals(99L, next.last_action_time)
    }
}
