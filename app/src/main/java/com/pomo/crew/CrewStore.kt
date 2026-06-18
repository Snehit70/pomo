package com.pomo.crew

import android.content.Context
import com.google.gson.Gson

public class CrewStore(context: Context) {
    private val prefs = context.getSharedPreferences("crew_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    public fun loadMembership(): CrewMembership? {
        val json = prefs.getString(CURRENT_CREW_KEY, null) ?: return null
        return try {
            gson.fromJson(json, CrewMembership::class.java)
        } catch (_: Exception) {
            null
        }
    }

    public fun saveMembership(membership: CrewMembership) {
        prefs.edit().putString(CURRENT_CREW_KEY, gson.toJson(membership)).apply()
    }

    public companion object {
        private const val CURRENT_CREW_KEY: String = "current_crew"
    }
}
