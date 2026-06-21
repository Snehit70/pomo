package com.pomo.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.preference.PreferenceManager
import androidx.fragment.app.Fragment
import com.pomo.db.DayStatsEntity
import com.pomo.db.HistoryCacheRepository
import com.pomo.stats.HourRhythm
import com.pomo.stats.StatsAggregator
import com.pomo.ui.screens.HistoryScreen
import com.pomo.ui.theme.PomoTheme
import com.pomo.ui.theme.themeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

public class HistoryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val repo = HistoryCacheRepository(requireContext())
        val itemsFlow: Flow<List<HistoryItem>> = repo.observeDayStats()
            .map { entities -> entities.toHistoryItems() }
        val loadRhythm: suspend (String) -> HourRhythm = { date ->
            StatsAggregator.hourRhythmForDay(repo.getSessionsForDate(date))
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PomoTheme(mode = PreferenceManager.getDefaultSharedPreferences(requireContext()).themeMode()) {
                    val items by itemsFlow.collectAsState(initial = emptyList())
                    HistoryScreen(items, loadRhythm = loadRhythm)
                }
            }
        }
    }
}

public data class DayEntry(
    val completed: Int,
    val work_minutes: Int,
    val break_minutes: Int,
)

public data class HistoryItem(val date: String, val entry: DayEntry)

internal fun List<DayStatsEntity>.toHistoryItems(): List<HistoryItem> =
    map { e ->
        HistoryItem(
            date = e.date,
            entry = DayEntry(
                completed = e.completed,
                work_minutes = e.workMinutes,
                break_minutes = e.breakMinutes,
            ),
        )
    }.sortedByDescending { it.date }
