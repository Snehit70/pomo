// app/src/main/java/com/pomo/db/HistoryCacheRepository.kt
package com.pomo.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

public class HistoryCacheRepository(context: Context) {
    public companion object {
        private const val TAG: String = "HistoryCacheRepo"
    }

    private val dao: HistoryDao = AppDatabase.getInstance(context).historyDao()

    public fun observeDayStats(): Flow<List<DayStatsEntity>> = dao.getAllDayStats()

    public fun observeTodayCompletedCount(): Flow<Int> {
        val date = getEffectiveDateString()
        return dao.getTodayCompletedCountFlow(date)
    }

    public suspend fun getCachedDayStats(): List<DayStatsEntity> = dao.getAllDayStatsSnapshot()

    public suspend fun getCachedSessions(): List<SessionEntity> = dao.getAllSessionsSnapshot()

    public suspend fun getHistoryPayload(): Map<String, ServerDayEntry> =
        withContext(Dispatchers.IO) {
            val days = dao.getAllDayStatsSnapshot()
            val sessionsByDate =
                if (days.isEmpty()) {
                    emptyMap()
                } else {
                    // Chunk dates to avoid SQLite's 999 parameter limit on older Android builds
                    days.map { it.date }
                        .chunked(500)
                        .flatMap { chunk -> dao.getSessionsForDates(chunk) }
                        .groupBy { it.date }
                }

            days.associate { day ->
                day.date to
                    ServerDayEntry(
                        completed = day.completed,
                        work_minutes = day.workMinutes,
                        break_minutes = day.breakMinutes,
                        sessions =
                            sessionsByDate[day.date].orEmpty().map {
                                ServerSession(
                                    type = it.type,
                                    start = it.start,
                                    duration = it.duration,
                                    completed = it.completed,
                                )
                            },
                    )
            }
        }

    public suspend fun getSessionsForDate(date: String): List<SessionEntity> = dao.getSessionsForDate(date)

    public suspend fun getSessionByStart(start: Long): SessionEntity? = dao.getSessionByStart(start)

    public suspend fun getAllSessionStarts(): Set<Long> =
        withContext(Dispatchers.IO) {
            dao.getAllSessionStarts().toSet()
        }

    public fun observeSessionsForDate(date: String): Flow<List<SessionEntity>> = dao.getSessionsForDateFlow(date)

    public fun observeAllSessions(): Flow<List<SessionEntity>> = dao.getAllSessionsFlow()

    public fun observeWorkTagUsageCounts(): Flow<Map<String, Int>> =
        dao.observeWorkTagUsageCounts().map { rows -> rows.associate { it.tag to it.usageCount } }

    public suspend fun saveLocalSession(session: com.pomo.models.Session) {
        val segments = splitSessionByCalendarDay(session)
        segments.forEachIndexed { index, segment ->
            val segmentCompleted = session.completed && index == 0
            val entity =
                SessionEntity(
                    start = segment.start,
                    date = segment.date,
                    type = session.type,
                    duration = segment.duration,
                    completed = segmentCompleted,
                    synced = true,
                    tag = if (index == 0) session.tag else null,
                )
            dao.insertSessionWithDayStats(
                date = segment.date,
                session = entity,
                // A block that crosses midnight is filed under the day it started
                // (first segment); later-day segments carry minutes only (ADR-0002).
                countCompletedSession = segmentCompleted,
            )
        }
    }

    public suspend fun getTodayCompletedCount(): Int {
        val date = getEffectiveDateString()
        return dao.getTodayCompletedCount(date)
    }

    public suspend fun updateSessionTag(
        startTime: Long,
        tag: String?,
    ) {
        dao.updateSessionTag(startTime, tag)
    }

    public suspend fun getLatestCompletedWorkSession(): SessionEntity? {
        val date = getEffectiveDateString()
        return dao.getLatestCompletedWorkSession(date)
    }

    public suspend fun getCompletedCountForDate(date: String): Int = dao.getTodayCompletedCount(date)

    public fun getEffectiveDateString(): String {
        val calendar = java.util.Calendar.getInstance()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return dateFormat.format(calendar.time)
    }

    public fun dateStringForEpochSecond(epochSecond: Long): String = dateForEpochSecond(epochSecond)

    private fun splitSessionByCalendarDay(session: com.pomo.models.Session): List<SessionSegment> {
        if (session.duration <= 0) {
            return listOf(SessionSegment(start = session.start, date = dateForEpochSecond(session.start), duration = 0))
        }

        val segments = mutableListOf<SessionSegment>()
        var segmentStart = session.start
        val endExclusive = session.start + session.duration
        while (segmentStart < endExclusive) {
            val nextMidnight = nextLocalMidnightEpochSecond(segmentStart)
            val segmentEnd = minOf(endExclusive, nextMidnight)
            val seconds = (segmentEnd - segmentStart).coerceAtLeast(0)
            segments +=
                SessionSegment(
                    start = segmentStart,
                    date = dateForEpochSecond(segmentStart),
                    duration = ceilSecondsToMinutes(seconds) * 60,
                )
            segmentStart = segmentEnd
        }
        return segments
    }

    private fun dateForEpochSecond(epochSecond: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return dateFormat.format(java.util.Date(epochSecond * 1000L))
    }

    private fun nextLocalMidnightEpochSecond(epochSecond: Long): Long {
        val calendar =
            Calendar.getInstance().apply {
                timeInMillis = epochSecond * 1000L
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        return calendar.timeInMillis / 1000L
    }

    private fun ceilSecondsToMinutes(seconds: Long): Int {
        return ((seconds + 59L) / 60L).toInt()
    }

    public suspend fun clearCache() {
        dao.replaceAllHistory(emptyList(), emptyList())
    }

    public data class ServerDayEntry(
        val completed: Int = 0,
        val work_minutes: Int = 0,
        val break_minutes: Int = 0,
        val sessions: List<ServerSession> = emptyList(),
    )

    public data class ServerSession(
        val type: String = "",
        val start: Long = 0,
        val duration: Int = 0,
        val completed: Boolean = false,
    )

    private data class SessionSegment(
        val start: Long,
        val date: String,
        val duration: Int,
    )
}
