package com.pomoremote.ui

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.widget.Toast
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pomoremote.MainActivity
import com.pomoremote.R
import com.pomoremote.db.DayStatsEntity
import com.pomoremote.db.HistoryCacheRepository
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StatsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    private lateinit var tvTotalFocus: TextView
    private lateinit var tvTotalSessions: TextView
    private lateinit var tvCurrentStreak: TextView
    private lateinit var tvBestStreak: TextView
    private lateinit var tvDailyAvg: TextView
    private lateinit var weekGrid: LinearLayout
    private lateinit var barGraph: LinearLayout
    private lateinit var barGraphCard: MaterialCardView
    private lateinit var lineGraphCard: MaterialCardView
    private lateinit var lineGraphContainer: FrameLayout
    private lateinit var graphToggleGroup: MaterialButtonToggleGroup
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnToggleBreakdown: MaterialButton
    private lateinit var btnSortLog: MaterialButton
    private lateinit var tvTodayProgress: TextView
    private lateinit var progressToday: LinearProgressIndicator
    private lateinit var periodToggleGroup: MaterialButtonToggleGroup
    private lateinit var tvGraphTitle: TextView
    private lateinit var tvTodayLogTitle: TextView
    private lateinit var heatmapView: HeatmapView
    private lateinit var btnExport: MaterialButton
    private var isBreakdownVisible = false
    private var isMonthlyView = false
    private var isSortAscending = false
    private var historyData: Map<String, DayEntry> = emptyMap()
    private var currentTodaySessions: List<com.pomoremote.db.SessionEntity> = emptyList()
    private var lineGraphView: LineGraphView? = null
    private var weekData: List<Pair<String, Int>> = emptyList()

    private var historyCacheRepository: HistoryCacheRepository? = null
    // Removed custom scope in favor of lifecycleScope

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    private val currentGoal: Int
        get() {
            val serviceGoal = mainActivity?.service?.currentState?.goal ?: 0
            return if (serviceGoal > 0) serviceGoal else (mainActivity?.prefs?.dailyGoal ?: 8)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTotalFocus = view.findViewById(R.id.tvTotalFocus)
        tvTotalSessions = view.findViewById(R.id.tvTotalSessions)
        tvCurrentStreak = view.findViewById(R.id.tvCurrentStreak)
        tvBestStreak = view.findViewById(R.id.tvBestStreak)
        tvDailyAvg = view.findViewById(R.id.tvDailyAvg)
        weekGrid = view.findViewById(R.id.weekGrid)
        barGraph = view.findViewById(R.id.barGraph)
        barGraphCard = view.findViewById(R.id.barGraphCard)
        lineGraphCard = view.findViewById(R.id.lineGraphCard)
        lineGraphContainer = view.findViewById(R.id.lineGraphContainer)
        graphToggleGroup = view.findViewById(R.id.graphToggleGroup)
        recyclerView = view.findViewById(R.id.statsRecyclerView)
        btnToggleBreakdown = view.findViewById(R.id.btnToggleBreakdown)
        btnSortLog = view.findViewById(R.id.btnSortLog)
        tvTodayProgress = view.findViewById(R.id.tvTodayProgress)
        progressToday = view.findViewById(R.id.progressToday)
        periodToggleGroup = view.findViewById(R.id.periodToggleGroup)
        tvGraphTitle = view.findViewById(R.id.tvGraphTitle)
        tvTodayLogTitle = view.findViewById(R.id.tvTodayLogTitle)
        heatmapView = view.findViewById(R.id.heatmapView)
        btnExport = view.findViewById(R.id.btnExport)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.isNestedScrollingEnabled = false
        recyclerView.visibility = View.GONE
        btnToggleBreakdown.text = "Show"

        // Set default selection to bar graph and weekly view
        graphToggleGroup.check(R.id.btnBarGraph)
        periodToggleGroup.check(R.id.btnWeek)

        graphToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnBarGraph -> {
                        barGraphCard.visibility = View.VISIBLE
                        lineGraphCard.visibility = View.GONE
                    }
                    R.id.btnLineGraph -> {
                        barGraphCard.visibility = View.GONE
                        lineGraphCard.visibility = View.VISIBLE
                        updateLineGraph()
                    }
                }
            }
        }

        periodToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isMonthlyView = checkedId == R.id.btnMonth
                tvGraphTitle.text = if (isMonthlyView) "Monthly Hours" else "Weekly Hours"
                if (historyData.isNotEmpty()) {
                    val dayStartHour = mainActivity?.prefs?.dayStartHour ?: 3
                    populateBarGraph(historyData, dayStartHour)
                    lineGraphView = null
                    lineGraphContainer.removeAllViews()
                    if (graphToggleGroup.checkedButtonId == R.id.btnLineGraph) {
                        updateLineGraph()
                    }
                }
            }
        }

        btnToggleBreakdown.setOnClickListener {
            isBreakdownVisible = !isBreakdownVisible
            btnToggleBreakdown.text = if (isBreakdownVisible) "Hide" else "Show"
            animateBreakdown(isBreakdownVisible)
        }

        btnSortLog.setOnClickListener {
            isSortAscending = !isSortAscending
            // Update icon rotation to indicate direction (optional, but nice touch)
            btnSortLog.animate().rotation(if (isSortAscending) 180f else 0f).setDuration(200).start()
            updateTodayLogSort()
        }

        btnExport.setOnClickListener {
            exportStats()
        }

        // Initialize repository
        context?.let {
            historyCacheRepository = HistoryCacheRepository(it)
        }

        observeData()
    }

    private fun getLogicalToday(): String {
        val dayStartHour = mainActivity?.prefs?.dayStartHour ?: 3
        val calendar = Calendar.getInstance()
        if (calendar.get(Calendar.HOUR_OF_DAY) < dayStartHour) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return dateFormat.format(calendar.time)
    }

    private fun observeData() {
        val repo = historyCacheRepository ?: return
        val todayStr = getLogicalToday()

        // 1. Observe all day stats (for graphs, totals, streaks)
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeDayStats().collectLatest { entities ->
                val historyMap = convertToHistoryMap(entities)
                populateStats(historyMap)
            }
        }

        // 2. Observe today's sessions (for the log)
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeSessionsForDate(todayStr).collectLatest { sessions ->
                populateTodayLog(sessions)
            }
        }
    }

    /**
     * Convert Room entities back to the Map format the UI expects.
     */
    private fun convertToHistoryMap(entities: List<DayStatsEntity>): Map<String, DayEntry> {
        return entities.associate { entity ->
            entity.date to DayEntry(
                completed = entity.completed,
                work_minutes = entity.workMinutes,
                break_minutes = entity.breakMinutes
            )
        }
    }

    private fun showEmptyState() {
        tvTotalFocus.text = "0m"
        tvTotalSessions.text = "0"
        tvCurrentStreak.text = "0"
        tvBestStreak.text = "0"
        tvDailyAvg.text = "0m"
        tvTodayProgress.text = "Today: 0/${currentGoal}"
        progressToday.setProgressCompat(0, false)
    }

    private fun exportStats() {
        val ctx = context ?: return
        if (historyData.isEmpty()) {
            Toast.makeText(ctx, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Create CSV content
            val csvBuilder = StringBuilder()
            csvBuilder.append("Date,Minutes,Sessions\n")

            val sortedEntries = historyData.entries
                .sortedByDescending { it.key } // Newest first

            for ((date, entry) in sortedEntries) {
                csvBuilder.append("$date,${entry.work_minutes},${entry.completed}\n")
            }

            // Write to file in cache
            val fileName = "pomo_stats.csv"
            val file = File(ctx.cacheDir, fileName)
            file.writeText(csvBuilder.toString())

            // Share file
            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Export Stats CSV"))

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun animateBreakdown(expand: Boolean) {
        if (expand) {
            recyclerView.visibility = View.VISIBLE
            recyclerView.measure(
                View.MeasureSpec.makeMeasureSpec(recyclerView.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = recyclerView.measuredHeight
            recyclerView.layoutParams.height = 0
            recyclerView.alpha = 0f

            val heightAnimator = ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    recyclerView.layoutParams.height = animator.animatedValue as Int
                    recyclerView.requestLayout()
                }
            }
            heightAnimator.start()
            recyclerView.animate().alpha(1f).setDuration(200).start()
        } else {
            val initialHeight = recyclerView.height
            val heightAnimator = ValueAnimator.ofInt(initialHeight, 0).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    recyclerView.layoutParams.height = animator.animatedValue as Int
                    recyclerView.requestLayout()
                    if (animator.animatedValue == 0) {
                        recyclerView.visibility = View.GONE
                        recyclerView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            recyclerView.animate().alpha(0f).setDuration(150).start()
            heightAnimator.start()
        }
    }

    private fun populateStats(history: Map<String, DayEntry>) {
        historyData = history

        // Total calculations
        var totalMinutes = 0
        var totalSessions = 0
        history.values.forEach {
            totalMinutes += it.work_minutes
            totalSessions += it.completed
        }

        // Format total focus
        val totalHours = totalMinutes / 60
        val totalMins = totalMinutes % 60
        tvTotalFocus.text = if (totalHours > 0) "${totalHours}h ${totalMins}m" else "${totalMins}m"
        tvTotalSessions.text = "$totalSessions"

        // Today's goal progress
        val dailyGoal = currentGoal
        val dayStartHour = mainActivity?.prefs?.dayStartHour ?: 3
        val calendar = Calendar.getInstance()
        if (calendar.get(Calendar.HOUR_OF_DAY) < dayStartHour) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = dateFormat.format(calendar.time)
        val todayEntry = history[todayStr]
        val todaySessions = todayEntry?.completed ?: 0
        tvTodayProgress.text = "Today: $todaySessions/$dailyGoal"
        val progressPercent = ((todaySessions.toFloat() / dailyGoal) * 100).coerceAtMost(100f).toInt()
        progressToday.setProgressCompat(progressPercent, true)

        // Daily average
        val daysWithActivity = history.values.count { it.completed > 0 }
        val avgMinutes = if (daysWithActivity > 0) totalMinutes / daysWithActivity else 0
        val avgHours = avgMinutes / 60
        val avgMins = avgMinutes % 60
        tvDailyAvg.text = if (avgHours > 0) "${avgHours}h ${avgMins}m" else "${avgMins}m"

        // Best streak
        val bestStreak = calculateBestStreak(history)
        tvBestStreak.text = "$bestStreak"

        // Current streak
        val currentStreak = calculateCurrentStreak(history, dayStartHour)
        tvCurrentStreak.text = "$currentStreak"

        // Week grid
        populateWeekGrid(history, dayStartHour)

        // Heatmap
        heatmapView.setData(history, dayStartHour)

        // Bar graph
        populateBarGraph(history, dayStartHour)
    }

    private fun populateTodayLog(sessions: List<com.pomoremote.db.SessionEntity>) {
        if (sessions.isEmpty()) {
            recyclerView.visibility = View.GONE
            btnToggleBreakdown.visibility = View.GONE
            btnSortLog.visibility = View.GONE
            tvTodayLogTitle.text = "Today's Log (0)"
            return
        }

        currentTodaySessions = sessions
        tvTodayLogTitle.text = "Today's Log (${sessions.size})"
        btnToggleBreakdown.visibility = View.VISIBLE
        btnSortLog.visibility = View.VISIBLE
        updateTodayLogSort()
    }

    private fun updateTodayLogSort() {
        if (currentTodaySessions.isEmpty()) return

        val sortedSessions = if (isSortAscending) {
            currentTodaySessions.sortedBy { it.start }
        } else {
            currentTodaySessions.sortedByDescending { it.start }
        }
        recyclerView.adapter = TodaySessionsAdapter(sortedSessions)
    }

    private fun calculateBestStreak(history: Map<String, DayEntry>): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sortedDates = history.keys
            .filter { (history[it]?.completed ?: 0) > 0 }
            .map { dateFormat.parse(it) }
            .filterNotNull()
            .sortedDescending()

        if (sortedDates.isEmpty()) return 0

        var bestStreak = 1
        var currentStreak = 1

        for (i in 0 until sortedDates.size - 1) {
            val diff = (sortedDates[i].time - sortedDates[i + 1].time) / (1000 * 60 * 60 * 24)
            if (diff == 1L) {
                currentStreak++
                bestStreak = maxOf(bestStreak, currentStreak)
            } else {
                currentStreak = 1
            }
        }
        return bestStreak
    }

    private fun calculateCurrentStreak(history: Map<String, DayEntry>, dayStartHour: Int): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = Calendar.getInstance()

        // Adjust for logical day
        if (calendar.get(Calendar.HOUR_OF_DAY) < dayStartHour) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        var streak = 0

        // Check today first
        val todayStr = dateFormat.format(calendar.time)
        val todayEntry = history[todayStr]
        val hasActivityToday = (todayEntry?.completed ?: 0) > 0

        // If no activity today, start checking from yesterday
        if (!hasActivityToday) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        // Count consecutive days with activity
        while (true) {
            val dateStr = dateFormat.format(calendar.time)
            val entry = history[dateStr]
            if ((entry?.completed ?: 0) > 0) {
                streak++
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }

        return streak
    }

    private fun populateWeekGrid(history: Map<String, DayEntry>, dayStartHour: Int) {
        val ctx = context ?: return
        weekGrid.removeAllViews()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayFormat = SimpleDateFormat("EEE", Locale.US)
        val calendar = Calendar.getInstance()

        // Adjust for logical day
        if (calendar.get(Calendar.HOUR_OF_DAY) < dayStartHour) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        // Start from 6 days ago relative to logical today
        calendar.add(Calendar.DAY_OF_YEAR, -6)

        val primaryColor = ContextCompat.getColor(ctx, R.color.md_theme_primary)
        val goldColor = ContextCompat.getColor(ctx, R.color.gold)
        val surfaceColor = ContextCompat.getColor(ctx, R.color.md_theme_surfaceVariant)

        // Get daily goal from preferences
        val dailyGoal = currentGoal

        for (i in 0 until 7) {
            val dateStr = dateFormat.format(calendar.time)
            val dayLabel = dayFormat.format(calendar.time)
            val entry = history[dateStr]
            val sessionsCompleted = entry?.completed ?: 0
            val hasActivity = sessionsCompleted > 0
            val goalMet = sessionsCompleted >= dailyGoal

            val dayView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Day label
            val label = TextView(ctx).apply {
                text = dayLabel.uppercase().take(1)
                setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSurface))
                textSize = 10f
                gravity = Gravity.CENTER
                alpha = 0.6f
            }

            // Activity dot - gold if goal met, primary if active, surface if inactive
            val dotColor = when {
                goalMet -> goldColor
                hasActivity -> primaryColor
                else -> surfaceColor
            }
            val dot = View(ctx).apply {
                val size = (24 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                    if (goalMet) {
                        setStroke((2 * resources.displayMetrics.density).toInt(), goldColor)
                    }
                }
            }

            // Minutes label (if any)
            val minutesLabel = TextView(ctx).apply {
                text = if (hasActivity) "${entry?.work_minutes}m" else ""
                setTextColor(if (goalMet) goldColor else primaryColor)
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                }
            }

            dayView.addView(label)
            dayView.addView(dot)
            dayView.addView(minutesLabel)
            weekGrid.addView(dayView)

            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun populateBarGraph(history: Map<String, DayEntry>, dayStartHour: Int) {
        val ctx = context ?: return
        barGraph.removeAllViews()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayFormat = SimpleDateFormat("EEE", Locale.US)
        val dayOfMonthFormat = SimpleDateFormat("d", Locale.US)
        val calendar = Calendar.getInstance()

        // Adjust for logical day
        if (calendar.get(Calendar.HOUR_OF_DAY) < dayStartHour) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        // Determine period: 7 days for week, 30 days for month
        val daysToShow = if (isMonthlyView) 30 else 7
        calendar.add(Calendar.DAY_OF_YEAR, -(daysToShow - 1))

        val primaryColor = ContextCompat.getColor(ctx, R.color.md_theme_primary)
        val goldColor = ContextCompat.getColor(ctx, R.color.gold)

        // Get daily goal and max height
        val dailyGoal = currentGoal
        val maxBarHeight = 80 * resources.displayMetrics.density

        // Collect data for the period
        weekData = mutableListOf<Pair<String, Int>>().apply {
            for (i in 0 until daysToShow) {
                val dateStr = dateFormat.format(calendar.time)
                val label = if (isMonthlyView) {
                    dayOfMonthFormat.format(calendar.time)
                } else {
                    dayFormat.format(calendar.time).take(1).uppercase()
                }
                val entry = history[dateStr]
                val minutes = entry?.work_minutes ?: 0
                add(Pair(label, minutes))
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // Find max for scaling
        val maxMinutes = weekData.maxOfOrNull { it.second } ?: 60
        val scaleFactor = if (maxMinutes > 0) maxBarHeight / maxMinutes else 1f

        for ((index, pair) in weekData.withIndex()) {
            val (dayLabel, minutes) = pair
            val barContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            // Hours label on top (hide for monthly to save space)
            val hoursLabel = TextView(ctx).apply {
                val hours = minutes / 60f
                text = if (!isMonthlyView) {
                    if (minutes >= 60) String.format(Locale.US, "%.1fh", hours) else "${minutes}m"
                } else ""
                setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSurface))
                textSize = 9f
                gravity = Gravity.CENTER
                alpha = 0.7f
            }

            // The bar itself
            val barHeight = (minutes * scaleFactor).toInt().coerceAtLeast(if (minutes > 0) 4 else 2)
            val goalMinutes = dailyGoal * 25 // Assume 25 min sessions
            val barColor = if (minutes >= goalMinutes) goldColor else primaryColor
            val barWidth = if (isMonthlyView) 6 else 16

            val bar = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (barWidth * resources.displayMetrics.density).toInt(),
                    barHeight
                ).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 8 * resources.displayMetrics.density
                    setColor(barColor)
                }
            }

            // Day label below (show every 5th day for monthly, all for weekly)
            val showLabel = if (isMonthlyView) (index % 5 == 0 || index == weekData.size - 1) else true
            val dayLabelView = TextView(ctx).apply {
                text = if (showLabel) dayLabel else ""
                setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSurface))
                textSize = if (isMonthlyView) 8f else 10f
                gravity = Gravity.CENTER
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                }
            }

            barContainer.addView(hoursLabel)
            barContainer.addView(bar)
            barContainer.addView(dayLabelView)
            barGraph.addView(barContainer)
        }
    }

    private fun updateLineGraph() {
        val ctx = context ?: return
        if (weekData.isEmpty()) return

        if (lineGraphView == null) {
            lineGraphView = LineGraphView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            lineGraphContainer.addView(lineGraphView)
        }

        val dailyGoal = currentGoal
        lineGraphView?.setData(weekData, dailyGoal)
    }
}

