package com.pomo.service

import android.app.Service
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import com.google.gson.Gson
import com.pomo.cues.CompletionCueFamily
import com.pomo.cues.CuePreviewChannel
import com.pomo.cues.CuePreviewOutcome
import com.pomo.cues.CueVariant
import com.pomo.cues.StateCueEngine
import com.pomo.cues.StateCueEvent
import com.pomo.crew.CrewRepository
import com.pomo.db.HistoryCacheRepository
import com.pomo.network.PhoneServer
import com.pomo.timer.OfflineTimer
import com.pomo.timer.TimerObserver
import com.pomo.timer.TimerState
import com.pomo.util.UtilPreferenceManager
import com.pomo.widget.TimerWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.net.Inet4Address
import java.net.NetworkInterface

public class PomodoroService : Service(), TimerObserver {

    private val binder = LocalBinder()
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var offlineTimer: OfflineTimer
    private lateinit var historyCacheRepository: HistoryCacheRepository
    private lateinit var prefs: UtilPreferenceManager
    private var currentState: TimerState = TimerState()
    private lateinit var phoneServer: PhoneServer
    private var activePhoneServerPort: Int = PhoneServer.DEFAULT_PORT
    private lateinit var cueEngine: StateCueEngine
    private lateinit var crewRepository: CrewRepository
    private val gson = Gson()

    private var lastSavedState: TimerState? = null

