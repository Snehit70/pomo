package com.pomo.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import com.pomo.MainActivity
import com.pomo.R
import com.pomo.db.HistoryCacheRepository
import com.pomo.tags.TagStore
import com.pomo.timer.TimerState
import com.pomo.ui.screens.TimerScreen
import com.pomo.ui.screens.TimerStats
import com.pomo.ui.theme.PomoTheme
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.ThemeMode
import com.pomo.ui.theme.tagPalette
import com.pomo.ui.theme.tagPaletteLight
import com.pomo.util.DateLogic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

public class TimerFragment : Fragment() {
    private val timerState = MutableStateFlow<TimerState?>(null)
    private val timerStats = MutableStateFlow(TimerStats())
    private val tagUsageCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

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
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val ctx = context
                val tagStore = remember { TagStore(ctx) }
                var showTagPicker by remember { mutableStateOf(false) }
                var defaultTag by remember { mutableStateOf(tagStore.getDefaultTag()) }

                PomoTheme(mode = mainActivity?.prefs?.themeMode ?: ThemeMode.System) {
                    val state by timerState.collectAsState()
                    val stats by timerStats.collectAsState()
                    val goal = mainActivity?.prefs?.dailyGoal ?: 8
                    val effectiveGoal = if ((state?.goal ?: 0) > 0) state!!.goal else goal
                    val workMinutes = mainActivity?.prefs?.pomodoroDuration ?: 25
                    val configuredTags = remember { tagStore.getTags() }
                    val usageCounts by tagUsageCounts.collectAsState()
                    val availableTags =
                        remember(configuredTags, usageCounts) {
                            com.pomo.tags.orderTagsByUsage(configuredTags, usageCounts)
                        }
                    val palette = if (PomoTokens.colors.isDark) tagPalette else tagPaletteLight
                    var tagInitialized by remember { mutableStateOf(false) }

                    // Initialize active tag from default on first non-null state
                    LaunchedEffect(state) {
                        if (!tagInitialized && state != null) {
                            tagInitialized = true
                            if (state?.tag?.isEmpty() == true) {
                                mainActivity?.service?.setActiveTag(defaultTag)
                            }
                        }
                    }

                    val displayTag = state?.tag?.takeIf { it.isNotEmpty() } ?: defaultTag
                    val colorSlots =
                        remember(availableTags, displayTag) {
                            tagStore.getColorSlots(availableTags + displayTag)
                        }
                    val currentTagColor = colorSlots[displayTag]?.let(palette::getOrNull)
                    val phaseBaseDuration =
                        when (state?.phase) {
                            TimerState.PHASE_SHORT -> (mainActivity?.prefs?.shortBreakDuration ?: 5) * 60
                            TimerState.PHASE_LONG -> (mainActivity?.prefs?.longBreakDuration ?: 15) * 60
                            else -> workMinutes * 60
                        }
                    val extensionAvailableSeconds =
                        state?.let {
                            val extension = (it.duration - phaseBaseDuration).coerceAtLeast(0.0)
                            val removable = minOf(extension, (it.remaining - 1.0).coerceAtLeast(0.0))
                            (removable.toInt() / 60) * 60
                        } ?: 0

                    TimerScreen(
                        state = state,
                        stats = stats,
                        dailyGoal = effectiveGoal,
                        fallbackWorkSeconds = workMinutes * 60,
                        onToggle = {
                            Log.i(TAG, "TimerScreen toggle tap delivered to fragment")
                            mainActivity?.toggleTimer()
                                ?: Log.w(TAG, "Toggle tap ignored because MainActivity is unavailable")
                        },
                        onSkip = {
                            Log.i(TAG, "TimerScreen skip tap delivered to fragment")
                            mainActivity?.skipTimer()
                                ?: Log.w(TAG, "Skip tap ignored because MainActivity is unavailable")
                        },
                        onReset = {
                            Log.i(TAG, "TimerScreen reset tap delivered to fragment")
                            mainActivity?.resetTimer()
                                ?: Log.w(TAG, "Reset tap ignored because MainActivity is unavailable")
                        },
                        onAddTime = { secondsDelta ->
                            Log.i(TAG, "TimerScreen add-time tap delivered to fragment: seconds=$secondsDelta")
                            mainActivity?.addTime(secondsDelta)
                                ?: Log.w(TAG, "Add-time tap ignored because MainActivity is unavailable")
                        },
                        onStatsClick = {
                            Log.i(TAG, "TimerScreen stats tap delivered to fragment")
                            runCatching { findNavController().navigate(R.id.navigation_stats) }
                                .onFailure { Log.w(TAG, "Could not navigate to stats", it) }
                        },
                        currentTag = displayTag,
                        currentTagColor = currentTagColor,
                        extensionAvailableSeconds = extensionAvailableSeconds,
                        onTagSelected = { showTagPicker = true },
                        onUndoExtension = {
                            if (extensionAvailableSeconds > 0) mainActivity?.removeAddedTime(extensionAvailableSeconds)
                        },
                        availableTags = availableTags,
                    )

                    if (showTagPicker) {
                        TagPickerSheet(
                            tags = availableTags,
                            currentTag = displayTag,
                            onSelect = { tag ->
                                showTagPicker = false
                                if (tag != null) {
                                    defaultTag = tag
                                    tagStore.setDefaultTag(tag)
                                    mainActivity?.service?.setActiveTag(tag)
                                }
                            },
                            onDismiss = { showTagPicker = false },
                        )
                    }
                }
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            mainActivity?.service?.stateSnapshot()?.let { updateUI(it) }
        }
        observeStats()
        observeTagUsage()
    }

    public fun updateUI(state: TimerState) {
        timerState.value = state.copy()
    }

    private fun observeStats() {
        val ctx = context ?: return
        val repo = HistoryCacheRepository(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeDayStats().combine(currentDateFlow()) { entities, today ->
                val map =
                    entities.associate { e ->
                        e.date to
                            DayEntry(
                                completed = e.completed,
                                work_minutes = e.workMinutes,
                                break_minutes = e.breakMinutes,
                            )
                    }
                val activeDates =
                    map.entries
                        .filter { it.value.completed > 0 }
                        .map { it.key }
                        .toSet()
                val todayEntry = map[today]
                TimerStats(
                    todayMinutes = todayEntry?.work_minutes ?: 0,
                    todaySessions = todayEntry?.completed ?: 0,
                    streak = DateLogic.currentStreak(activeDates, System.currentTimeMillis()),
                )
            }.collectLatest { stats ->
                timerStats.value = stats
            }
        }
    }

    private fun observeTagUsage() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            HistoryCacheRepository(ctx).observeWorkTagUsageCounts().collectLatest { counts ->
                tagUsageCounts.value = counts
            }
        }
    }

    private fun currentDateFlow() =
        flow {
            while (true) {
                emit(DateLogic.effectiveDate(System.currentTimeMillis()))
                delay(DATE_REFRESH_INTERVAL_MS)
            }
        }.distinctUntilChanged()

    private companion object {
        private const val TAG: String = "PomoTimerFragment"
    }
}

private const val DATE_REFRESH_INTERVAL_MS: Long = 60_000L
