// app/src/main/java/com/pomoremote/db/HistoryCacheRepository.kt
package com.pomoremote.db

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Repository that provides offline-first access to history data.
 *
 * Strategy:
 * 1. Return cached data immediately from Room
 * 2. Fetch from server in background
 * 3. Merge server data into Room (server is source of truth)
 * 4. UI observes Room via Flow for automatic updates
 */
class HistoryCacheRepository(context: Context) {

    companion object {
        private const val TAG = "HistoryCacheRepo"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val dao: HistoryDao = AppDatabase.getInstance(context).historyDao()
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Observable flow of all day stats. UI should collect this.
     */
    fun observeDayStats(): Flow<List<DayStatsEntity>> = dao.getAllDayStats()

    /**
     * Observable flow of today's completed count.
     */
    fun observeTodayCompletedCount(dayStartHour: Int): Flow<Int> {
        val date = getEffectiveDateString(dayStartHour)
        return dao.getTodayCompletedCountFlow(date)
    }

    /**
     * Get cached day stats immediately (non-blocking snapshot).
     */
    suspend fun getCachedDayStats(): List<DayStatsEntity> = dao.getAllDayStatsSnapshot()

    /**
     * Get sessions for a specific date.
     */
    suspend fun getSessionsForDate(date: String): List<SessionEntity> =
        dao.getSessionsForDate(date)

    /**
     * Observable flow of sessions for a specific date.
     */
    fun observeSessionsForDate(date: String): Flow<List<SessionEntity>> =
        dao.getSessionsForDateFlow(date)

    /**
     * Save a locally completed session.
     */
    suspend fun saveLocalSession(session: com.pomoremote.models.Session, dayStartHour: Int) {
        val date = getEffectiveDateString(dayStartHour)
        val entity = SessionEntity(
            start = session.start,
            date = date,
            type = session.type,
            duration = session.duration,
            completed = session.completed,
            synced = false
        )
        dao.insertSession(entity)
        updateLocalDayStats(date, entity)
    }

    /**
     * Get today's completed session count (for service/timer logic).
     */
    suspend fun getTodayCompletedCount(dayStartHour: Int): Int {
        val date = getEffectiveDateString(dayStartHour)
        return dao.getTodayCompletedCount(date)
    }

    /**
     * Helper to calculate effective date string (e.g. "2024-01-01").
     */
    fun getEffectiveDateString(dayStartHour: Int): String {
        val calendar = java.util.Calendar.getInstance()
        if (calendar.get(java.util.Calendar.HOUR_OF_DAY) < dayStartHour) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return dateFormat.format(calendar.time)
    }

    // ─── Sync Logic ──────────────────────────────────────────────────────────────

    /**
     * Push unsynced sessions to server, then pull latest history.
     */
    suspend fun syncWithServer(ip: String, port: Int): SyncResult {
        // 1. Push unsynced
        pushUnsyncedSessions(ip, port)

        // 2. Pull latest
        return syncFromServer(ip, port)
    }

    private suspend fun pushUnsyncedSessions(ip: String, port: Int) {
        val unsynced = dao.getUnsyncedSessions()
        if (unsynced.isEmpty()) return

        Log.d(TAG, "Pushing ${unsynced.size} unsynced sessions")
        val url = "http://$ip:$port/api/history/sync"

        // Convert to model for JSON
        val payload = unsynced.map {
            com.pomoremote.models.Session(it.type, it.start, it.duration, it.completed)
        }

        try {
            val json = gson.toJson(payload)
            val body = json.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Mark as synced
                    val startTimes = unsynced.map { it.start }
                    dao.markAsSynced(startTimes)
                    Log.d(TAG, "Successfully pushed sessions")
                } else {
                    Log.w(TAG, "Failed to push sessions: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing sessions", e)
        }
    }

    /**
     * Sync history from server. Returns true if sync succeeded.
     */
    suspend fun syncFromServer(ip: String, port: Int): SyncResult = withContext(Dispatchers.IO) {
        val url = "http://$ip:$port/api/history"
        Log.d(TAG, "Syncing from $url")

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Server returned ${response.code}")
                return@withContext SyncResult.Error("Server error: ${response.code}")
            }

            val json = response.body?.string()
            if (json.isNullOrBlank()) {
                Log.w(TAG, "Empty response from server")
                return@withContext SyncResult.Error("Empty response")
            }

            val serverData = parseServerResponse(json)
            if (serverData.isEmpty()) {
                Log.d(TAG, "No history data from server")
                return@withContext SyncResult.Success(0)
            }

            // Convert to entities
            val now = System.currentTimeMillis()
            val dayStatsEntities = mutableListOf<DayStatsEntity>()
            val sessionEntities = mutableListOf<SessionEntity>()

            // Get existing unsynced sessions to preserve them
            val localUnsynced = dao.getUnsyncedSessions()

            for ((date, dayData) in serverData) {
                // 1. Deduplicate sessions for this day first
                val uniqueDaySessions = mutableListOf<SessionEntity>()

                // Add server sessions
                dayData.sessions.forEach { session ->
                    if (!isFuzzyDuplicate(uniqueDaySessions, session.start, session.duration)) {
                        uniqueDaySessions.add(
                            SessionEntity(
                                date = date,
                                type = session.type,
                                start = session.start,
                                duration = session.duration,
                                completed = session.completed,
                                synced = true
                            )
                        )
                    }
                }

                // Merge local unsynced sessions if they belong to this date
                localUnsynced.filter { it.date == date }.forEach { local ->
                     if (!isFuzzyDuplicate(uniqueDaySessions, local.start, local.duration)) {
                         uniqueDaySessions.add(local)
                     }
                }

                // 2. Calculate stats from the UNIQUE sessions
                val calculatedCompleted = uniqueDaySessions.count { it.type == "work" && it.completed }
                val calculatedWorkMinutes = uniqueDaySessions
                    .filter { it.type == "work" && it.completed }
                    .sumOf { it.duration } / 60
                val calculatedBreakMinutes = uniqueDaySessions
                    .filter { (it.type == "short" || it.type == "long") && it.completed }
                    .sumOf { it.duration } / 60

                dayStatsEntities.add(
                    DayStatsEntity(
                        date = date,
                        completed = calculatedCompleted,
                        workMinutes = calculatedWorkMinutes,
                        breakMinutes = calculatedBreakMinutes,
                        lastUpdated = now
                    )
                )

                // 3. Add to the main list for batch insertion
                sessionEntities.addAll(uniqueDaySessions)
            }

            // Also handle unsynced sessions for dates NOT in server response
            localUnsynced.forEach { local ->
                if (sessionEntities.none { it.start == local.start }) {
                     // If we haven't already added it (via the date loop above), add it now
                     // But we also need a DayStatsEntity for it if one wasn't created
                     if (dayStatsEntities.none { it.date == local.date }) {
                         // Need to create a DayStats entry for this local-only day
                         // This is a simplified case, ideally we'd calculate properly
                         // For now, let's just ensure the session is kept
                         sessionEntities.add(local)
                         // And create a basic stats entry
                         val isWork = local.type == "work"
                         val isBreak = local.type == "short" || local.type == "long"
                         dayStatsEntities.add(DayStatsEntity(
                            date = local.date,
                            completed = if (isWork && local.completed) 1 else 0,
                            workMinutes = if (isWork && local.completed) local.duration / 60 else 0,
                            breakMinutes = if (isBreak && local.completed) local.duration / 60 else 0,
                            lastUpdated = now
                         ))
                     } else {
                         // Stats entity exists (created from server data), but session wasn't in it?
                         // This happens if server had NO data for this day, but we created a stats entry
                         // Wait, the loop iterates over serverData.
                         // If serverData doesn't have the date, we enter this block.
                         // So we just added the stats and session.
                     }
                }
            }

            // Replace all data (server is source of truth)
            dao.replaceAllHistory(dayStatsEntities, sessionEntities)
            Log.d(TAG, "Synced ${dayStatsEntities.size} days, ${sessionEntities.size} sessions")

            SyncResult.Success(dayStatsEntities.size)

        } catch (e: IOException) {
            Log.w(TAG, "Network error during sync", e)
            SyncResult.NetworkError(e.message ?: "Network error")
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "JSON parse error", e)
            SyncResult.Error("Invalid data format")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sync", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseServerResponse(json: String): Map<String, ServerDayEntry> {
        val type = object : TypeToken<Map<String, ServerDayEntry>>() {}.type
        return gson.fromJson(json, type) ?: emptyMap()
    }

    /**
     * Clear all cached history.
     */
    suspend fun clearCache() {
        dao.replaceAllHistory(emptyList(), emptyList())
    }

    // ─── Data Classes for Server Response ────────────────────────────────────────

    data class ServerDayEntry(
        val completed: Int = 0,
        val work_minutes: Int = 0,
        val break_minutes: Int = 0,
        val sessions: List<ServerSession> = emptyList()
    )

    data class ServerSession(
        val type: String = "",
        val start: Long = 0,
        val duration: Int = 0,
        val completed: Boolean = false
    )

    // ─── Sync Result ─────────────────────────────────────────────────────────────

    sealed class SyncResult {
        data class Success(val daysUpdated: Int) : SyncResult()
        data class NetworkError(val message: String) : SyncResult()
        data class Error(val message: String) : SyncResult()

        val isSuccess: Boolean get() = this is Success
    }

    private suspend fun updateLocalDayStats(date: String, session: SessionEntity) {
        val currentStats = dao.getDayStats(date) ?: DayStatsEntity(
            date = date,
            completed = 0,
            workMinutes = 0,
            breakMinutes = 0,
            lastUpdated = System.currentTimeMillis()
        )

        val isWork = session.type == "work"
        val isBreak = session.type == "short" || session.type == "long"

        val newStats = currentStats.copy(
            completed = if (isWork && session.completed) currentStats.completed + 1 else currentStats.completed,
            workMinutes = if (isWork && session.completed) currentStats.workMinutes + (session.duration / 60) else currentStats.workMinutes,
            breakMinutes = if (isBreak && session.completed) currentStats.breakMinutes + (session.duration / 60) else currentStats.breakMinutes,
            lastUpdated = System.currentTimeMillis()
        )

        dao.insertDayStats(newStats)
    }

    /**
     * Check if a session is a duplicate (fuzzy match on start time).
     * Server timestamps might differ slightly from local ones.
     */
    private fun isFuzzyDuplicate(
        existing: List<SessionEntity>,
        start: Long,
        duration: Int
    ): Boolean {
        // Allow 2 second variance
        return existing.any {
            kotlin.math.abs(it.start - start) < 2000 && it.duration == duration
        }
    }
}