// Adapter for today's sessions
class TodaySessionsAdapter(private val list: List<com.pomoremote.db.SessionEntity>) : RecyclerView.Adapter<TodaySessionsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimeRange: TextView = view.findViewById(R.id.tvTimeRange)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val tvType: TextView = view.findViewById(R.id.tvType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = list[position]

        // Format start - end time
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val startTime = Date(session.start)
        val endTime = Date(session.start + (session.duration * 1000L))
        val startStr = dateFormat.format(startTime)
        val endStr = dateFormat.format(endTime)

        holder.tvTimeRange.text = "$startStr - $endStr"

        // Duration
        val mins = session.duration / 60
        holder.tvDuration.text = "${mins}m"

        // Type styling
        holder.tvType.text = session.type.uppercase()

        val ctx = holder.itemView.context
        val bg = holder.tvType.background as GradientDrawable

        when (session.type) {
            "work" -> {
                holder.tvType.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onPrimaryContainer))
                bg.setColor(ContextCompat.getColor(ctx, R.color.md_theme_primaryContainer))
            }
            "short" -> {
                holder.tvType.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSecondaryContainer))
                bg.setColor(ContextCompat.getColor(ctx, R.color.md_theme_secondaryContainer))
            }
            "long" -> {
                holder.tvType.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onTertiaryContainer))
                bg.setColor(ContextCompat.getColor(ctx, R.color.md_theme_tertiaryContainer))
            }
            else -> {
                holder.tvType.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSurfaceVariant))
                bg.setColor(ContextCompat.getColor(ctx, R.color.md_theme_surfaceVariant))
            }
        }
    }

    override fun getItemCount() = list.size
}
