package com.pomoremote.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.pomoremote.timer.TimerState
import java.security.SecureRandom

class UtilPreferenceManager(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()

    fun saveTimerState(state: TimerState) {
        val json = gson.toJson(state)
        prefs.edit().putString("saved_timer_state", json).apply()
    }

    fun loadTimerState(): TimerState? {
        val json = prefs.getString("saved_timer_state", null) ?: return null
        return try {
            gson.fromJson(json, TimerState::class.java)
        } catch (e: Exception) {
            null
        }
    }

    val phoneServerPort: Int
        get() {
            val portStr = prefs.getString("phone_server_port", "9876")
            return portStr
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: 9876
        }

    val pairingToken: String
        get() {
            val existing = prefs.getString("pairing_token", null)
            if (!existing.isNullOrBlank()) return existing

            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("pairing_token", token).apply()
            return token
        }

    val isVibrateEnabled: Boolean
        get() = prefs.getBoolean("vibrate_enabled", true)

    val isSoundEnabled: Boolean
        get() = prefs.getBoolean("sound_enabled", true)

    var pomodoroDuration: Int
        get() {
            val str = prefs.getString("pomodoro_duration", "25")
            return try { str?.toInt() ?: 25 } catch (e: NumberFormatException) { 25 }
        }
        set(value) {
            prefs.edit().putString("pomodoro_duration", value.toString()).apply()
        }

    var shortBreakDuration: Int
        get() {
            val str = prefs.getString("short_break_duration", "5")
            return try { str?.toInt() ?: 5 } catch (e: NumberFormatException) { 5 }
        }
        set(value) {
            prefs.edit().putString("short_break_duration", value.toString()).apply()
        }

    var longBreakDuration: Int
        get() {
            val str = prefs.getString("long_break_duration", "15")
            return try { str?.toInt() ?: 15 } catch (e: NumberFormatException) { 15 }
        }
        set(value) {
            prefs.edit().putString("long_break_duration", value.toString()).apply()
        }

    var longBreakAfter: Int
        get() {
            val str = prefs.getString("long_break_after", "4")
            return try { str?.toInt() ?: 4 } catch (e: NumberFormatException) { 4 }
        }
        set(value) {
            prefs.edit().putString("long_break_after", value.toString()).apply()
        }

    var dailyGoal: Int
        get() {
            val str = prefs.getString("daily_goal", "8")
            return try { str?.toInt() ?: 8 } catch (e: NumberFormatException) { 8 }
        }
        set(value) {
            prefs.edit().putString("daily_goal", value.toString()).apply()
        }

    var dayStartHour: Int
        get() {
            val str = prefs.getString("day_start_hour", "3")
            return try { str?.toInt() ?: 3 } catch (e: NumberFormatException) { 3 }
        }
        set(value) {
            prefs.edit().putString("day_start_hour", value.toString()).apply()
        }
}
