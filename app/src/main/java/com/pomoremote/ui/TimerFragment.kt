package com.pomoremote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialFadeThrough
import com.pomoremote.MainActivity
import com.pomoremote.db.HistoryCacheRepository
import com.pomoremote.timer.TimerState
import com.pomoremote.ui.screens.TimerScreen
import com.pomoremote.ui.screens.TimerStats
import com.pomoremote.ui.theme.PomoRemoteTheme
import com.pomoremote.util.DateLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

public class TimerFragment : Fragment() {

    private val timerState = MutableStateFlow<TimerState?>(null)
    private val timerStats = MutableStateFlow(TimerStats())

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PomoRemoteTheme {
                val state by timerState.collectAsState()
                val stats by timerStats.collectAsState()
                val goal = mainActivity?.prefs?.dailyGoal ?: 8
                val effectiveGoal = if ((state?.goal ?: 0) > 0) state!!.goal else goal
                val workMinutes = mainActivity?.prefs?.pomodoroDuration ?: 25
                TimerScreen(
                    state = state,
                    stats = stats,
                    dailyGoal = effectiveGoal,
                    fallbackWorkSeconds = workMinutes * 60,
                    onToggle = { mainActivity?.toggleTimer() },
                    onSkip = { mainActivity?.skipTimer() },
                    onReset = { mainActivity?.resetTimer() },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainActivity?.service?.currentState?.let { updateUI(it) }
        observeStats()
    }

    public fun updateUI(state: TimerState) {
        timerState.value = state.copy()
    }

    private fun observeStats() {
        val ctx = context ?: return
        val repo = HistoryCacheRepository(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeDayStats().collectLatest { entities ->
                val map = entities.associate { e ->
                    e.date to DayEntry(
                        completed = e.completed,
                        work_minutes = e.workMinutes,
                        break_minutes = e.breakMinutes,
                    )
                }
                val dayStartHour = mainActivity?.prefs?.dayStartHour ?: 3
                val today = DateLogic.effectiveDate(System.currentTimeMillis(), dayStartHour)
                val todayEntry = map[today]
                val activeDates = map.entries.filter { it.value.completed > 0 }.map { it.key }.toSet()
                timerStats.value = TimerStats(
                    todayMinutes = todayEntry?.work_minutes ?: 0,
                    todaySessions = todayEntry?.completed ?: 0,
                    streak = DateLogic.currentStreak(activeDates, System.currentTimeMillis(), dayStartHour),
                )
            }
        }
    }

}
