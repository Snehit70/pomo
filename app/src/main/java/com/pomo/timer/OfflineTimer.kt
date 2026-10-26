package com.pomo.timer

import android.os.CountDownTimer
import com.pomo.db.HistoryCacheRepository
import com.pomo.models.Session
import com.pomo.util.UtilPreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

public class OfflineTimer(
    private val observer: TimerObserver,
    private val prefs: UtilPreferenceManager,
    private val historyRepository: HistoryCacheRepository,
    private val scope: CoroutineScope,
) {
    private var timer: CountDownTimer? = null
    private var completionJob: Job? = null
    public var state: TimerState = TimerState()
        private set

    public fun updateState(newState: TimerState) {
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

        timer =
            object : CountDownTimer(remainingMillis, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    state.remaining = millisUntilFinished / 1000.0
                    observer.onTimerUpdate(state)
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

    public fun completeExpiredTimer(): Job {
        stopLocalTimer()
        return handleTimerComplete()
    }

    private fun handleTimerComplete(): Job {
        completionJob?.takeIf { it.isActive }?.let { return it }

        val completionState = state.copy()
        val now = System.currentTimeMillis() / 1000
        val sessionStart =
            completionState.start_time
                .takeIf { it > 0 }
                ?.toLong()
                ?: now - completionState.duration.toLong()
        // Derive the completion date from the session's actual end time (start + duration)
        // so that process-death restores and midnight-crossing coroutines both use the
        // correct calendar day rather than the time the app reopened.
        val sessionEnd = sessionStart + completionState.duration.toLong()
        val completionDate = historyRepository.dateStringForEpochSecond(sessionEnd)
        val session =
            Session(
                type = completionState.phase,
                start = sessionStart,
                duration = completionState.duration.toInt(),
                completed = true,
                tag = completionState.tag.ifEmpty { null },
            )

        val job =
            scope.launch {
                historyRepository.saveLocalSession(session)

                if (!isStillCompleting(completionState)) {
                    return@launch
                }

                state.remaining = 0.0
                state.status = TimerState.STATUS_STOPPED

                if (TimerState.PHASE_WORK == completionState.phase) {
                    state.completed = historyRepository.getCompletedCountForDate(completionDate)
                    state.date = completionDate
                    val longBreakAfter = prefs.longBreakAfter

                    if (state.completed > 0 && state.completed % longBreakAfter == 0) {
                        state.phase = TimerState.PHASE_LONG
                        state.duration = (prefs.longBreakDuration * 60).toDouble()
                    } else {
                        state.phase = TimerState.PHASE_SHORT
                        state.duration = (prefs.shortBreakDuration * 60).toDouble()
                    }
                } else {
                    state.phase = TimerState.PHASE_WORK
                    state.duration = (prefs.pomodoroDuration * 60).toDouble()
                }

                recalculateNextPhase()

                state.remaining = state.duration
                observer.onTimerComplete(state, completionState.phase)
            }
        completionJob = job
        job.invokeOnCompletion {
            if (completionJob === job) {
                completionJob = null
            }
        }
        return job
    }

    private fun isStillCompleting(completionState: TimerState): Boolean {
        return state.status == TimerState.STATUS_RUNNING &&
            state.phase == completionState.phase &&
            state.start_time == completionState.start_time &&
            state.duration == completionState.duration
    }

    public fun toggle() {
        if (completionJob?.isActive == true) return
        if (TimerState.STATUS_RUNNING == state.status) {
            state.status = TimerState.STATUS_PAUSED
            state.last_action_time = System.currentTimeMillis() / 1000
            stopLocalTimer()
            observer.onTimerUpdate(state)
        } else {
            state.status = TimerState.STATUS_RUNNING
            state.last_action_time = System.currentTimeMillis() / 1000
            if (state.remaining <= 0) {
                state.remaining = getDurationForPhase(state.phase)
                state.duration = state.remaining
            }
            state.start_time = (System.currentTimeMillis() / 1000).toDouble() - (state.duration - state.remaining)
            startLocalTimer()
            observer.onTimerUpdate(state)
        }
    }

    public fun skip() {
        if (completionJob?.isActive == true) return

        // Skip on a work phase credits the partial focus time as Focus minutes (but no
        // earned block); Reset, by contrast, discards. Capture before mutating state.
        if (TimerState.PHASE_WORK == state.phase) {
            recordPartialWorkBlock()
        }

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
        observer.onTimerUpdate(state)
    }

    public fun reset() {
        if (completionJob?.isActive == true) return
        state.status = TimerState.STATUS_STOPPED
        state.duration = getDurationForPhase(state.phase)
        state.remaining = state.duration
        state.last_action_time = System.currentTimeMillis() / 1000
        stopLocalTimer()

        recalculateNextPhase()

        observer.onTimerUpdate(state)
    }

    public fun extend(secondsDelta: Int) {
        if (completionJob?.isActive == true) return
        if (TimerState.STATUS_RUNNING != state.status) return
        val seconds = secondsDelta.coerceAtLeast(1).toDouble()
        state.duration += seconds
        state.remaining += seconds
        state.last_action_time = System.currentTimeMillis() / 1000

        startLocalTimer()

        observer.onTimerUpdate(state)
    }

    public fun removeAddedTime(secondsToRemove: Int) {
        if (completionJob?.isActive == true) return
        if (state.status != TimerState.STATUS_RUNNING && state.status != TimerState.STATUS_PAUSED) return

        val baseDuration = getDurationForPhase(state.phase)
        val extension = (state.duration - baseDuration).coerceAtLeast(0.0)
        val requested = secondsToRemove.coerceAtLeast(0).toDouble()
        val removable = minOf(requested, extension, (state.remaining - 1.0).coerceAtLeast(0.0))
        if (removable <= 0.0) return

        state.duration -= removable
        state.remaining -= removable
        state.last_action_time = System.currentTimeMillis() / 1000

        if (state.status == TimerState.STATUS_RUNNING) startLocalTimer()
        observer.onTimerUpdate(state)
    }

    private fun recalculateNextPhase() {
        if (TimerState.PHASE_WORK == state.phase) {
            val nextCompleted = state.completed + 1
            val longBreakAfter = prefs.longBreakAfter

            if (nextCompleted > 0 && nextCompleted % longBreakAfter == 0) {
                state.next_phase = TimerState.PHASE_LONG
            } else {
                state.next_phase = TimerState.PHASE_SHORT
            }
        } else {
            state.next_phase = TimerState.PHASE_WORK
        }
    }

    private fun getDurationForPhase(phase: String): Double {
        val minutes =
            when (phase) {
                TimerState.PHASE_WORK -> prefs.pomodoroDuration
                TimerState.PHASE_SHORT -> prefs.shortBreakDuration
                TimerState.PHASE_LONG -> prefs.longBreakDuration
                else -> 25
            }
        return (minutes * 60).toDouble()
    }

    /** Record the elapsed focus time of a work block ended early by Skip. Below the
     *  60s floor it is a non-event. The repository rounds seconds up to whole minutes
     *  and splits across calendar days; `completed = false` keeps it out of the block
     *  count while its minutes still count toward Focus minutes (ADR-0002). */
    private fun recordPartialWorkBlock() {
        val elapsedSeconds = (state.duration - state.remaining).toLong()
        if (elapsedSeconds < MIN_PARTIAL_BLOCK_SECONDS) return

        val now = System.currentTimeMillis() / 1000
        val sessionStart = state.start_time.takeIf { it > 0 }?.toLong() ?: (now - elapsedSeconds)
        val session =
            Session(
                type = TimerState.PHASE_WORK,
                start = sessionStart,
                duration = elapsedSeconds.toInt(),
                completed = false,
                tag = state.tag.ifEmpty { null },
            )
        scope.launch {
            historyRepository.saveLocalSession(session)
            observer.onPartialWorkBlockRecorded()
        }
    }

    private companion object {
        const val MIN_PARTIAL_BLOCK_SECONDS: Long = 60
    }
}
