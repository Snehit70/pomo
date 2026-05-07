// app/src/main/java/com/pomoremote/db/HistoryDao.kt
package com.pomoremote.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    // ─── Day Stats ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM day_stats ORDER BY date DESC")
    fun getAllDayStats(): Flow<List<DayStatsEntity>>

    @Query("SELECT * FROM day_stats ORDER BY date DESC")
    suspend fun getAllDayStatsSnapshot(): List<DayStatsEntity>

    @Query("SELECT * FROM day_stats WHERE date = :date")
    suspend fun getDayStats(date: String): DayStatsEntity?

    @Query("SELECT * FROM day_stats WHERE date >= :startDate ORDER BY date ASC")
    suspend fun getDayStatsSince(startDate: String): List<DayStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayStats(dayStats: DayStatsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllDayStats(dayStats: List<DayStatsEntity>)

    @Query("DELETE FROM day_stats")
    suspend fun clearAllDayStats()

    // ─── Sessions ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY start ASC")
    suspend fun getSessionsForDate(date: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY start ASC")
    fun getSessionsForDateFlow(date: String): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSessions(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE date = :date")
    suspend fun clearSessionsForDate(date: String)

    @Query("DELETE FROM sessions")
    suspend fun clearAllSessions()

    // ─── Combined Operations ─────────────────────────────────────────────────────

    @Transaction
    suspend fun replaceAllHistory(
        dayStats: List<DayStatsEntity>,
        sessions: List<SessionEntity>
    ) {
        clearAllSessions()
        clearAllDayStats()
        insertAllDayStats(dayStats)
        insertAllSessions(sessions)
    }

    @Transaction
    suspend fun replaceDayHistory(
        date: String,
        dayStats: DayStatsEntity,
        sessions: List<SessionEntity>
    ) {
        clearSessionsForDate(date)
        insertDayStats(dayStats)
        insertAllSessions(sessions)
    }

    // ─── Aggregates ──────────────────────────────────────────────────────────────

    @Query("SELECT SUM(workMinutes) FROM day_stats")
    suspend fun getTotalWorkMinutes(): Int?

    @Query("SELECT SUM(completed) FROM day_stats")
    suspend fun getTotalSessions(): Int?

    @Query("SELECT COUNT(*) FROM day_stats WHERE completed > 0")
    suspend fun getDaysWithActivity(): Int

    @Query("SELECT MAX(lastUpdated) FROM day_stats")
    suspend fun getLastSyncTime(): Long?

    // ─── Sync Logic ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sessions WHERE synced = 0 ORDER BY start ASC")
    suspend fun getUnsyncedSessions(): List<SessionEntity>

    @Query("UPDATE sessions SET synced = 1 WHERE start IN (:startTimes)")
    suspend fun markAsSynced(startTimes: List<Long>)

    @Query("SELECT COUNT(*) FROM sessions WHERE date = :date AND completed = 1 AND type = 'work'")
    fun getTodayCompletedCountFlow(date: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sessions WHERE date = :date AND completed = 1 AND type = 'work'")
    suspend fun getTodayCompletedCount(date: String): Int
}
