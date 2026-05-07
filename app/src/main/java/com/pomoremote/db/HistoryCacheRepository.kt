// app/src/main/java/com/pomoremote/db/HistoryCacheRepository.kt
package com.pomoremote.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository that provides local-first access to canonical phone history data.
 */
class HistoryCacheRepository(context: Context) {

    companion object {
        private const val TAG = "HistoryCacheRepo"
    }

    private val dao: HistoryDao = AppDatabase.getInstance(context).historyDao()

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

    suspend fun getHistoryPayload(): Map<String, ServerDayEntry> = withContext(Dispatchers.IO) {
        dao.getAllDayStatsSnapshot().associate { day ->
            day.date to ServerDayEntry(
                completed = day.completed,
                work_minutes = day.workMinutes,
                break_minutes = day.breakMinutes,
                sessions = dao.getSessionsForDate(day.date).map {
                    ServerSession(
                        type = it.type,
                        start = it.start,
                        duration = it.duration,
                        completed = it.completed
                    )
                }
            )
        }
    }

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
            synced = true
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
}
