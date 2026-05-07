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
import com.pomoremote.db.DayStatsEntity
import com.pomoremote.db.HistoryCacheRepository
import com.pomoremote.ui.screens.HistoryScreen
import com.pomoremote.ui.theme.PomoRemoteTheme
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
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PomoRemoteTheme {
                    val items by itemsFlow.collectAsState(initial = emptyList())
                    HistoryScreen(items)
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
