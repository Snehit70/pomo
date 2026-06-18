package com.pomo.crew

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

public class CrewStore(context: Context) {
    private val prefs = context.getSharedPreferences("crew_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    public fun loadMembership(): CrewMembership? {
        val memberships = loadMemberships()
        val activeCrewId = prefs.getString(ACTIVE_CREW_KEY, null)
        return memberships.firstOrNull { it.crewId == activeCrewId } ?: memberships.firstOrNull()
    }

    public fun loadMemberships(): List<CrewMembership> {
        val json = prefs.getString(MEMBERSHIPS_KEY, null)
        if (json != null) {
            return try {
                val type = object : TypeToken<List<CrewMembership>>() {}.type
                gson.fromJson<List<CrewMembership>>(json, type).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }

        return loadLegacyMembership()?.let { listOf(it) }.orEmpty()
    }

    public fun saveMembership(membership: CrewMembership) {
        val next = loadMemberships()
            .filterNot { it.crewId == membership.crewId }
            .plus(membership)
            .sortedBy { it.crewId }
        saveMemberships(next, activeCrewId = membership.crewId)
    }

    public fun selectCrew(crewId: String): Boolean {
        if (loadMemberships().none { it.crewId == crewId }) return false
        prefs.edit().putString(ACTIVE_CREW_KEY, crewId).apply()
        return true
    }

    public fun leaveCrew(crewId: String): CrewMembership? {
        val existing = loadMemberships()
        val removed = existing.firstOrNull { it.crewId == crewId } ?: return null
        val remaining = existing.filterNot { it.crewId == crewId }
        val activeCrewId = prefs.getString(ACTIVE_CREW_KEY, null)
        val nextActive = when {
            remaining.isEmpty() -> null
            activeCrewId == crewId || activeCrewId == null -> remaining.first().crewId
            else -> activeCrewId
        }
        saveMemberships(remaining, nextActive)
        return removed
    }

    public fun updateDisplayName(displayName: String): List<CrewMembership> {
        val name = displayName.trim().ifBlank { "Me" }
        val updated = loadMemberships().map { it.copy(displayName = name) }
        saveMemberships(updated, prefs.getString(ACTIVE_CREW_KEY, null))
        return updated
    }

    private fun saveMemberships(memberships: List<CrewMembership>, activeCrewId: String?) {
        prefs.edit()
            .putString(MEMBERSHIPS_KEY, gson.toJson(memberships))
            .apply {
                if (activeCrewId == null) {
                    remove(ACTIVE_CREW_KEY)
                } else {
                    putString(ACTIVE_CREW_KEY, activeCrewId)
                }
                if (memberships.isEmpty()) {
                    remove(CURRENT_CREW_KEY)
                }
            }
            .apply()
    }

    private fun loadLegacyMembership(): CrewMembership? {
        val json = prefs.getString(CURRENT_CREW_KEY, null) ?: return null
        return try {
            gson.fromJson(json, CrewMembership::class.java)
        } catch (_: Exception) {
            null
        }
    }

    public companion object {
        private const val CURRENT_CREW_KEY: String = "current_crew"
        private const val MEMBERSHIPS_KEY: String = "memberships"
        private const val ACTIVE_CREW_KEY: String = "active_crew_id"
    }
}
