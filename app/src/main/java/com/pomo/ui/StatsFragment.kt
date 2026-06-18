package com.pomo.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.pomo.MainActivity
import com.pomo.db.DayStatsEntity
import com.pomo.db.HistoryCacheRepository
import com.pomo.db.SessionEntity
import com.pomo.stats.StatsAggregator
import com.pomo.stats.StatsSnapshot
import com.pomo.ui.screens.StatsScreen
import com.pomo.ui.theme.PomoTheme
import com.pomo.util.DateLogic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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

        val daysFlow: Flow<List<DayStatsEntity>> = repo.observeDayStats()
        val sessionsFlow: Flow<List<SessionEntity>> = repo.observeAllSessions()
        val todayFlow: Flow<String> = currentDateFlow()

        val snapshotFlow: Flow<StatsSnapshot> =
            combine(daysFlow, sessionsFlow, todayFlow) { days, sessions, today ->
                val goal = mainActivity?.prefs?.dailyGoal ?: 8
                StatsAggregator.aggregate(
                    days = days,
                    sessions = sessions,
                    dailyGoal = goal,
                    today = today,
                    nowMs = System.currentTimeMillis(),
                )
            }

        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PomoTheme(mode = mainActivity?.prefs?.themeMode ?: com.pomo.ui.theme.ThemeMode.System) {
                    val snapshot by snapshotFlow.collectAsState(initial = StatsSnapshot.Empty)
                    val days by daysFlow.collectAsState(initial = emptyList())
                    StatsScreen(
                        snapshot = snapshot,
                        onExport = { exportStats(days) },
                        onShare = { shareStatsScreenshot() },
                    )
                }
            }
        }
    }

    private fun currentDateFlow(): Flow<String> = flow {
        while (true) {
            emit(DateLogic.effectiveDate(System.currentTimeMillis()))
            delay(DATE_REFRESH_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    private fun exportStats(days: List<DayStatsEntity>) {
        val ctx = context ?: return
        if (days.isEmpty()) {
            Toast.makeText(ctx, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val csv = buildString {
                append("Date,WorkMinutes,Completed\n")
                days.sortedByDescending { it.date }.forEach { d ->
                    append("${d.date},${d.workMinutes},${d.completed}\n")
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

    private fun shareStatsScreenshot() {
        val ctx = context ?: return
        val root = activity?.window?.decorView?.rootView
        if (root == null || root.width <= 0 || root.height <= 0) {
            Toast.makeText(ctx, "Stats screen is not ready to share", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            root.draw(canvas)

            val file = File(ctx.cacheDir, "pomo_stats_share.png")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(ctx.contentResolver, "Pomo stats screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Stats"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

}

private const val DATE_REFRESH_INTERVAL_MS: Long = 60_000L
