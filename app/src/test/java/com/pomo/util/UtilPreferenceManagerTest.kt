package com.pomo.util

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.pomo.timer.TimerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
public class UtilPreferenceManagerTest {

    private lateinit var prefs: UtilPreferenceManager

    @Before
    public fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().apply()
        ctx.getSharedPreferences("pairing_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        prefs = UtilPreferenceManager(ctx)
    }

    @After
    public fun tearDown() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().apply()
        ctx.getSharedPreferences("pairing_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    public fun defaults_areReturnedWhenUnset() {
        assertEquals(25, prefs.pomodoroDuration)
        assertEquals(5, prefs.shortBreakDuration)
        assertEquals(15, prefs.longBreakDuration)
        assertEquals(4, prefs.longBreakAfter)
        assertEquals(8, prefs.dailyGoal)
        assertEquals(9876, prefs.phoneServerPort)
        assertTrue(prefs.isPhoneServerEnabled)
        assertTrue(prefs.isPhoneServerWifiOnly)
        assertTrue(prefs.isVibrateEnabled)
        assertTrue(prefs.isSoundEnabled)
    }

    @Test
    public fun intProps_persistRoundTrip() {
        prefs.pomodoroDuration = 30
        prefs.shortBreakDuration = 7
        prefs.longBreakDuration = 20
        prefs.longBreakAfter = 5
        prefs.dailyGoal = 12

        assertEquals(30, prefs.pomodoroDuration)
        assertEquals(7, prefs.shortBreakDuration)
        assertEquals(20, prefs.longBreakDuration)
        assertEquals(5, prefs.longBreakAfter)
        assertEquals(12, prefs.dailyGoal)
    }

    @Test
    public fun pairingToken_isGeneratedAndStable() {
        val first = prefs.pairingToken
        assertNotNull(first)
        assertTrue("token should be non-trivial length", first.length >= 32)
        val second = prefs.pairingToken
        assertEquals("token must persist between calls", first, second)
    }

    @Test
    public fun crewIdentity_isGeneratedAndStable() {
        val firstPrivate = prefs.crewIdentityPrivateKey
        val firstPublic = prefs.crewIdentityPublicKey

        assertNotNull(firstPrivate)
        assertTrue("private key should be non-trivial length", firstPrivate.length >= 32)
        assertNotNull(firstPublic)
        assertEquals(firstPrivate, prefs.crewIdentityPrivateKey)
        assertEquals(firstPublic, prefs.crewIdentityPublicKey)
    }

    @Test
    public fun pairingToken_differsAcrossInstances() {
        val a = prefs.pairingToken
        // Clear and create again — fresh token expected
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().apply()
        ctx.getSharedPreferences("pairing_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        val newPrefs = UtilPreferenceManager(ctx)
        val b = newPrefs.pairingToken
        assertNotEquals(a, b)
    }

    @Test
    public fun rotatePairingToken_replacesExistingToken() {
        val first = prefs.pairingToken
        val rotated = prefs.rotatePairingToken()

        assertNotEquals(first, rotated)
        assertEquals(rotated, prefs.pairingToken)
    }

    @Test
    public fun pairingToken_migratesLegacyDefaultPrefAndRemovesIt() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("pairing_token", "legacy-token")
            .apply()

        val token = prefs.pairingToken

        assertEquals("legacy-token", token)
        assertNull(PreferenceManager.getDefaultSharedPreferences(ctx).getString("pairing_token", null))
        assertEquals(
            "legacy-token",
            ctx.getSharedPreferences("pairing_prefs", android.content.Context.MODE_PRIVATE)
                .getString("pairing_token", null),
        )
    }

    @Test
    public fun phoneServerPort_clampsOutOfRangeToDefault() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("phone_server_port", "99999").apply()
        assertEquals(9876, prefs.phoneServerPort)

        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("phone_server_port", "0").apply()
        assertEquals(9876, prefs.phoneServerPort)
    }

    @Test
    public fun phoneServerPort_garbledValueFallsBackToDefault() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("phone_server_port", "not-a-number").apply()
        assertEquals(9876, prefs.phoneServerPort)
    }

    @Test
    public fun intProps_garbledValueFallsBackToDefault() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("pomodoro_duration", "abc")
            .putString("daily_goal", "xyz")
            .apply()
        assertEquals(25, prefs.pomodoroDuration)
        assertEquals(8, prefs.dailyGoal)
    }

    @Test
    public fun intProps_rejectInvalidBounds() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("pomodoro_duration", "0")
            .putString("short_break_duration", "0")
            .putString("long_break_duration", "-1")
            .putString("long_break_after", "0")
            .putString("daily_goal", "-1")
            .apply()

        assertEquals(25, prefs.pomodoroDuration)
        assertEquals(5, prefs.shortBreakDuration)
        assertEquals(15, prefs.longBreakDuration)
        assertEquals(4, prefs.longBreakAfter)
        assertEquals(8, prefs.dailyGoal)
    }

    @Test
    public fun intProps_settersSanitizeInvalidBounds() {
        prefs.pomodoroDuration = 0
        prefs.shortBreakDuration = 0
        prefs.longBreakDuration = -1
        prefs.longBreakAfter = 0
        prefs.dailyGoal = -1

        assertEquals(25, prefs.pomodoroDuration)
        assertEquals(5, prefs.shortBreakDuration)
        assertEquals(15, prefs.longBreakDuration)
        assertEquals(4, prefs.longBreakAfter)
        assertEquals(8, prefs.dailyGoal)
    }

    @Test
    public fun timerState_savesAndLoads() {
        val state = TimerState().apply {
            status = TimerState.STATUS_RUNNING
            phase = TimerState.PHASE_WORK
            duration = 1500.0
            remaining = 1234.0
            completed = 3
            goal = 10
            date = "2026-05-07"
        }
        prefs.saveTimerState(state)
        val loaded = prefs.loadTimerState()
        assertNotNull(loaded)
        assertEquals(TimerState.STATUS_RUNNING, loaded!!.status)
        assertEquals(TimerState.PHASE_WORK, loaded.phase)
        assertEquals(1500.0, loaded.duration, 0.001)
        assertEquals(1234.0, loaded.remaining, 0.001)
        assertEquals(3, loaded.completed)
        assertEquals(10, loaded.goal)
        assertEquals("2026-05-07", loaded.date)
    }

    @Test
    public fun timerState_loadReturnsNullWhenAbsent() {
        assertNull(prefs.loadTimerState())
    }

    @Test
    public fun timerState_loadReturnsNullOnGarbledJson() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("saved_timer_state", "{this is not valid json").apply()
        assertNull(prefs.loadTimerState())
    }
}
