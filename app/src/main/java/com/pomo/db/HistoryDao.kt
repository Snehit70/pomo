// app/src/main/java/com/pomo/db/HistoryDao.kt
package com.pomo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.pomo.timer.TimerState
import kotlinx.coroutines.flow.Flow

@Dao
public interface HistoryDao {
    @Query("SELECT * FROM day_stats ORDER BY date DESC")
    public fun getAllDayStats(): Flow<List<DayStatsEntity>>

    @Query("SELECT * FROM day_stats ORDER BY date DESC")
    public suspend fun getAllDayStatsSnapshot(): List<DayStatsEntity>

    @Query("SELECT * FROM day_stats WHERE date = :date")
    public suspend fun getDayStats(date: String): DayStatsEntity?

    @Query("SELECT * FROM day_stats WHERE date >= :startDate ORDER BY date ASC")
    public suspend fun getDayStatsSince(startDate: String): List<DayStatsEntity>

    @Upsert
    public suspend fun insertDayStats(dayStats: DayStatsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAllDayStats(dayStats: List<DayStatsEntity>)

    @Query("DELETE FROM day_stats")
    public suspend fun clearAllDayStats()

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY start ASC")
    public suspend fun getSessionsForDate(date: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE start = :start LIMIT 1")
    public suspend fun getSessionByStart(start: Long): SessionEntity?

    @Query("SELECT start FROM sessions")
    public suspend fun getAllSessionStarts(): List<Long>

    @Query("SELECT * FROM sessions WHERE date IN (:dates) ORDER BY date DESC, start ASC")
    public suspend fun getSessionsForDates(dates: List<String>): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY start ASC")
    public fun getSessionsForDateFlow(date: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY start ASC")
    public fun getAllSessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY start ASC")
    public suspend fun getAllSessionsSnapshot(): List<SessionEntity>

    @Query("SELECT tag, COUNT(*) AS usageCount FROM sessions WHERE type = 'work' AND tag IS NOT NULL AND TRIM(tag) != '' GROUP BY tag")
    public fun observeWorkTagUsageCounts(): Flow<List<TagUsageCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertSession(session: SessionEntity)

    @Transaction
    public suspend fun insertSessionWithDayStats(
        date: String,
        session: SessionEntity,
        countCompletedSession: Boolean = true,
    ) {
        val currentStats =
            getDayStats(date) ?: DayStatsEntity(
                date = date,
                completed = 0,
                workMinutes = 0,
                breakMinutes = 0,
                lastUpdated = System.currentTimeMillis(),
            )

        val isWork = session.type == TimerState.PHASE_WORK
        val isBreak = session.type == TimerState.PHASE_SHORT || session.type == TimerState.PHASE_LONG
        val durationMinutes = (session.duration + 59) / 60

        val newStats =
            currentStats.copy(
                // `completed` gates the earned block count only. Work minutes are
                // time-honest: a partial (skipped) block contributes minutes but no
                // block (ADR-0002).
                completed =
                    if (isWork && session.completed && countCompletedSession) {
                        currentStats.completed + 1
                    } else {
                        currentStats.completed
                    },
                workMinutes = if (isWork) currentStats.workMinutes + durationMinutes else currentStats.workMinutes,
                breakMinutes = if (isBreak && session.completed) currentStats.breakMinutes + durationMinutes else currentStats.breakMinutes,
                lastUpdated = System.currentTimeMillis(),
            )

        insertDayStats(newStats)
        insertSession(session)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAllSessions(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE date = :date")
    public suspend fun clearSessionsForDate(date: String)

    @Query("DELETE FROM sessions")
    public suspend fun clearAllSessions()

    @Transaction
    public suspend fun replaceAllHistory(
        dayStats: List<DayStatsEntity>,
        sessions: List<SessionEntity>,
    ) {
        clearAllSessions()
        clearAllDayStats()
        insertAllDayStats(dayStats)
        insertAllSessions(sessions)
    }

    @Transaction
    public suspend fun replaceDayHistory(
        date: String,
        dayStats: DayStatsEntity,
        sessions: List<SessionEntity>,
    ) {
        clearSessionsForDate(date)
        insertDayStats(dayStats)
        insertAllSessions(sessions)
    }

    @Query("SELECT SUM(workMinutes) FROM day_stats")
    public suspend fun getTotalWorkMinutes(): Int?

    @Query("SELECT SUM(completed) FROM day_stats")
    public suspend fun getTotalSessions(): Int?

    @Query("SELECT COUNT(*) FROM day_stats WHERE completed > 0")
    public suspend fun getDaysWithActivity(): Int

    @Query("SELECT MAX(lastUpdated) FROM day_stats")
    public suspend fun getLastSyncTime(): Long?

    @Query("SELECT * FROM sessions WHERE synced = 0 ORDER BY start ASC")
    public suspend fun getUnsyncedSessions(): List<SessionEntity>

    @Query("UPDATE sessions SET synced = 1 WHERE start IN (:startTimes)")
    public suspend fun markAsSynced(startTimes: List<Long>)

    @Query("SELECT COUNT(*) FROM sessions WHERE date = :date AND completed = 1 AND type = 'work'")
    public fun getTodayCompletedCountFlow(date: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sessions WHERE date = :date AND completed = 1 AND type = 'work'")
    public suspend fun getTodayCompletedCount(date: String): Int

    @Query("UPDATE sessions SET tag = :tag WHERE start = :startTime")
    public suspend fun updateSessionTag(
        startTime: Long,
        tag: String?,
    )

    @Query("SELECT * FROM sessions WHERE date = :date AND type = 'work' AND completed = 1 ORDER BY start DESC LIMIT 1")
    public suspend fun getLatestCompletedWorkSession(date: String): SessionEntity?
}

/** Session count for one tag, used to put frequently chosen timer tags first. */
public data class TagUsageCount(
    val tag: String,
    val usageCount: Int,
)

/**
 * Chunk-safe wrapper for [HistoryDao.markAsSynced].
 * SQLite caps host parameters at 999 on older Android versions; chunking prevents overflow.
 */
public suspend fun HistoryDao.markAsSyncedChunked(
    startTimes: List<Long>,
    chunkSize: Int = 500,
) {
    startTimes.chunked(chunkSize).forEach { markAsSynced(it) }
}
