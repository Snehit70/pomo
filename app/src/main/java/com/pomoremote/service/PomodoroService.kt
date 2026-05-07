package com.pomoremote.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.util.Log
import com.pomoremote.network.WebSocketClient
import com.pomoremote.db.HistoryCacheRepository
import com.pomoremote.timer.OfflineTimer
import com.pomoremote.timer.SyncManager
import com.pomoremote.timer.TimerState
import com.pomoremote.util.UtilPreferenceManager
import com.pomoremote.widget.TimerWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class PomodoroService : Service(), WebSocketClient.Listener {

    private val binder = LocalBinder()
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var offlineTimer: OfflineTimer
    private lateinit var syncManager: SyncManager
    private lateinit var historyCacheRepository: HistoryCacheRepository
    private lateinit var prefs: UtilPreferenceManager
    var isConnected: Boolean = false
        private set
    var currentState: TimerState = TimerState()
        private set
    private lateinit var httpClient: OkHttpClient
    private lateinit var mainHandler: Handler
    private var currentRingtone: Ringtone? = null

    private var lastSavedState: TimerState? = null

    // Coroutine scope for service operations
    private val serviceScope = MainScope()

    @Volatile
    private var syncCompleted = false
    @Volatile
    private var readySent = false
    private var syncTimeoutRunnable: Runnable? = null

    inner class LocalBinder : Binder() {
        val service: PomodoroService
            get() = this@PomodoroService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = UtilPreferenceManager(this)
        currentState.goal = prefs.dailyGoal
        notificationHelper = NotificationHelper(this)
        webSocketClient = WebSocketClient(this)
        historyCacheRepository = HistoryCacheRepository(this)

        offlineTimer = OfflineTimer(this, prefs, historyCacheRepository, serviceScope)
        syncManager = SyncManager()
        httpClient = OkHttpClient()
        mainHandler = Handler(Looper.getMainLooper())

        // Restore state or initialize default
        val savedState = prefs.loadTimerState()
        if (savedState != null) {
            Log.d(TAG, "Restoring saved state: ${savedState.status} - ${savedState.remaining}s")
            // Recalculate if it was running
            if (savedState.status == TimerState.STATUS_RUNNING) {
                val now = System.currentTimeMillis() / 1000.0
                val elapsed = now - savedState.start_time
                val newRemaining = savedState.duration - elapsed

                if (newRemaining <= 0) {
                    savedState.remaining = 0.0
                    savedState.status = TimerState.STATUS_STOPPED
                    // We treat it as stopped at 0, user has to handle completion manually or we just let it be.
                    // For simplicity, we just show it as 0.
                } else {
                    savedState.remaining = newRemaining
                }
            }
            sanitizeState(savedState)
            currentState = savedState
        } else {
            // Initialize state with current date and completed count
            currentState.date = historyCacheRepository.getEffectiveDateString(prefs.dayStartHour)
            // Fetch completed count async
            serviceScope.launch {
                currentState.completed = historyCacheRepository.getTodayCompletedCount(prefs.dayStartHour)
                offlineTimer.updateState(currentState)
                saveCurrentState()
            }
            // Ensure next_phase is set
            sanitizeState(currentState)
        }

        // Sync the OfflineTimer with the restored/initial state
        offlineTimer.updateState(currentState)

        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildNotification(currentState, false)
        )

        connect()
    }

    private fun saveCurrentState() {
        // We use a copy to avoid concurrency issues if the state object is being mutated elsewhere
        // (though currently it's all on main thread)
        val stateToSave = currentState.copy()
        lastSavedState = stateToSave
        prefs.saveTimerState(stateToSave)
    }

    private fun shouldSaveState(newState: TimerState): Boolean {
        val last = lastSavedState ?: return true

        if (newState.status != last.status) return true
        if (newState.phase != last.phase) return true
        if (newState.goal != last.goal) return true
        if (newState.completed != last.completed) return true
        if (newState.date != last.date) return true
        // If start_time changed (e.g. restart/resume), we must save
        if (newState.start_time != last.start_time) return true

        // If NOT running, any change in remaining time is a manual adjustment or pause-time update
        // that we should probably persist.
        if (newState.status != TimerState.STATUS_RUNNING && newState.remaining != last.remaining) {
            return true
        }

        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action != null) {
            handleAction(intent.action!!)
        }
        return START_STICKY
    }

    private fun handleAction(action: String) {
        when (action) {
            "TOGGLE" -> toggleTimer()
            "SKIP" -> skipTimer()
            "RECONNECT" -> reconnect()
        }
    }

    fun connect() {
        val ip = prefs.laptopIp
        val port = prefs.laptopPort
        webSocketClient.connect(ip, port)
    }

    fun reconnect() {
        webSocketClient.close()
        connect()
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // Cancel all coroutines
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        webSocketClient.close()
    }

    override fun onConnected() {
        mainHandler.post {
            isConnected = true
            syncCompleted = false
            readySent = false
            Log.d(TAG, "Connected to WebSocket - starting sync")

            syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            syncTimeoutRunnable = Runnable {
                if (!syncCompleted) {
                    Log.w(TAG, "Sync timeout - accepting WS messages anyway")
                    syncCompleted = true
                    readySent = true
                    webSocketClient.send("{\"type\":\"ready\"}")
                }
            }
            mainHandler.postDelayed(syncTimeoutRunnable!!, SYNC_TIMEOUT_MS)

            serviceScope.launch {
                val localState = offlineTimer.state

                // Sync history (Push unsynced -> Pull latest)
                try {
                    historyCacheRepository.syncWithServer(prefs.laptopIp, prefs.laptopPort)
                } catch (e: Exception) {
                    Log.e(TAG, "History sync failed", e)
                }

                try {
                    val mergedState = syncManager.syncNow(prefs.laptopIp, prefs.laptopPort, localState)

                    syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }

                    Log.d(TAG, "Sync successful - applying merged state")

                    // Validate completed count against actual history (timestamps)
                    val today = historyCacheRepository.getEffectiveDateString(prefs.dayStartHour)
                    // If the state is for today, verify the count
                    if (mergedState.date == today) {
                        val historySessions = historyCacheRepository.getSessionsForDate(today)
                        val calculatedCompleted = historySessions.count {
                            it.type == TimerState.PHASE_WORK && it.completed
                        }

                        if (mergedState.completed != calculatedCompleted) {
                            Log.w(TAG, "State completed count (${mergedState.completed}) mismatch with history ($calculatedCompleted). Correcting.")
                            mergedState.completed = calculatedCompleted
                        }
                    }

                    sanitizeState(mergedState)
                    // Use local dailyGoal - phone is source of truth for settings
                    mergedState.goal = prefs.dailyGoal
                    currentState = mergedState
                    saveCurrentState()
                    offlineTimer.updateState(mergedState)
                    syncCompleted = true

                    // Push local config to server (phone is source of truth)
                    syncConfig()

                    webSocketClient.send("{\"type\":\"ready\"}")
                    readySent = true
                    Log.d(TAG, "Sent ready message to server")

                    updateNotification()
                    broadcastStateUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed - will accept WS messages on timeout", e)
                }
            }
        }
    }

    override fun onDisconnected() {
        mainHandler.post {
            isConnected = false
            syncCompleted = false
            readySent = false

            syncTimeoutRunnable?.let {
                mainHandler.removeCallbacks(it)
                syncTimeoutRunnable = null
            }

            Log.d(TAG, "Disconnected from WebSocket")
            syncManager.setOffline()

            if (TimerState.STATUS_RUNNING == currentState.status) {
                offlineTimer.updateState(currentState)
            }

            updateNotification()
            broadcastStateUpdate()
        }
    }

    override fun onStateReceived(state: TimerState) {
        mainHandler.post {
            if (!syncCompleted || !readySent) {
                Log.d(TAG, "Ignoring WS state - sync not completed yet")
                return@post
            }

            sanitizeState(state)
            // Preserve local dailyGoal - phone is source of truth for settings
            state.goal = prefs.dailyGoal
            this.currentState = state
            if (shouldSaveState(state)) {
                saveCurrentState()
            }
            offlineTimer.updateState(state)
            updateNotification()
            broadcastStateUpdate()
        }
    }

    private fun sanitizeState(state: TimerState) {
        if (state.duration <= 0) {
            when (state.phase) {
                TimerState.PHASE_WORK -> state.duration = (prefs.pomodoroDuration * 60).toDouble()
                TimerState.PHASE_SHORT -> state.duration = (prefs.shortBreakDuration * 60).toDouble()
                TimerState.PHASE_LONG -> state.duration = (prefs.longBreakDuration * 60).toDouble()
                else -> state.duration = (prefs.pomodoroDuration * 60).toDouble()
            }
        }
        // Ensure remaining doesn't exceed duration
        if (state.remaining > state.duration) {
            state.remaining = state.duration
        }

        // Ensure next_phase is populated
        if (state.next_phase == null) {
            if (TimerState.PHASE_WORK == state.phase) {
                // If we are in WORK, next is BREAK.
                // We assume we are looking at the *current* state.
                // If this state finishes, completed + 1.
                val nextCompleted = state.completed + 1
                if (nextCompleted > 0 && nextCompleted % prefs.longBreakAfter == 0) {
                    state.next_phase = TimerState.PHASE_LONG
                } else {
                    state.next_phase = TimerState.PHASE_SHORT
                }
            } else {
                state.next_phase = TimerState.PHASE_WORK
            }
        }
    }

    fun onTimerUpdate(state: TimerState) {
        this.currentState = state
        if (shouldSaveState(state)) {
            saveCurrentState()
        }
        updateNotification()
        broadcastStateUpdate()
    }

    fun onTimerComplete(state: TimerState) {
        this.currentState = state
        saveCurrentState()
        updateNotification()
        broadcastStateUpdate()
        vibrate()
        playSound()
    }

    private fun broadcastStateUpdate() {
        val intent = Intent("com.pomoremote.STATE_UPDATE")
        sendBroadcast(intent)
        TimerWidgetProvider.updateAllWidgets(this, currentState)
    }

    private fun updateNotification() {
        notificationHelper.updateNotification(currentState, isConnected)
    }

    fun toggleTimer() {
        checkDayTransition()
        if (isConnected) {
            sendApiRequest("toggle")
        } else {
            offlineTimer.toggle()
        }
    }

    fun skipTimer() {
        checkDayTransition()
        if (isConnected) {
            sendApiRequest("skip")
        } else {
            offlineTimer.skip()
        }
    }

    fun resetTimer() {
        checkDayTransition()
        if (isConnected) {
            sendApiRequest("reset")
        } else {
            offlineTimer.reset()
        }
    }

    private fun checkDayTransition() {
        val today = historyCacheRepository.getEffectiveDateString(prefs.dayStartHour)
        if (currentState.date != today) {
            Log.d(TAG, "Day transition detected: ${currentState.date} -> $today. Resetting state.")
            currentState.status = TimerState.STATUS_STOPPED
            currentState.phase = TimerState.PHASE_WORK
            currentState.next_phase = TimerState.PHASE_WORK // Ensure next phase is reset too
            currentState.start_time = 0.0
            currentState.duration = 0.0
            currentState.remaining = 0.0
            currentState.date = today
            currentState.last_action_time = System.currentTimeMillis() / 1000

            // Reset count immediately to avoid showing yesterday's count, update with true count async
            currentState.completed = 0

            serviceScope.launch {
                currentState.completed = historyCacheRepository.getTodayCompletedCount(prefs.dayStartHour)
                offlineTimer.updateState(currentState)
                saveCurrentState()
                updateNotification()
                broadcastStateUpdate()
            }

            offlineTimer.updateState(currentState)
            saveCurrentState()
            updateNotification()
            broadcastStateUpdate()
        }
    }

    fun updateDailyGoal() {
        val newGoal = prefs.dailyGoal
        if (currentState.goal != newGoal) {
            currentState.goal = newGoal
            saveCurrentState()
            updateNotification()
            broadcastStateUpdate()
        }
    }

    fun syncConfig() {
        serviceScope.launch {
            if (!isConnected) return@launch

            val durations = SyncManager.Durations(
                work = prefs.pomodoroDuration,
                short_break = prefs.shortBreakDuration,
                long_break = prefs.longBreakDuration
            )

            val config = SyncManager.ConfigPayload(
                durations = durations,
                long_break_after = prefs.longBreakAfter,
                daily_goal = prefs.dailyGoal,
                day_start_hour = prefs.dayStartHour
            )

            syncManager.syncConfig(prefs.laptopIp, prefs.laptopPort, config)
        }
    }

    private fun sendApiRequest(endpoint: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ip = prefs.laptopIp
                val port = prefs.laptopPort
                val url = "http://$ip:$port/api/$endpoint"

                val body = "".toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().close()
            } catch (e: IOException) {
                Log.e(TAG, "API request failed", e)
            }
        }
    }

    private fun vibrate() {
        if (!prefs.isVibrateEnabled) return

        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(500)
        }
    }

    private fun playSound() {
        if (!prefs.isSoundEnabled) return

        try {
            if (currentRingtone != null && currentRingtone!!.isPlaying) {
                currentRingtone!!.stop()
            }

            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            currentRingtone = RingtoneManager.getRingtone(applicationContext, notification)

            if (currentRingtone != null) {
                currentRingtone!!.play()
            } else {
                Log.w(TAG, "Could not get ringtone")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play sound", e)
        }
    }

    companion object {
        private const val TAG = "PomodoroService"
        private const val SYNC_TIMEOUT_MS = 10000L
    }
}
