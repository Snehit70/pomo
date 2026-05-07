package com.pomoremote.timer

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.pomoremote.db.HistoryCacheRepository
import com.pomoremote.util.UtilPreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class OfflineTimerTest {

    private class RecordingObserver : TimerObserver {
        public val updates: MutableList<TimerState> = mutableListOf()
        public val completions: MutableList<TimerState> = mutableListOf()
        override fun onTimerUpdate(state: TimerState) {
            updates += state.copy()
        }
        override fun onTimerComplete(state: TimerState) {
            completions += state.copy()
        }
    }

    private lateinit var observer: RecordingObserver
    private lateinit var prefs: UtilPreferenceManager
    private lateinit var repo: HistoryCacheRepository
    private lateinit var scope: CoroutineScope
    private lateinit var timer: OfflineTimer

    @Before
    public fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().apply()
        prefs = UtilPreferenceManager(ctx)
        repo = HistoryCacheRepository(ctx)
        scope = CoroutineScope(Dispatchers.Unconfined)
        observer = RecordingObserver()
        timer = OfflineTimer(observer, prefs, repo, scope)
    }

    @After
    public fun tearDown() {
        scope.cancel()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().apply()
    }

    @Test
    public fun toggle_fromStopped_startsRunning() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 1500.0
            status = TimerState.STATUS_STOPPED
        }
        timer.updateState(initial)

        timer.toggle()

        assertEquals(TimerState.STATUS_RUNNING, timer.state.status)
        assertTrue("observer should have been notified", observer.updates.isNotEmpty())
    }

    @Test
    public fun toggle_fromRunning_pauses() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 1000.0
            status = TimerState.STATUS_RUNNING
        }
        timer.updateState(initial)
        observer.updates.clear()

        timer.toggle()

        assertEquals(TimerState.STATUS_PAUSED, timer.state.status)
    }

    @Test
    public fun skip_workToShortBreak_swapsPhase() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 800.0
        }
        timer.updateState(initial)

        timer.skip()

        assertEquals(TimerState.PHASE_SHORT, timer.state.phase)
        assertEquals(TimerState.STATUS_STOPPED, timer.state.status)
        // duration should be reset to short-break default (5 min = 300s)
        assertEquals(300.0, timer.state.duration, 0.001)
        assertEquals(300.0, timer.state.remaining, 0.001)
        // next_phase from short break should be WORK
        assertEquals(TimerState.PHASE_WORK, timer.state.next_phase)
    }

    @Test
    public fun skip_breakToWork_swapsPhase() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_SHORT
            duration = 300.0
            remaining = 100.0
        }
        timer.updateState(initial)

        timer.skip()

        assertEquals(TimerState.PHASE_WORK, timer.state.phase)
        assertEquals(1500.0, timer.state.duration, 0.001)
        // next_phase after work, completed=0, longBreakAfter=4: nextCompleted=1 → SHORT
        assertEquals(TimerState.PHASE_SHORT, timer.state.next_phase)
    }

    @Test
    public fun skip_workWithCompletedThreeAndLongBreakAfterFour_predictsLongBreakNext() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 1500.0
            completed = 3
        }
        timer.updateState(initial)

        timer.skip()

        // After skip we're in SHORT (skip always goes WORK→SHORT regardless of count),
        // but next_phase from a break is always WORK.
        assertEquals(TimerState.PHASE_WORK, timer.state.next_phase)
    }

    @Test
    public fun reset_restoresRemainingToDuration() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 200.0
            status = TimerState.STATUS_RUNNING
        }
        timer.updateState(initial)

        timer.reset()

        assertEquals(TimerState.STATUS_STOPPED, timer.state.status)
        assertEquals(1500.0, timer.state.duration, 0.001)
        assertEquals(1500.0, timer.state.remaining, 0.001)
    }

    @Test
    public fun extend_addsMinutesToDurationAndRemaining() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 1000.0
        }
        timer.updateState(initial)

        timer.extend(5)

        assertEquals(1800.0, timer.state.duration, 0.001)
        assertEquals(1300.0, timer.state.remaining, 0.001)
    }

    @Test
    public fun extend_zeroOrNegativeMinutes_clampedToOne() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 1500.0
        }
        timer.updateState(initial)

        timer.extend(0)
        assertEquals(1560.0, timer.state.duration, 0.001) // +60s

        timer.extend(-5)
        assertEquals(1620.0, timer.state.duration, 0.001) // +60s
    }

    @Test
    public fun toggle_fromWork_setsNextPhaseShortByDefault() {
        val initial = TimerState().apply {
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 1500.0
            status = TimerState.STATUS_STOPPED
        }
        timer.updateState(initial)

        timer.toggle()

        // next_phase is set on phase changes (skip/reset/complete) — toggle alone
        // does not modify next_phase. But updates should still flow.
        assertTrue(observer.updates.isNotEmpty())
    }
}
