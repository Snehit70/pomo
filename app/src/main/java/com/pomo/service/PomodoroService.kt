package com.pomo.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import com.pomo.achievements.AchievementCatalog
import com.pomo.achievements.AchievementEvaluator
import com.pomo.crew.CrewRepository
import com.pomo.cues.CompletionCueFamily
import com.pomo.cues.CuePreviewChannel
import com.pomo.cues.CuePreviewOutcome
import com.pomo.cues.CueVariant
import com.pomo.cues.StateCueEngine
import com.pomo.cues.StateCueEvent
import com.pomo.db.HistoryCacheRepository
import com.pomo.network.LinkLog
import com.pomo.network.PhoneMessages
import com.pomo.network.PhoneServer
import com.pomo.network.PomoServiceAdvertiser
import com.pomo.network.SessionImportPayloads
import com.pomo.network.TimerAdoptPayloads
import com.pomo.notifications.AlertsNotifier
import com.pomo.stats.StatsAggregator
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
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.abs

public class PomodoroService : Service(), TimerObserver {
    private val binder = LocalBinder()
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var offlineTimer: OfflineTimer
    private lateinit var historyCacheRepository: HistoryCacheRepository
    private lateinit var prefs: UtilPreferenceManager
    private var currentState: TimerState = TimerState()
    private lateinit var phoneServer: PhoneServer
    private var activePhoneServerPort: Int = PhoneServer.DEFAULT_PORT
    private lateinit var serviceAdvertiser: PomoServiceAdvertiser
    private lateinit var cueEngine: StateCueEngine
    private lateinit var crewRepository: CrewRepository
    private lateinit var alertsNotifier: AlertsNotifier
    private val gson = Gson()

    // Earned-achievement ids as of the last observation, seeded on start. Null until that first read
    // completes, so a work block that races startup seeds the baseline instead of firing a
    // notification for every already-earned tile.
    private var knownEarnedIds: Set<String>? = null

    private var lastSavedState: TimerState? = null