    private val serviceScope = MainScope()
    private val commandMutex = Mutex()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch { restartPhoneServerIfNeeded() }
        }
        override fun onLost(network: Network) {
            serviceScope.launch { restartPhoneServerIfNeeded() }
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            serviceScope.launch { restartPhoneServerIfNeeded() }
        }
    }

    public val pairingToken: String
        get() = prefs.pairingToken

    public val pairingUrl: String
        get() = "http://${getLocalIpAddress()}:${prefs.phoneServerPort}"

    private val isPhoneServerServing: Boolean
        get() = prefs.isPhoneServerEnabled && (!prefs.isPhoneServerWifiOnly || hasActiveLanNetwork())

    public val pairingPayload: String
        get() = gson.toJson(mapOf("url" to pairingUrl, "token" to pairingToken))

    public inner class LocalBinder : Binder() {
        public val service: PomodoroService
            get() = this@PomodoroService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = UtilPreferenceManager(this)
        cueEngine = StateCueEngine(this, prefs)
        crewRepository = CrewRepository(this)
        currentState.goal = prefs.dailyGoal
        notificationHelper = NotificationHelper(this)
        historyCacheRepository = HistoryCacheRepository(this)

        offlineTimer = OfflineTimer(this, prefs, historyCacheRepository, serviceScope)
        activePhoneServerPort = prefs.phoneServerPort
        phoneServer = PhoneServer(this, activePhoneServerPort)

        val savedState = prefs.loadTimerState()
        var shouldCompleteRestoredTimer = false
        if (savedState != null) {
            Log.d(TAG, "Restoring saved state: ${savedState.status} - ${savedState.remaining}s")
            if (savedState.status == TimerState.STATUS_RUNNING) {
                val now = System.currentTimeMillis() / 1000.0
                val elapsed = now - savedState.start_time
                val newRemaining = savedState.duration - elapsed

                if (newRemaining <= 0) {
                    savedState.remaining = 0.0
                    shouldCompleteRestoredTimer = true
                } else {
                    savedState.remaining = newRemaining
                }
            }
            sanitizeState(savedState)
            currentState = savedState
            if (!shouldCompleteRestoredTimer) {
                serviceScope.launch {
                    reconcileStateWithHistory()
                }
            }
        } else {
            currentState.date = historyCacheRepository.getEffectiveDateString()
            sanitizeState(currentState)
            serviceScope.launch {
                currentState.completed = historyCacheRepository.getTodayCompletedCount()
                offlineTimer.updateState(currentState)
                saveCurrentState()
                updateNotification()
                broadcastStateUpdate()
            }
        }

        if (savedState != null) {
            offlineTimer.updateState(currentState)
        }

        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildNotification(currentState, isPhoneServerServing),
        )

        restartPhoneServerIfNeeded()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        if (shouldCompleteRestoredTimer) {
            offlineTimer.completeExpiredTimer()
        }
    }

    private fun saveCurrentState() {
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
        if (newState.start_time != last.start_time) return true
        if (newState.duration != last.duration) return true

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
            "RECONNECT" -> Unit
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback", e)
        }
        serviceScope.cancel()
        phoneServer.stop()
        cueEngine.release()
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
        if (state.remaining > state.duration) {
            state.remaining = state.duration
        }
        if (state.status == TimerState.STATUS_STOPPED && state.remaining <= 0) {
            state.remaining = state.duration
        }
        if (state.version < TimerState().version) {
            state.version = TimerState().version
        }

        if (state.next_phase == null) {
            if (TimerState.PHASE_WORK == state.phase) {
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

    private suspend fun reconcileStateWithHistory() {
        val today = historyCacheRepository.getEffectiveDateString()
        val completed = historyCacheRepository.getTodayCompletedCount()
        var changed = false

        if (currentState.date != today) {
            currentState.status = TimerState.STATUS_STOPPED
            currentState.phase = TimerState.PHASE_WORK
            currentState.next_phase = null
            currentState.start_time = 0.0
            currentState.duration = 0.0
            currentState.remaining = 0.0
            currentState.date = today
            currentState.last_action_time = System.currentTimeMillis() / 1000
            changed = true
        }

        if (currentState.completed != completed) {
            currentState.completed = completed
            changed = true
        }

        sanitizeState(currentState)
        offlineTimer.updateState(currentState)
        if (changed) {
            saveCurrentState()
            updateNotification()
            broadcastStateUpdate()
            publishCrewSnapshot("restored day transition")
        }
    }

    override fun onTimerUpdate(state: TimerState) {
        this.currentState = state
        if (shouldSaveState(state)) {
            saveCurrentState()
        }
        updateNotification()
        broadcastStateUpdate()
    }

    override fun onTimerComplete(state: TimerState) {
        val completedPhase = currentState.phase
        this.currentState = state
        saveCurrentState()
        updateNotification()
        broadcastStateUpdate()
        StateCueEvent.forCompletedPhase(completedPhase)?.let { cueEngine.playCompletion(it) }
        publishCrewSnapshot("timer block complete")
    }

    private fun broadcastStateUpdate() {
        val intent = Intent("com.pomo.STATE_UPDATE")
        sendBroadcast(intent)
        TimerWidgetProvider.updateAllWidgets(this, currentState)
        serviceScope.launch {
            phoneServer.broadcastState()
        }
    }

    private fun updateNotification() {
        notificationHelper.updateNotification(currentState, isPhoneServerServing)
    }

    public fun toggleTimer() {
        serviceScope.launch { toggleTimerBlocking() }
    }

    public fun skipTimer() {
        serviceScope.launch { skipTimerBlocking() }
    }

    public fun resetTimer() {
        serviceScope.launch { resetTimerBlocking() }
    }

    public fun extendTimer(secondsDelta: Int) {
        serviceScope.launch { addTimeBlocking(secondsDelta) }
    }

    public suspend fun toggleTimerBlocking(): TimerState = executeCommand(TimerCommand.Toggle)
        .also { Log.i(TAG, "Timer command executed: toggle status=${it.status} remaining=${it.remaining}") }

    public suspend fun skipTimerBlocking(): TimerState = executeCommand(TimerCommand.Skip)
        .also { Log.i(TAG, "Timer command executed: skip status=${it.status} phase=${it.phase}") }

    public suspend fun resetTimerBlocking(): TimerState = executeCommand(TimerCommand.Reset)
        .also { Log.i(TAG, "Timer command executed: reset status=${it.status} remaining=${it.remaining}") }

    public suspend fun addTimeBlocking(secondsDelta: Int): TimerState =
        executeCommand(TimerCommand.AddTime(secondsDelta))
            .also { Log.i(TAG, "Timer command executed: add_time seconds=$secondsDelta remaining=${it.remaining}") }

    public suspend fun extendTimerBlocking(minutes: Int): TimerState = addTimeBlocking(minutes.coerceAtLeast(1) * 60)

    public fun nextCompletionCueVariant(family: CompletionCueFamily): CueVariant = cueEngine.nextVariant(family)

    public fun previewCompletionCue(
        family: CompletionCueFamily,
        variant: CueVariant,
        channel: CuePreviewChannel,
    ): CuePreviewOutcome = cueEngine.previewCompletion(family, variant, channel)

    public fun previewManualCue(event: StateCueEvent): CuePreviewOutcome = cueEngine.previewManual(event)

    private suspend fun executeCommand(command: TimerCommand): TimerState = commandMutex.withLock {
        withContext(Dispatchers.Main) {
            reconcileDayTransitionIfNeeded(notify = false)
            val before = currentState.copy()
            val event = when (command) {
                TimerCommand.Toggle -> if (currentState.status == TimerState.STATUS_RUNNING) {
                    StateCueEvent.PauseTapped
                } else {
                    StateCueEvent.StartOrResumeTapped
                }
                TimerCommand.Skip -> StateCueEvent.SkipTapped
                TimerCommand.Reset -> StateCueEvent.ResetTapped
                is TimerCommand.AddTime -> null
            }
            when (event) {
                StateCueEvent.StartOrResumeTapped, StateCueEvent.PauseTapped -> offlineTimer.toggle()
                StateCueEvent.SkipTapped -> offlineTimer.skip()
                StateCueEvent.ResetTapped -> offlineTimer.reset()
                else -> if (command is TimerCommand.AddTime) {
                    offlineTimer.extend(command.secondsDelta)
                }
            }
            if (event != null && didStateChange(before, currentState)) {
                cueEngine.playManual(event)
                if (event == StateCueEvent.StartOrResumeTapped || event == StateCueEvent.PauseTapped) {
                    publishCrewSnapshot("timer ${event.name}")
                }
            }
            currentState.copy()
        }
    }

    private sealed class TimerCommand {
        object Toggle : TimerCommand()
        object Skip : TimerCommand()
        object Reset : TimerCommand()
        data class AddTime(val secondsDelta: Int) : TimerCommand()
    }

    private fun didStateChange(before: TimerState, after: TimerState): Boolean {
        return before.status != after.status ||
            before.phase != after.phase ||
            doublesDiffer(before.remaining, after.remaining) ||
            doublesDiffer(before.start_time, after.start_time) ||
            before.last_action_time != after.last_action_time
    }

    private fun doublesDiffer(before: Double, after: Double, tolerance: Double = 0.001): Boolean =
        abs(before - after) > tolerance

    private suspend fun reconcileDayTransitionIfNeeded(notify: Boolean) {
        val today = historyCacheRepository.getEffectiveDateString()
        if (currentState.date == today) return

        val completed = historyCacheRepository.getTodayCompletedCount()

        Log.d(TAG, "Day transition detected: ${currentState.date} -> $today. Resetting state.")
        currentState.status = TimerState.STATUS_STOPPED
        currentState.phase = TimerState.PHASE_WORK
        currentState.next_phase = null
        currentState.start_time = 0.0
        currentState.duration = 0.0
        currentState.remaining = 0.0
        currentState.date = today
        currentState.last_action_time = System.currentTimeMillis() / 1000
        currentState.completed = completed

        sanitizeState(currentState)
        offlineTimer.updateState(currentState)
        saveCurrentState()

        if (notify) {
            updateNotification()
            broadcastStateUpdate()
        }
        publishCrewSnapshot("day rollover")
    }

    private fun publishCrewSnapshot(reason: String) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                crewRepository.publishCurrentSnapshot()
            }.onFailure { error ->
                Log.w(TAG, "Failed to publish Crew snapshot after $reason", error)
            }
        }
    }

    public fun updateDailyGoal() {
        val newGoal = prefs.dailyGoal
        if (currentState.goal != newGoal) {
            currentState.goal = newGoal
            saveCurrentState()
            updateNotification()
            broadcastStateUpdate()
        }
    }

    public fun syncConfig() {
        updateDailyGoal()
        restartPhoneServerIfNeeded()
        broadcastStateUpdate()
    }

    private fun restartPhoneServerIfNeeded() {
        val newPort = prefs.phoneServerPort
        val shouldServe = isPhoneServerServing
        if (!shouldServe) {
            phoneServer.stop()
            return
        }

        if (newPort == activePhoneServerPort && phoneServer.isRunning) return

        Log.d(TAG, "Restarting phone API on port $newPort")
        phoneServer.stop()
        activePhoneServerPort = newPort
        phoneServer = PhoneServer(this, activePhoneServerPort)
        phoneServer.start()
    }

    public fun rotatePairingToken(): String {
        val token = prefs.rotatePairingToken()
        // Force a full stop+start so existing WebSocket clients are disconnected and
        // must re-pair with the new token. restartPhoneServerIfNeeded() skips the
        // restart when the port is unchanged and the server is already running.
        phoneServer.stop()
        if (isPhoneServerServing) {
            phoneServer = PhoneServer(this, activePhoneServerPort)
            phoneServer.start()
        }
        broadcastStateUpdate()
        return token
    }

    public suspend fun stateSnapshot(): TimerState = commandMutex.withLock {
        withContext(Dispatchers.Main) {
            currentState.copy()
        }
    }

    public fun getConfigPayload(): ConfigPayload {
        return ConfigPayload(
            durations = Durations(
                work = prefs.pomodoroDuration,
                short_break = prefs.shortBreakDuration,
                long_break = prefs.longBreakDuration,
            ),
            long_break_after = prefs.longBreakAfter,
            daily_goal = prefs.dailyGoal,
        )
    }

    public suspend fun applyConfigPayload(body: String): TimerState = withContext(Dispatchers.Main) {
        val config = TimerConfigPayloads.parseAndMerge(body, currentConfigValues())
        prefs.pomodoroDuration = config.work
        prefs.shortBreakDuration = config.shortBreak
        prefs.longBreakDuration = config.longBreak
        prefs.longBreakAfter = config.longBreakAfter
        prefs.dailyGoal = config.dailyGoal

        currentState.goal = prefs.dailyGoal
        if (currentState.status != TimerState.STATUS_RUNNING) {
            currentState.duration = getDurationForPhase(currentState.phase)
            currentState.remaining = currentState.duration
        }
        sanitizeState(currentState)
        offlineTimer.updateState(currentState)
        saveCurrentState()
        updateNotification()
        broadcastStateUpdate()
        currentState.copy()
    }

    private fun currentConfigValues(): TimerConfigPayloads.Values {
        return TimerConfigPayloads.Values(
            work = prefs.pomodoroDuration,
            shortBreak = prefs.shortBreakDuration,
            longBreak = prefs.longBreakDuration,
            longBreakAfter = prefs.longBreakAfter,
            dailyGoal = prefs.dailyGoal,
        )
    }

    public suspend fun getHistoryPayload(): Map<String, HistoryCacheRepository.ServerDayEntry> {
        return historyCacheRepository.getHistoryPayload()
    }

    private fun getDurationForPhase(phase: String): Double {
        val minutes = when (phase) {
            TimerState.PHASE_WORK -> prefs.pomodoroDuration
            TimerState.PHASE_SHORT -> prefs.shortBreakDuration
            TimerState.PHASE_LONG -> prefs.longBreakDuration
            else -> prefs.pomodoroDuration
        }
        return (minutes * 60).toDouble()
    }

    private fun getLocalIpAddress(): String {
        getActiveLanIpAddress()?.let { return it }

        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
                ?: NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }?.hostAddress
                ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun getActiveLanIpAddress(): String? {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return null
            }

            connectivityManager.getLinkProperties(network)
                ?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun hasActiveLanNetwork(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.any { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    public companion object {
        private const val TAG: String = "PomodoroService"
    }

    public data class ConfigPayload(
        val durations: Durations,
        val long_break_after: Int,
        val daily_goal: Int,
    )

    public data class Durations(
        val work: Int,
        val short_break: Int,
        val long_break: Int,
    )

}
