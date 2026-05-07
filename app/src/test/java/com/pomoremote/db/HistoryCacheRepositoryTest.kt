package com.pomoremote.db

import androidx.test.core.app.ApplicationProvider
import com.pomoremote.models.Session
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class HistoryCacheRepositoryTest {

    private lateinit var repo: HistoryCacheRepository

    @Before
    public fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        repo = HistoryCacheRepository(ctx)
        // Reset singleton DB state between tests
        runTest { repo.clearCache() }
    }

    @After
    public fun tearDown() {
        runTest { repo.clearCache() }
    }

    @Test
    public fun saveLocalSession_workCompleted_incrementsCompletedAndMinutes(): Unit = runTest {
        val session = Session(type = "work", start = 1_700_000_000L, duration = 1500, completed = true)
        repo.saveLocalSession(session, dayStartHour = 3)

        val count = repo.getTodayCompletedCount(dayStartHour = 3)
        assertEquals(1, count)

        val date = repo.getEffectiveDateString(3)
        val stats = repo.getCachedDayStats().firstOrNull { it.date == date }
        assertNotNull(stats)
        assertEquals(1, stats!!.completed)
        assertEquals(25, stats.workMinutes)
        assertEquals(0, stats.breakMinutes)
    }

    @Test
    public fun saveLocalSession_workIncomplete_doesNotIncrement(): Unit = runTest {
        val session = Session(type = "work", start = 1_700_000_001L, duration = 1500, completed = false)
        repo.saveLocalSession(session, dayStartHour = 3)
        assertEquals(0, repo.getTodayCompletedCount(3))
    }

    @Test
    public fun saveLocalSession_break_addsBreakMinutesOnly(): Unit = runTest {
        val short = Session(type = "short", start = 1_700_000_002L, duration = 300, completed = true)
        repo.saveLocalSession(short, dayStartHour = 3)
        val long = Session(type = "long", start = 1_700_000_003L, duration = 900, completed = true)
        repo.saveLocalSession(long, dayStartHour = 3)

        val date = repo.getEffectiveDateString(3)
        val stats = repo.getCachedDayStats().firstOrNull { it.date == date }
        assertNotNull(stats)
        assertEquals(0, stats!!.completed)
        assertEquals(0, stats.workMinutes)
        assertEquals(20, stats.breakMinutes) // 5 + 15
    }

    @Test
    public fun saveLocalSession_multipleWork_accumulates(): Unit = runTest {
        repeat(3) { i ->
            repo.saveLocalSession(
                Session(type = "work", start = 1_700_000_010L + i, duration = 1500, completed = true),
                dayStartHour = 3,
            )
        }
        assertEquals(3, repo.getTodayCompletedCount(3))
        val date = repo.getEffectiveDateString(3)
        val stats = repo.getCachedDayStats().first { it.date == date }
        assertEquals(75, stats.workMinutes)
    }

    @Test
    public fun getHistoryPayload_includesSessions(): Unit = runTest {
        repo.saveLocalSession(
            Session(type = "work", start = 1_700_000_020L, duration = 1500, completed = true),
            dayStartHour = 3,
        )
        val payload = repo.getHistoryPayload()
        val date = repo.getEffectiveDateString(3)
        val entry = payload[date]
        assertNotNull(entry)
        assertEquals(1, entry!!.completed)
        assertEquals(25, entry.work_minutes)
        assertEquals(1, entry.sessions.size)
        assertEquals("work", entry.sessions[0].type)
    }
}
