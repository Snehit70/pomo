package com.pomoremote.timer

/**
 * Callback surface that OfflineTimer drives. Extracted from PomodoroService so
 * the timer logic can be unit-tested with a fake observer.
 */
public interface TimerObserver {
    public fun onTimerUpdate(state: TimerState)
    public fun onTimerComplete(state: TimerState)
}
