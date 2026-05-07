package com.pomoremote.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class HistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: HistoryDao

    @Before
    public fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.historyDao()
    }

    @After
    public fun tearDown() {
        db.close()
    }

    @Test
    public fun insertAndRead_dayStats_roundTrip(): Unit = runTest {
        val day = DayStatsEntity("2026-05-07", completed = 4, workMinutes = 100, breakMinutes = 20)
        dao.insertDayStats(day)
        val read = dao.getDayStats("2026-05-07")
        assertNotNull(read)
        assertEquals(4, read!!.completed)
        assertEquals(100, read.workMinutes)
    }

    @Test
    public fun getDayStats_missingKey_returnsNull(): Unit = runTest {
        assertNull(dao.getDayStats("9999-01-01"))
    }

    @Test
    public fun replaceAllHistory_clearsAndRepopulates(): Unit = runTest {
        dao.insertDayStats(DayStatsEntity("2026-05-01", 1, 25, 5))
        dao.insertSession(SessionEntity(start = 1L, date = "2026-05-01", type = "work", duration = 1500, completed = true))

        val newDays = listOf(
            DayStatsEntity("2026-05-06", 2, 50, 10),
            DayStatsEntity("2026-05-07", 3, 75, 15),
        )
        val newSessions = listOf(
            SessionEntity(start = 100L, date = "2026-05-06", type = "work", duration = 1500, completed = true),
            SessionEntity(start = 200L, date = "2026-05-07", type = "work", duration = 1500, completed = true),
        )
        dao.replaceAllHistory(newDays, newSessions)

        val all = dao.getAllDayStatsSnapshot()
        assertEquals(2, all.size)
        assertNull(dao.getDayStats("2026-05-01"))
        assertEquals(0, dao.getSessionsForDate("2026-05-01").size)
        assertEquals(1, dao.getSessionsForDate("2026-05-07").size)
    }

    @Test
    public fun aggregates_returnExpectedSums(): Unit = runTest {
        dao.insertAllDayStats(
            listOf(
                DayStatsEntity("2026-05-05", completed = 2, workMinutes = 50, breakMinutes = 10),
                DayStatsEntity("2026-05-06", completed = 0, workMinutes = 0, breakMinutes = 0),
                DayStatsEntity("2026-05-07", completed = 5, workMinutes = 125, breakMinutes = 25),
            ),
        )
        assertEquals(175, dao.getTotalWorkMinutes())
        assertEquals(7, dao.getTotalSessions())
        assertEquals(2, dao.getDaysWithActivity())
    }

    @Test
    public fun completedCountForDate_countsOnlyWorkAndCompleted(): Unit = runTest {
        dao.insertDayStats(DayStatsEntity("2026-05-07", 0, 0, 0))
        dao.insertAllSessions(
            listOf(
                SessionEntity(1L, "2026-05-07", "work", 1500, completed = true),
                SessionEntity(2L, "2026-05-07", "work", 1500, completed = true),
                SessionEntity(3L, "2026-05-07", "work", 1500, completed = false), // not counted
                SessionEntity(4L, "2026-05-07", "short", 300, completed = true),  // not work
                SessionEntity(5L, "2026-05-07", "long", 900, completed = true),   // not work
            ),
        )
        assertEquals(2, dao.getTodayCompletedCount("2026-05-07"))
        assertEquals(2, dao.getTodayCompletedCountFlow("2026-05-07").first())
    }

    @Test
    public fun unsynced_returnsOnlySyncedFalse(): Unit = runTest {
        dao.insertDayStats(DayStatsEntity("2026-05-07", 0, 0, 0))
        dao.insertAllSessions(
            listOf(
                SessionEntity(1L, "2026-05-07", "work", 1500, completed = true, synced = true),
                SessionEntity(2L, "2026-05-07", "work", 1500, completed = true, synced = false),
                SessionEntity(3L, "2026-05-07", "short", 300, completed = true, synced = false),
            ),
        )
        val unsynced = dao.getUnsyncedSessions()
        assertEquals(2, unsynced.size)
        assertTrue(unsynced.none { it.synced })
    }

    @Test
    public fun markAsSynced_flipsFlag(): Unit = runTest {
        dao.insertDayStats(DayStatsEntity("2026-05-07", 0, 0, 0))
        dao.insertAllSessions(
            listOf(
                SessionEntity(10L, "2026-05-07", "work", 1500, completed = true, synced = false),
                SessionEntity(20L, "2026-05-07", "work", 1500, completed = true, synced = false),
            ),
        )
        dao.markAsSynced(listOf(10L))
        val unsynced = dao.getUnsyncedSessions()
        assertEquals(1, unsynced.size)
        assertEquals(20L, unsynced.first().start)
    }
}
