// app/src/main/java/com/pomoremote/db/HistoryDao.kt
package com.pomoremote.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertDayStats(dayStats: DayStatsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAllDayStats(dayStats: List<DayStatsEntity>)

    @Query("DELETE FROM day_stats")
    public suspend fun clearAllDayStats()

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY start ASC")
    public suspend fun getSessionsForDate(date: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY start ASC")
    public fun getSessionsForDateFlow(date: String): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertSession(session: SessionEntity)

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
}
