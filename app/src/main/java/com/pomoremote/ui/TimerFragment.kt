package com.pomoremote.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.pomoremote.MainActivity
import com.pomoremote.R
import com.pomoremote.db.HistoryCacheRepository
import com.pomoremote.timer.TimerState
import java.text.SimpleDateFormat
import java.util.Locale
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TimerFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    private lateinit var tvTimer: TextView
    private lateinit var tvPhase: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: FloatingActionButton
    private lateinit var btnSkip: Button
    private lateinit var btnReset: Button
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var goalProgressIndicator: CircularProgressIndicator

    // Stats card views
    private lateinit var tvTodayFocus: TextView
    private lateinit var tvSessions: TextView
    private lateinit var tvStreak: TextView

    private var historyRepository: HistoryCacheRepository? = null

    // We access the service through MainActivity which holds the connection
    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    // Interpolation state
    private var lastServerState: TimerState? = null
    private var lastSyncTime: Long = 0
    private val animationHandler = Handler(Looper.getMainLooper())
    private val animationRunnable = object : Runnable {
        override fun run() {
            updateInterpolatedDisplay()
            animationHandler.postDelayed(this, 16) // ~60fps
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_timer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTimer = view.findViewById(R.id.tvTimer)
        tvPhase = view.findViewById(R.id.tvPhase)
        tvStatus = view.findViewById(R.id.tvConnectionStatus)
        btnToggle = view.findViewById(R.id.btnToggle)
        btnSkip = view.findViewById(R.id.btnSkip)
        btnReset = view.findViewById(R.id.btnReset)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        goalProgressIndicator = view.findViewById(R.id.goalProgressIndicator)

        // Stats card
        tvTodayFocus = view.findViewById(R.id.tvTodayFocus)
        tvSessions = view.findViewById(R.id.tvSessions)
        tvStreak = view.findViewById(R.id.tvStreak)

        btnToggle.setOnClickListener { mainActivity?.toggleTimer() }
        btnSkip.setOnClickListener { mainActivity?.skipTimer() }
        btnReset.setOnClickListener { mainActivity?.resetTimer() }
        historyRepository = context?.let { HistoryCacheRepository(it) }

        // Start animation loop
        animationHandler.post(animationRunnable)

        // Initial UI update if service is already bound
        mainActivity?.service?.currentState?.let { updateUI(it) }

        observeStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        animationHandler.removeCallbacks(animationRunnable)
    }

    private fun observeStats() {
        val repo = historyRepository ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeDayStats().collectLatest { entities ->
                val historyMap = entities.associate {
                    it.date to DayEntry(
                        completed = it.completed,
                        work_minutes = it.workMinutes,
                        break_minutes = it.breakMinutes
                    )
                }
                val dayStartHour = mainActivity?.prefs?.dayStartHour ?: 3
                val calendar = java.util.Calendar.getInstance()
                if (calendar.get(java.util.Calendar.HOUR_OF_DAY) < dayStartHour) {
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
                val todayEntry = historyMap[today]
                val streak = calculateStreak(historyMap, dayStartHour)

                val minutes = todayEntry?.work_minutes ?: 0
                val hours = minutes / 60
                val mins = minutes % 60
                tvTodayFocus.text = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                tvSessions.text = "${todayEntry?.completed ?: 0}"
                tvStreak.text = "$streak"
            }
        }
    }

    private fun calculateStreak(history: Map<String, DayEntry>, dayStartHour: Int): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sortedDates = history.keys
            .filter { (history[it]?.completed ?: 0) > 0 }
            .sortedDescending()

        if (sortedDates.isEmpty()) return 0

        var streak = 0
        val calendar = java.util.Calendar.getInstance()
        // Start from the effective current date
        if (calendar.get(java.util.Calendar.HOUR_OF_DAY) < dayStartHour) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }

        for (i in sortedDates.indices) {
            val expectedDate = dateFormat.format(calendar.time)
            if (sortedDates.contains(expectedDate)) {
                streak++
                calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
            } else if (i == 0) {
                // Today might not have sessions yet, check yesterday
                calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
                val yesterday = dateFormat.format(calendar.time)
                if (sortedDates.contains(yesterday)) {
                    streak++
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
                } else {
                    break
                }
            } else {
                break
            }
        }
        return streak
    }

    // Called by MainActivity when state updates
    fun updateUI(state: TimerState) {
        val context = context ?: return
        if (!isAdded) return

        // Sync state for interpolation
        lastServerState = state
        lastSyncTime = System.currentTimeMillis()

        // Update static UI elements immediately
        updateStaticUI(state, context)
    }

    private fun updateInterpolatedDisplay() {
        val state = lastServerState ?: return
        if (!isAdded) return

        var remaining = state.remaining

        // If running, interpolate based on time elapsed since last sync
        if (state.status == TimerState.STATUS_RUNNING) {
            val elapsedSeconds = (System.currentTimeMillis() - lastSyncTime) / 1000.0
            remaining = (state.remaining - elapsedSeconds).coerceAtLeast(0.0)
        }

        // Render remaining time with smooth milliseconds
        val totalSeconds = remaining
        val minutes = totalSeconds.toInt() / 60
        val seconds = totalSeconds.toInt() % 60
        val millis = ((totalSeconds - totalSeconds.toInt()) * 100).toInt()
        tvTimer.text = String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, millis)

        // Update progress bar smoothly
        var total = state.duration
        if (total <= 0) {
             // Fallback default durations if missing
             total = when (state.phase) {
                 TimerState.PHASE_WORK -> 1500.0
                 TimerState.PHASE_SHORT -> 300.0
                 TimerState.PHASE_LONG -> 900.0
                 else -> 1500.0
             }
        }
        val progress = ((remaining / total) * 100).toInt()
        progressIndicator.setProgressCompat(progress, true)
    }

    private fun updateStaticUI(state: TimerState, context: android.content.Context) {
        var phaseName = state.phase
        var colorRes = R.color.md_theme_primary

        if (TimerState.PHASE_WORK == state.phase) {
            phaseName = "Focus"
            colorRes = R.color.md_theme_primary
        } else if (TimerState.PHASE_SHORT == state.phase) {
            phaseName = "Short Break"
            colorRes = R.color.md_theme_secondary
        } else if (TimerState.PHASE_LONG == state.phase) {
            phaseName = "Long Break"
            colorRes = R.color.md_theme_secondary
        }

        tvPhase.text = phaseName
        val color = ContextCompat.getColor(context, colorRes)
        tvPhase.setTextColor(color)
        tvTimer.setTextColor(color) // Sync timer text color with phase
        progressIndicator.setIndicatorColor(color)
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

        // Tint secondary buttons
        btnSkip.setTextColor(color)
        // btnSkip.iconTint = colorStateList
        btnReset.setTextColor(color)
        // btnReset.iconTint = colorStateList

        // Button Icon
        if (TimerState.STATUS_RUNNING == state.status) {
            btnToggle.setImageResource(R.drawable.ic_pause)
            btnToggle.contentDescription = "Pause"
        } else {
            btnToggle.setImageResource(R.drawable.ic_play)
            btnToggle.contentDescription = if (state.status == TimerState.STATUS_PAUSED) "Resume" else "Start"
        }

        tvStatus.text = "Phone primary"
        tvStatus.setTextColor(ContextCompat.getColor(context, R.color.status_connected))

        // Real-time session update
        tvSessions.text = "${state.completed}"

        // Update goal progress ring
        val dailyGoal = if (state.goal > 0) state.goal else (mainActivity?.prefs?.dailyGoal ?: 8)
        val goalProgress = ((state.completed.toFloat() / dailyGoal) * 100).toInt().coerceIn(0, 100)
        goalProgressIndicator.setProgressCompat(goalProgress, true)
    }
}
