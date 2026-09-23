package com.pomo.network

import com.google.gson.Gson
import com.pomo.timer.TimerState

/**
 * Parse and validate `POST /api/timer/adopt` bodies so the desk can hand a live offline
 * (or shorter) timer to the phone.
 *
 * **Focus-over-break precedence:** when both phone and desk have a live timer (running
 * or paused) on different sessions, the work side wins across classes — desk work vs
 * phone break always adopts (any remaining); desk break vs phone work never adopts
 * (HTTP 409, desk snaps to phone). Same class (both work, or both break including
 * short-vs-long) falls back to strict least-remaining. Phone STOPPED always adopts;
 * same session always refreshes.
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
     * - Both sides live (running or paused) on different sessions, different
     *   classes (work vs short/long break) → work side wins: desk work vs
     *   phone break is true regardless of remaining; desk break vs phone
     *   work is false (HTTP 409 `timer_busy`, desk snaps to phone).
     * - Both sides live, same class (both work, or both break including
     *   short-vs-long) → true only when `payload.remaining < current.remaining`
     *   (strict least-remaining).
     * - Otherwise false; caller should respond HTTP 409 `timer_busy`.
     */
    public fun canAdopt(
        current: TimerState,
        payload: Payload,
    ): Boolean {
        if (current.status == TimerState.STATUS_STOPPED) return true
        if (isSameSession(current, payload)) return true
        if (!isLiveStatus(current.status) || !isLiveStatus(payload.status)) return false
        // Phase classes: work vs break. Non-work counts as break so short-vs-long
        // stays in the same class and falls through to least-remaining.
        val currentWork = current.phase == TimerState.PHASE_WORK
        val payloadWork = payload.phase == TimerState.PHASE_WORK
        if (currentWork != payloadWork) {
            // Focus overrides break: only the work side wins.
            return payloadWork
        }
        return payload.remaining < current.remaining
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
