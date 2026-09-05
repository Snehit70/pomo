package com.pomo.network

import com.google.gson.Gson
import com.pomo.timer.TimerState

/**
 * Parse and validate `POST /api/timer/adopt` bodies so the desk can hand a live offline
 * (or shorter) timer to the phone.
 *
 * **Least-remaining rule:** when both phone and desk have a live timer (running or paused)
 * on different sessions, the phone adopts the desk payload only if desk remaining is
 * strictly less than phone remaining. Otherwise the phone keeps its clock (HTTP 409).
 * Phone STOPPED always adopts; same session always refreshes.
 */
public object TimerAdoptPayloads {
    private val gson = Gson()

    /** Keep untrusted timer payloads finite and bounded to one day. */
    public const val MAX_DURATION_SECONDS: Double = 24.0 * 60.0 * 60.0

    public data class Payload(
        val status: String,
        val phase: String,
        val remaining: Double,
        val duration: Double,
        val start_time: Double,
        val completed: Int,
        val daily_goal: Int,
        val tag: String,
    )

    public data class WireBody(
        val status: String? = null,
        val phase: String? = null,
        val remaining: Double? = null,
        val duration: Double? = null,
        val start_time: Double? = null,
        val completed: Int? = null,
        val daily_goal: Int? = null,
        val tag: String? = null,
    )

    public fun parse(body: String): Payload {
        val wire =
            try {
                gson.fromJson(body, WireBody::class.java)
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid adopt body", e)
            } ?: throw IllegalArgumentException("adopt body must be a JSON object")

        val status = wire.status?.trim().orEmpty()
        if (status !in ALLOWED_STATUS) {
            throw IllegalArgumentException("invalid status")
        }

        val phase = wire.phase?.trim().orEmpty()
        if (phase !in ALLOWED_PHASES) {
            throw IllegalArgumentException("invalid phase")
        }

        val duration = wire.duration ?: throw IllegalArgumentException("duration required")
        if (!duration.isFinite() || duration <= 0.0 || duration > MAX_DURATION_SECONDS) {
            throw IllegalArgumentException("duration must be > 0")
        }

        val remaining = wire.remaining ?: throw IllegalArgumentException("remaining required")
        if (!remaining.isFinite() || remaining < 0.0 || remaining > MAX_DURATION_SECONDS) {
            throw IllegalArgumentException("remaining must be >= 0")
        }
        if (remaining > duration) {
            throw IllegalArgumentException("remaining cannot exceed duration")
        }

        val startTime = wire.start_time ?: 0.0
        if (!startTime.isFinite() || startTime < 0.0) {
            throw IllegalArgumentException("start_time must be >= 0")
        }
        // Live timers need a positive start_time so same-session identity works
        // (isSameSession requires start_time > 0 on both sides). Stopped may omit/0.
        if (status != TimerState.STATUS_STOPPED && startTime <= 0.0) {
            throw IllegalArgumentException("start_time must be > 0 when running or paused")
        }

        val completed = wire.completed ?: 0
        if (completed < 0) {
            throw IllegalArgumentException("completed must be >= 0")
        }

        val dailyGoal = wire.daily_goal ?: 0
        if (dailyGoal < 0) {
            throw IllegalArgumentException("daily_goal must be >= 0")
        }

        val tag = wire.tag?.trim().orEmpty()

        return Payload(
            status = status,
            phase = phase,
            remaining = remaining,
            duration = duration,
            start_time = startTime,
            completed = completed,
            daily_goal = dailyGoal,
            tag = tag,
        )
    }

    /**
     * Whether [payload] describes the same live session as [current].
     * Matching start_time + phase is enough: remaining may tick on either side.
     */
    public fun isSameSession(
        current: TimerState,
        payload: Payload,
    ): Boolean {
        if (current.start_time <= 0.0 || payload.start_time <= 0.0) return false
        return current.start_time == payload.start_time && current.phase == payload.phase
    }

    /**
     * Whether the phone should adopt [payload] as the sole live clock.
     *
     * - Phone [TimerState.STATUS_STOPPED] → always true.
     * - Same session ([isSameSession]) → true (desk refresh).
     * - Both sides live (running or paused) on different sessions → true only when
     *   `payload.remaining < current.remaining` (strict least-remaining).
     * - Otherwise false; caller should respond HTTP 409 `timer_busy`.
     *
     * Single source of truth is [adoptReason]; this returns true for the
     * adopt reasons so the decision and its logged explanation cannot drift.
     */
    public fun canAdopt(
        current: TimerState,
        payload: Payload,
    ): Boolean = adoptReason(current, payload) in ADOPT_REASONS

    /**
     * Why [canAdopt] says yes or no, as a stable code for the link activity log.
     */
    public fun adoptReason(
        current: TimerState,
        payload: Payload,
    ): String {
        if (current.status == TimerState.STATUS_STOPPED) return REASON_PHONE_STOPPED
        if (isSameSession(current, payload)) return REASON_SAME_SESSION
        if (!isLiveStatus(current.status) || !isLiveStatus(payload.status)) return REASON_NOT_LIVE
        return if (payload.remaining < current.remaining) {
            REASON_LEAST_REMAINING
        } else {
            REASON_DESK_NOT_SHORTER
        }
    }

    private fun isLiveStatus(status: String): Boolean {
        return status == TimerState.STATUS_RUNNING || status == TimerState.STATUS_PAUSED
    }

    public fun applyTo(
        state: TimerState,
        payload: Payload,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): TimerState {
        val next = state.copy()
        next.status = payload.status
        next.phase = payload.phase
        next.remaining = payload.remaining
        next.duration = payload.duration
        next.start_time = payload.start_time
        next.completed = payload.completed
        next.goal = payload.daily_goal
        next.tag = payload.tag
        next.last_action_time = nowSeconds
        next.next_phase = null
        return next
    }

    public const val REASON_PHONE_STOPPED: String = "phone-stopped"
    public const val REASON_SAME_SESSION: String = "same-session"
    public const val REASON_LEAST_REMAINING: String = "least-remaining"
    public const val REASON_DESK_NOT_SHORTER: String = "desk-not-shorter"
    public const val REASON_NOT_LIVE: String = "not-live"

    private val ADOPT_REASONS: Set<String> =
        setOf(REASON_PHONE_STOPPED, REASON_SAME_SESSION, REASON_LEAST_REMAINING)

    private val ALLOWED_STATUS: Set<String> =
        setOf(
            TimerState.STATUS_STOPPED,
            TimerState.STATUS_RUNNING,
            TimerState.STATUS_PAUSED,
        )

    private val ALLOWED_PHASES: Set<String> =
        setOf(
            TimerState.PHASE_WORK,
            TimerState.PHASE_SHORT,
            TimerState.PHASE_LONG,
        )
}