    private val serviceScope = MainScope()
    private val commandMutex = Mutex()
    private val achievementMutex = Mutex()

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch { restartPhoneServerIfNeeded() }
                // A block finished while offline never reached the relay. Coming back online is the
                // first chance to send it, and it must not wait for the Crew page to be opened.
                publishCrewCatchUp()
            }

            override fun onLost(network: Network) {
                serviceScope.launch { restartPhoneServerIfNeeded() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
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

    /** Session-scoped desk-link activity for the Settings log screen. Never contains secrets. */
    public fun linkLogSnapshot(): String = LinkLog.snapshot()

    public inner class LocalBinder : Binder() {
        public val service: PomodoroService
            get() = this@PomodoroService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = UtilPreferenceManager(this)
        cueEngine = StateCueEngine(this, prefs) { refreshRingNotification() }
        crewRepository = CrewRepository(this)
        currentState.goal = prefs.dailyGoal
        notificationHelper = NotificationHelper(this)
        alertsNotifier = AlertsNotifier(this)
        historyCacheRepository = HistoryCacheRepository(this)
        seedAchievementBaseline()

        offlineTimer = OfflineTimer(this, prefs, historyCacheRepository, serviceScope)
        activePhoneServerPort = prefs.phoneServerPort
        phoneServer = PhoneServer(this, activePhoneServerPort)
        serviceAdvertiser = PomoServiceAdvertiser.forContext(this)

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
        val networkRequest =
            NetworkRequest.Builder()
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
        if (newState.tag != last.tag) return true

        if (newState.status != TimerState.STATUS_RUNNING && newState.remaining != last.remaining) {
            return true
        }

        return false
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent != null && intent.action != null) {
            handleAction(intent.action!!)
        }
        return START_STICKY
    }

    private fun handleAction(action: String) {
        when (action) {
            "TOGGLE" -> toggleTimer()
            "SKIP" -> skipTimer()
            "DISMISS" -> cueEngine.stop()
            "RECONNECT" -> Unit
        }
    }

    /** Post or clear the heads-up ring notification to match the engine's ring state. */
    private fun refreshRingNotification() {
        if (cueEngine.isRinging()) {
            notificationHelper.showRingNotification(currentState)
        } else {
            notificationHelper.cancelRingNotification()
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
        // Unregister the advertisement before killing the server, so a client never
        // resolves a record pointing at a socket that has already closed.
        serviceAdvertiser.stop()
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

        // A running block is allowed to cross midnight intact: it keeps ticking and is
        // filed under its start day when it completes (ADR-0002). Don't reset or re-date
        // it here — handleTimerComplete handles the day transition on completion.
        val runningAcrossMidnight =
            currentState.date != today && currentState.status == TimerState.STATUS_RUNNING

        if (currentState.date != today && !runningAcrossMidnight) {
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

        if (!runningAcrossMidnight && currentState.completed != completed) {
            currentState.completed = completed
            changed = true
        }

        sanitizeState(currentState)
        offlineTimer.updateState(currentState)
        if (changed) {
            saveCurrentState()
            updateNotification()
            broadcastStateUpdate()
        }

        // Work blocks that complete while the service is dead are recorded to history but
        // never fire onTimerComplete, so no Crew snapshot is published and the leaderboard
        // lags local history. Catch up by publishing when local history holds a completed
        // block newer than the last publish — this covers blocks from a previous day too.
        publishCrewCatchUp()
    }

    override fun onTimerUpdate(state: TimerState) {
        this.currentState = state
        if (shouldSaveState(state)) {
            saveCurrentState()
        }
        updateNotification()
        broadcastStateUpdate()
    }

    override fun onTimerComplete(
        state: TimerState,
        completedPhase: String,
    ) {
        this.currentState = state
        saveCurrentState()
        updateNotification()
        broadcastPhaseComplete(completedPhase)
        broadcastStateUpdate()
        StateCueEvent.forCompletedPhase(completedPhase)?.let { cueEngine.playCompletion(it) }
        if (completedPhase == TimerState.PHASE_WORK) {
            publishCrewSnapshot("work block complete")
            checkForNewAchievements()
        }
    }

    override fun onPartialWorkBlockRecorded() {
        // Partial skip minutes count toward Crew ranking, so refresh the snapshot now
        // rather than waiting for the next completed block (ADR-0002).
        publishCrewSnapshot("partial work block")
        // Partial focus counts toward lifetime minutes too, so it can cross a Focus rung.
        checkForNewAchievements()
    }

    private fun seedAchievementBaseline() {
        serviceScope.launch(Dispatchers.IO) {
            achievementMutex.withLock {
                if (knownEarnedIds == null) knownEarnedIds = currentEarnedIds()
            }
        }
    }

    private suspend fun currentEarnedIds(): Set<String> {
        val snapshot =
            StatsAggregator.aggregate(
                days = historyCacheRepository.getCachedDayStats(),
                sessions = historyCacheRepository.getCachedSessions(),
                dailyGoal = prefs.dailyGoal,
                today = historyCacheRepository.getEffectiveDateString(),
                nowMs = System.currentTimeMillis(),
            )
        return AchievementEvaluator.earnedOnly(snapshot).map { it.id }.toSet()
    }

    /**
     * A work block just committed to Room. Diff the evaluator across the commit (ADR 0005 decision 8)
     * and, for anything newly earned, post a quiet notification and light the Profile-tab dot.
     */
    private fun checkForNewAchievements() {
        serviceScope.launch(Dispatchers.IO) {
            achievementMutex.withLock {
                val nowEarned = currentEarnedIds()
                val previous = knownEarnedIds
                knownEarnedIds = nowEarned
                // Baseline not yet established: this read becomes the baseline, nothing is "new" yet.
                if (previous == null) return@withLock
                val newly = nowEarned - previous
                if (newly.isEmpty()) return@withLock
                prefs.hasUnseenAchievement = true
                alertsNotifier.notifyAchievements(AchievementCatalog.all.filter { it.id in newly })
                // Nudge any foreground UI so the Profile-tab dot appears now, not on the next resume.
                // Explicit package required for RECEIVER_NOT_EXPORTED receivers on modern Android.
                sendBroadcast(Intent("com.pomo.STATE_UPDATE").setPackage(packageName))
            }
        }
    }

    private fun broadcastStateUpdate() {
        // Explicit package so same-app RECEIVER_NOT_EXPORTED receivers reliably get the intent.
        val intent = Intent("com.pomo.STATE_UPDATE").setPackage(packageName)
        sendBroadcast(intent)
        TimerWidgetProvider.updateAllWidgets(this, currentState)
        serviceScope.launch {
            phoneServer.broadcastState()
        }
    }

    /**
     * Tells remote clients a phase ended on its own. Hardware clients ring on this
     * and stay silent on skip or reset, which a state snapshot alone cannot
     * distinguish — that distinction is the whole reason the event exists.
     *
     * This is dispatched before the state broadcast, but the two are independent,
     * fire-and-forget coroutines with no joint failure handling: a dead-session
     * drop can deliver one frame and not the other. Dispatch order is not a
     * delivery guarantee, so remote clients must treat the event and the state
     * snapshot as independent rather than assuming they arrive as a pair — the
     * buzzer reacts to the event, the display to the state, and neither waits
     * for the other.
     */
    private fun broadcastPhaseComplete(completedPhase: String) {
        serviceScope.launch {
            phoneServer.broadcastEvent(PhoneMessages.EVENT_PHASE_COMPLETE, completedPhase)
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

    public suspend fun toggleTimerBlocking(): TimerState =
        executeCommand(TimerCommand.Toggle)
            .also { Log.i(TAG, "Timer command executed: toggle status=${it.status} remaining=${it.remaining}") }

    public suspend fun skipTimerBlocking(): TimerState =
        executeCommand(TimerCommand.Skip)
            .also { Log.i(TAG, "Timer command executed: skip status=${it.status} phase=${it.phase}") }

    public suspend fun resetTimerBlocking(): TimerState =
        executeCommand(TimerCommand.Reset)
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

    private suspend fun executeCommand(command: TimerCommand): TimerState =
        commandMutex.withLock {
            withContext(Dispatchers.Main) {
                // Acting on the timer from any surface acknowledges (and silences) a ring.
                if (cueEngine.isRinging()) cueEngine.stop()
                val runningAcrossMidnight =
                    currentState.date != historyCacheRepository.getEffectiveDateString() &&
                        currentState.status == TimerState.STATUS_RUNNING
                if (!runningAcrossMidnight) {
                    reconcileDayTransitionIfNeeded(notify = false)
                }
                val before = currentState.copy()
                val event =
                    when (command) {
                        TimerCommand.Toggle ->
                            if (currentState.status == TimerState.STATUS_RUNNING) {
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
                    else ->
                        if (command is TimerCommand.AddTime) {
                            offlineTimer.extend(command.secondsDelta)
                        }
                }
                if (runningAcrossMidnight && currentState.status != TimerState.STATUS_RUNNING) {
                    adoptCurrentDayAfterCrossMidnightCommand()
                }
                if (event != null && didStateChange(before, currentState)) {
                    cueEngine.playManual(event)
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

    private fun didStateChange(
        before: TimerState,
        after: TimerState,
    ): Boolean {
        return before.status != after.status ||
            before.phase != after.phase ||
            doublesDiffer(before.remaining, after.remaining) ||
            doublesDiffer(before.start_time, after.start_time) ||
            before.last_action_time != after.last_action_time ||
            before.tag != after.tag
    }

    private fun doublesDiffer(
        before: Double,
        after: Double,
        tolerance: Double = 0.001,
    ): Boolean = abs(before - after) > tolerance

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
    }

    private suspend fun adoptCurrentDayAfterCrossMidnightCommand() {
        val today = historyCacheRepository.getEffectiveDateString()
        if (currentState.date == today) return

        currentState.date = today
        currentState.completed = historyCacheRepository.getTodayCompletedCount()
        currentState.last_action_time = System.currentTimeMillis() / 1000
        sanitizeState(currentState)
        offlineTimer.updateState(currentState)
        saveCurrentState()
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

    private fun publishCrewCatchUp() {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                crewRepository.republishStaleLocalHistory()
            }.onFailure { error ->
                Log.w(TAG, "Failed Crew catch-up publish", error)
            }
        }
    }

    /**
     * Re-reads today's earned block count from Room. A backup restore writes sessions behind the
     * service's back, so the count it is holding — and showing on the timer, notification, and
     * widget — is stale until it looks again.
     */
    public fun refreshFromHistory() {
        serviceScope.launch { reconcileStateWithHistory() }
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
            serviceAdvertiser.stop()
            phoneServer.stop()
            return
        }

        if (newPort == activePhoneServerPort && phoneServer.isRunning) {
            // Already serving on the right port. Still call advertise() — it is
            // idempotent, and this covers the case where the server survived but
            // mDNS registration previously failed.
            serviceAdvertiser.advertise(activePhoneServerPort)
            return
        }

        Log.d(TAG, "Restarting phone API on port $newPort")
        serviceAdvertiser.stop()
        phoneServer.stop()
        activePhoneServerPort = newPort
        phoneServer = PhoneServer(this, activePhoneServerPort)
        phoneServer.start()
        // start() swallows a failed bind and leaves the server down. Advertising a
        // port nothing is listening on would send clients to a dead socket.
        if (phoneServer.isRunning) {
            serviceAdvertiser.advertise(activePhoneServerPort)
        }
    }

    public fun rotatePairingToken(): String {
        val token = prefs.rotatePairingToken()
        // Force a full stop+start so existing WebSocket clients are disconnected and
        // must re-pair with the new token. restartPhoneServerIfNeeded() skips the
        // restart when the port is unchanged and the server is already running.
        serviceAdvertiser.stop()
        phoneServer.stop()
        if (isPhoneServerServing) {
            phoneServer = PhoneServer(this, activePhoneServerPort)
            phoneServer.start()
            if (phoneServer.isRunning) {
                serviceAdvertiser.advertise(activePhoneServerPort)
            }
        }
        broadcastStateUpdate()
        return token
    }

    public suspend fun stateSnapshot(): TimerState =
        commandMutex.withLock {
            withContext(Dispatchers.Main) {
                currentState.copy()
            }
        }

    public fun setActiveTag(tag: String) {
        serviceScope.launch {
            commandMutex.withLock {
                withContext(Dispatchers.Main) {
                    val isWorkRunning =
                        currentState.phase == TimerState.PHASE_WORK &&
                            (
                                currentState.status == TimerState.STATUS_RUNNING ||
                                    currentState.status == TimerState.STATUS_PAUSED
                            )
                    if (isWorkRunning) return@withContext
                    currentState.tag = tag
                    saveCurrentState()
                    broadcastStateUpdate()
                }
            }
        }
    }

    public fun getConfigPayload(): ConfigPayload {
        return ConfigPayload(
            durations =
                Durations(
                    work = prefs.pomodoroDuration,
                    short_break = prefs.shortBreakDuration,
                    long_break = prefs.longBreakDuration,
                ),
            long_break_after = prefs.longBreakAfter,
            daily_goal = prefs.dailyGoal,
        )
    }

    public suspend fun applyConfigPayload(body: String): TimerState =
        withContext(Dispatchers.Main) {
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

    /**
     * Desk offline-history flush. Validates sessions, writes new ones to Room, refreshes
     * today's completed count, and broadcasts state so UIs and WS clients catch up.
     */
    public suspend fun importSessions(body: String): Map<String, Any> =
        commandMutex.withLock {
            val nowSeconds = System.currentTimeMillis() / 1000L
            val knownStarts = historyCacheRepository.getAllSessionStarts()
            val knownClientIds = prefs.importedClientIds()
            val parsed =
                SessionImportPayloads.parseAndValidate(
                    body = body,
                    nowSeconds = nowSeconds,
                    knownStarts = knownStarts,
                    knownClientIds = knownClientIds,
                )

            val newlyAcceptedClientIds = mutableListOf<String>()
            for (row in parsed.accepted) {
                if (!row.alreadyPresent) {
                    historyCacheRepository.saveLocalSession(row.session)
                }
                newlyAcceptedClientIds += row.clientId
            }
            prefs.markImportedClientIds(newlyAcceptedClientIds)

            withContext(Dispatchers.Main) {
                val today = historyCacheRepository.getEffectiveDateString()
                if (currentState.date == today || currentState.date == null) {
                    currentState.completed = historyCacheRepository.getTodayCompletedCount()
                    currentState.date = today
                    sanitizeState(currentState)
                    offlineTimer.updateState(currentState)
                    saveCurrentState()
                    updateNotification()
                    broadcastStateUpdate()
                } else {
                    // Running across midnight: do not redate, but still push WS so history UIs refresh.
                    broadcastStateUpdate()
                }
            }

            // Any newly imported completed work can affect Crew ranking / achievements.
            if (parsed.accepted.any { !it.alreadyPresent && it.session.type == TimerState.PHASE_WORK }) {
                publishCrewSnapshot("desk session import")
                checkForNewAchievements()
            }

            LinkLog.record(
                "link import from ${(parsed.source ?: "desk").trim().take(32).ifBlank { "desk" }}: " +
                    "accepted=${parsed.accepted.size} rejected=${parsed.rejected.size}",
            )
            mapOf(
                "success" to true,
                "accepted" to parsed.accepted.map { it.clientId },
                "rejected" to
                    parsed.rejected.map { row ->
                        mapOf("client_id" to row.clientId, "error" to row.error)
                    },
            )
        }

    /**
     * Desk hands a live offline (or shorter) timer to the phone under the least-remaining
     * adopt policy: always when phone is stopped; same session always; different live
     * sessions only when desk remaining is strictly less than phone remaining. Otherwise
     * [AdoptResult.Conflict] (HTTP 409). On success the phone owns the sole live clock.
     *
     * Today's [TimerState.completed] always comes from Room after adopt — desk cannot
     * silently inflate daily counts or long-break cadence.
     */
    public suspend fun adoptTimer(body: String): AdoptResult {
        val payload = TimerAdoptPayloads.parse(body)
        // Apply under lock; notify UI after release so stateSnapshot cannot race a mid-broadcast lock.
        val result =
            commandMutex.withLock {
                withContext(Dispatchers.Main) {
                    val reason = TimerAdoptPayloads.adoptReason(currentState, payload)
                    val desk = LinkLog.describe(payload.status, payload.phase, payload.remaining)
                    val phone = LinkLog.describe(currentState.status, currentState.phase, currentState.remaining)
                    if (!TimerAdoptPayloads.canAdopt(currentState, payload)) {
                        LinkLog.record("link adopt desk $desk vs phone $phone: rejected ($reason)")
                        return@withContext AdoptResult.Conflict(currentState.copy())
                    }

                    val next = TimerAdoptPayloads.applyTo(currentState, payload)
                    next.date = historyCacheRepository.getEffectiveDateString()
                    // Room is canonical for today's completed work blocks. Desk completed is
                    // a hint only — never accept a higher desk count without matching imported
                    // sessions (import already refreshed Room before adopt on reconnect).
                    next.completed = historyCacheRepository.getTodayCompletedCount()
                    sanitizeState(next)
                    currentState = next
                    offlineTimer.updateState(currentState)
                    saveCurrentState()
                    LinkLog.record("link adopt desk $desk vs phone $phone: adopted ($reason)")
                    AdoptResult.Success(currentState.copy())
                }
            }
        if (result is AdoptResult.Success) {
            withContext(Dispatchers.Main) {
                updateNotification()
                broadcastStateUpdate()
            }
        }
        return result
    }

    public sealed class AdoptResult {
        public data class Success(val state: TimerState) : AdoptResult()

        public data class Conflict(val state: TimerState) : AdoptResult()
    }

    public suspend fun updateSessionTag(
        startTime: Long,
        tag: String?,
    ) {
        commandMutex.withLock {
            withContext(Dispatchers.IO) {
                historyCacheRepository.updateSessionTag(startTime, tag)
            }
        }
    }

    public suspend fun getLatestCompletedWorkSession(): com.pomo.db.SessionEntity? {
        return commandMutex.withLock {
            withContext(Dispatchers.IO) {
                historyCacheRepository.getLatestCompletedWorkSession()
            }
        }
    }

    private fun getDurationForPhase(phase: String): Double {
        val minutes =
            when (phase) {
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
