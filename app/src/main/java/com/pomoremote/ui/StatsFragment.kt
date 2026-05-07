package com.pomoremote.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialFadeThrough
import com.pomoremote.MainActivity
import com.pomoremote.db.HistoryCacheRepository
import com.pomoremote.db.SessionEntity
import com.pomoremote.ui.screens.StatsScreen
import com.pomoremote.ui.theme.PomoRemoteTheme
import com.pomoremote.util.DateLogic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

public class StatsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val repo = HistoryCacheRepository(ctx)
        val historyFlow: Flow<Map<String, DayEntry>> = repo.observeDayStats().map { entities ->
            entities.associate {
                it.date to DayEntry(
                    completed = it.completed,
                    work_minutes = it.workMinutes,
                    break_minutes = it.breakMinutes,
                )
            }
        }
        val today = DateLogic.effectiveDate(System.currentTimeMillis(), mainActivity?.prefs?.dayStartHour ?: 3)
        val sessionsFlow: Flow<List<SessionEntity>> = repo.observeSessionsForDate(today)

        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PomoRemoteTheme {
                    val history by historyFlow.collectAsState(initial = emptyMap())
                    val sessions by sessionsFlow.collectAsState(initial = emptyList())
                    val act = mainActivity
                    val goal = act?.service?.currentState?.goal?.takeIf { it > 0 }
                        ?: act?.prefs?.dailyGoal ?: 8
                    val dayStart = act?.prefs?.dayStartHour ?: 3
                    val sessionMins = act?.prefs?.pomodoroDuration ?: 25
                    StatsScreen(
                        history = history,
                        todaySessions = sessions,
                        dailyGoal = goal,
                        dayStartHour = dayStart,
                        sessionMinutes = sessionMins,
                        onExport = { exportStats(history) },
                    )
                }
            }
        }
    }

    private fun exportStats(history: Map<String, DayEntry>) {
        val ctx = context ?: return
        if (history.isEmpty()) {
            Toast.makeText(ctx, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val csv = buildString {
                append("Date,WorkMinutes,Completed\n")
                history.entries.sortedByDescending { it.key }.forEach { (date, entry) ->
                    append("$date,${entry.work_minutes},${entry.completed}\n")
                }
            }
            val file = File(ctx.cacheDir, "pomo_stats.csv").apply { writeText(csv) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export Stats CSV"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

}
