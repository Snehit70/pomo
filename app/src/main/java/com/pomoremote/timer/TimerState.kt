package com.pomoremote.timer

import com.google.gson.annotations.SerializedName

public class TimerState {
    public companion object {
        public const val STATUS_STOPPED: String = "stopped"
        public const val STATUS_RUNNING: String = "running"
        public const val STATUS_PAUSED: String = "paused"

        public const val PHASE_WORK: String = "work"
        public const val PHASE_SHORT: String = "short"
        public const val PHASE_LONG: String = "long"
    }

    @JvmField public var status: String = STATUS_STOPPED
    @JvmField public var phase: String = PHASE_WORK
    @JvmField @SerializedName("next_phase") public var next_phase: String? = null
    @JvmField @SerializedName("start_time") public var start_time: Double = 0.0
    @JvmField public var duration: Double = 0.0
    @JvmField public var remaining: Double = 0.0
    @JvmField public var completed: Int = 0
    @JvmField @SerializedName("daily_goal") public var goal: Int = 8
    @JvmField public var date: String? = null
    @JvmField @SerializedName("last_action_time") public var last_action_time: Long = 0
    @JvmField public var version: Int = 2

    public fun copy(): TimerState {
        val new = TimerState()
        new.status = status
        new.phase = phase
        new.next_phase = next_phase
        new.start_time = start_time
        new.duration = duration
        new.remaining = remaining
        new.completed = completed
        new.goal = goal
        new.date = date
        new.last_action_time = last_action_time
        new.version = version
        return new
    }
}
