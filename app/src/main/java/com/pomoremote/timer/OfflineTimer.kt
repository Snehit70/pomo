package com.pomoremote.timer

import android.os.CountDownTimer
import com.pomoremote.db.HistoryCacheRepository
import com.pomoremote.models.Session
import com.pomoremote.service.PomodoroService
import com.pomoremote.util.UtilPreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class OfflineTimer(
    private val service: PomodoroService,
    private val prefs: UtilPreferenceManager,
    private val historyRepository: HistoryCacheRepository,
    private val scope: CoroutineScope
) {
    private var timer: CountDownTimer? = null
    var state: TimerState = TimerState()
        private set

    fun updateState(newState: TimerState) {
        this.state = newState
        if (TimerState.STATUS_RUNNING == state.status) {
            startLocalTimer()
        } else {
            stopLocalTimer()
        }
    }

    private fun startLocalTimer() {
        stopLocalTimer()

        val remainingMillis = (state.remaining * 1000).toLong()
        if (remainingMillis <= 0) return

        timer = object : CountDownTimer(remainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                state.remaining = millisUntilFinished / 1000.0
                service.onTimerUpdate(state)
            }

            override fun onFinish() {
                handleTimerComplete()
            }
        }.start()
    }

    private fun stopLocalTimer() {
        timer?.cancel()
        timer = null
    }

    private fun handleTimerComplete() {
        // Record session before updating state
        val session = Session(
            type = state.phase,
            start = System.currentTimeMillis() / 1000 - state.duration.toLong(),
            duration = state.duration.toInt(),
            completed = true
        )

        scope.launch {
            historyRepository.saveLocalSession(session, prefs.dayStartHour)

            state.remaining = 0.0
            state.status = TimerState.STATUS_STOPPED

            if (TimerState.PHASE_WORK == state.phase) {
                // Calculate completed from session history (source of truth)
                state.completed = historyRepository.getTodayCompletedCount(prefs.dayStartHour)
                state.date = historyRepository.getEffectiveDateString(prefs.dayStartHour)
                val longBreakAfter = prefs.longBreakAfter

                if (state.completed > 0 && state.completed % longBreakAfter == 0) {
                    state.phase = TimerState.PHASE_LONG
                    state.duration = (prefs.longBreakDuration * 60).toDouble()
                } else {
                    state.phase = TimerState.PHASE_SHORT
                    state.duration = (prefs.shortBreakDuration * 60).toDouble()
                }
            } else {
                // Break is done, back to work
                state.phase = TimerState.PHASE_WORK
                state.duration = (prefs.pomodoroDuration * 60).toDouble()
            }

            recalculateNextPhase()

            state.remaining = state.duration
            service.onTimerComplete(state)
        }
    }

    fun toggle() {
        if (TimerState.STATUS_RUNNING == state.status) {
            state.status = TimerState.STATUS_PAUSED
            state.last_action_time = System.currentTimeMillis() / 1000
            stopLocalTimer()
            service.onTimerUpdate(state)
        } else {
            state.status = TimerState.STATUS_RUNNING
            state.last_action_time = System.currentTimeMillis() / 1000
            if (state.remaining <= 0) {
                // Should not happen usually as onFinish resets it, but just in case
                state.remaining = getDurationForPhase(state.phase)
                state.duration = state.remaining
            }
            // Set start_time to now - elapsed, matching Go logic
            state.start_time = (System.currentTimeMillis() / 1000).toDouble() - (state.duration - state.remaining)
            startLocalTimer()
            service.onTimerUpdate(state)
        }
    }

    fun skip() {
        state.status = TimerState.STATUS_STOPPED
        state.last_action_time = System.currentTimeMillis() / 1000
        stopLocalTimer()

        if (TimerState.PHASE_WORK == state.phase) {
            state.phase = TimerState.PHASE_SHORT
            state.duration = (prefs.shortBreakDuration * 60).toDouble()
        } else {
            state.phase = TimerState.PHASE_WORK
            state.duration = (prefs.pomodoroDuration * 60).toDouble()
        }

        recalculateNextPhase()

        state.remaining = state.duration
        service.onTimerUpdate(state)
    }

    fun reset() {
        state.status = TimerState.STATUS_STOPPED
        state.duration = getDurationForPhase(state.phase)
        state.remaining = state.duration
        state.last_action_time = System.currentTimeMillis() / 1000
        stopLocalTimer()

        recalculateNextPhase()

        service.onTimerUpdate(state)
    }

    fun extend(minutes: Int) {
        val seconds = (minutes.coerceAtLeast(1) * 60).toDouble()
        state.duration += seconds
        state.remaining += seconds
        state.last_action_time = System.currentTimeMillis() / 1000

        if (TimerState.STATUS_RUNNING == state.status) {
            startLocalTimer()
        }

        service.onTimerUpdate(state)
    }

    private fun recalculateNextPhase() {
        if (TimerState.PHASE_WORK == state.phase) {
            // Current is WORK. Next is a break.
            // If we finish this WORK, completed count will go up by 1.
            val nextCompleted = state.completed + 1
            val longBreakAfter = prefs.longBreakAfter

            if (nextCompleted > 0 && nextCompleted % longBreakAfter == 0) {
                state.next_phase = TimerState.PHASE_LONG
            } else {
                state.next_phase = TimerState.PHASE_SHORT
            }
        } else {
            // Current is BREAK. Next is WORK.
            state.next_phase = TimerState.PHASE_WORK
        }
    }

    private fun getDurationForPhase(phase: String): Double {
        val minutes = when (phase) {
            TimerState.PHASE_WORK -> prefs.pomodoroDuration
            TimerState.PHASE_SHORT -> prefs.shortBreakDuration
            TimerState.PHASE_LONG -> prefs.longBreakDuration
            else -> 25
        }
        return (minutes * 60).toDouble()
    }
}
