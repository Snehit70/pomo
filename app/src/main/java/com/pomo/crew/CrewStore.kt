package com.pomo.crew

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

public class CrewStore internal constructor(
    context: Context,
    private val injectedCipher: CrewStoreCipher?,
) {
    public constructor(context: Context) : this(context, null)

    private val prefs =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cipher: CrewStoreCipher by lazy { injectedCipher ?: CrewSecretCipher() }

    init {
        archiveLegacyMembershipsIfNeeded()
    }

    public fun loadMembership(): CrewMembership? {
        val memberships = loadMemberships()
        val activeCrewId = prefs.getString(ACTIVE_CREW_KEY, null)
        return memberships.firstOrNull { it.crewId == activeCrewId } ?: memberships.firstOrNull()
    }

    public fun loadMemberships(): List<CrewMembership> {
        val encrypted = prefs.getString(SECURE_MEMBERSHIPS_KEY, null) ?: return emptyList()
        val json = cipher.decryptString(encrypted) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<CrewMembership>>() {}.type
            gson.fromJson<List<CrewMembership>>(json, type).orEmpty()
                .filter { it.protocolVersion == CrewDefaults.PROTOCOL_VERSION && !it.isArchived }
        }.getOrDefault(emptyList())
    }

    public fun loadArchivedMemberships(): List<CrewMembership> {
        val json = prefs.getString(ARCHIVED_MEMBERSHIPS_KEY, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ArchivedMembership>>() {}.type
            gson.fromJson<List<ArchivedMembership>>(json, type).orEmpty().map { archived ->
                CrewMembership(
                    crewId = archived.crewId,
                    crewName = archived.crewName,
                    joinCode = "",
                    relays = emptyList(),
                    key = "",
                    displayName = archived.displayName,
                    protocolVersion = 1,
                    isArchived = true,
                )
            }
        }.getOrDefault(emptyList())
    }

    public fun saveMembership(membership: CrewMembership) {
        require(membership.protocolVersion == CrewDefaults.PROTOCOL_VERSION && !membership.isArchived)
        val next =
            loadMemberships()
                .filterNot { it.crewId == membership.crewId }
                .plus(membership)
                .sortedBy { it.crewId }
        saveMemberships(next, activeCrewId = membership.crewId)
    }

    public fun replaceMemberships(memberships: List<CrewMembership>) {
        require(memberships.all { it.protocolVersion == CrewDefaults.PROTOCOL_VERSION && !it.isArchived })
        val next = memberships.distinctBy { it.crewId }.sortedBy { it.crewId }
        val active =
            activeCrewId()?.takeIf { id -> next.any { it.crewId == id } }
                ?: next.firstOrNull()?.crewId
        saveMemberships(next, active)
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
        val nextActive =
            when {
                remaining.isEmpty() -> null
                activeCrewId == crewId || activeCrewId == null -> remaining.first().crewId
                else -> activeCrewId
            }
        saveMemberships(remaining, nextActive)
        return removed
    }

    public fun activeCrewId(): String? = prefs.getString(ACTIVE_CREW_KEY, null)

    /**
     * Adds crews from a backup that this device is not already in, and returns how many were new.
     * A crew the device already holds is left untouched: its membership carries the display name
     * currently in use, which is fresher than whatever the backup froze.
     */
    public fun mergeMemberships(
        memberships: List<CrewMembership>,
        preferredActiveCrewId: String?,
    ): Int {
        val existing = loadMemberships()
        val existingIds = existing.map { it.crewId }.toSet()
        val added =
            memberships
                .filter { it.protocolVersion == CrewDefaults.PROTOCOL_VERSION && !it.isArchived }
                .distinctBy { it.crewId }
                .filterNot { it.crewId in existingIds }
        if (added.isEmpty()) return 0
        val next = (existing + added).sortedBy { it.crewId }
        val active =
            activeCrewId()?.takeIf { id -> next.any { it.crewId == id } }
                ?: preferredActiveCrewId?.takeIf { id -> next.any { it.crewId == id } }
                ?: next.first().crewId
        saveMemberships(next, active)
        return added.size
    }

    public fun updateDisplayName(displayName: String): List<CrewMembership> {
        val name = CrewValidation.normalizeDisplayName(displayName) ?: return loadMemberships()
        val updated = loadMemberships().map { it.copy(displayName = name) }
        saveMemberships(updated, prefs.getString(ACTIVE_CREW_KEY, null))
        return updated
    }

    private fun saveMemberships(
        memberships: List<CrewMembership>,
        activeCrewId: String?,
    ) {
        val encrypted = cipher.encryptString(gson.toJson(memberships))
        prefs.edit()
            .putString(SECURE_MEMBERSHIPS_KEY, encrypted)
            .apply {
                if (activeCrewId == null) remove(ACTIVE_CREW_KEY) else putString(ACTIVE_CREW_KEY, activeCrewId)
            }
            .commit()
    }

    private fun archiveLegacyMembershipsIfNeeded() {
        if (prefs.contains(LEGACY_ARCHIVE_COMPLETE_KEY)) return
        val legacyJson =
            prefs.getString(LEGACY_MEMBERSHIPS_KEY, null)
                ?: prefs.getString(LEGACY_CURRENT_CREW_KEY, null)?.let { "[$it]" }
        val archived =
            legacyJson?.let { json ->
                runCatching {
                    val type = object : TypeToken<List<LegacyMembership>>() {}.type
                    gson.fromJson<List<LegacyMembership>>(json, type).orEmpty()
                        .filter { it.crewId.isNotBlank() }
                        .map { legacy ->
                            ArchivedMembership(
                                crewId = legacy.crewId,
                                crewName = "Archived Crew ${legacy.crewId.take(6)}",
                                displayName = legacy.displayName.ifBlank { "Me" },
                            )
                        }
                }.getOrDefault(emptyList())
            }.orEmpty()
        prefs.edit()
            .putString(ARCHIVED_MEMBERSHIPS_KEY, gson.toJson(archived))
            .putBoolean(LEGACY_ARCHIVE_COMPLETE_KEY, true)
            .remove(LEGACY_MEMBERSHIPS_KEY)
            .remove(LEGACY_CURRENT_CREW_KEY)
            .remove(ACTIVE_CREW_KEY)
            .commit()
    }

    private data class LegacyMembership(
        val crewId: String = "",
        val displayName: String = "",
    )

    private data class ArchivedMembership(
        val crewId: String,
        val crewName: String,
        val displayName: String,
    )

    private companion object {
        private const val PREFS_NAME: String = "crew_prefs"
        private const val SECURE_MEMBERSHIPS_KEY: String = "memberships_v2_encrypted"
        private const val ARCHIVED_MEMBERSHIPS_KEY: String = "memberships_v1_archive"
        private const val LEGACY_ARCHIVE_COMPLETE_KEY: String = "memberships_v1_archive_complete"
        private const val ACTIVE_CREW_KEY: String = "active_crew_id"
        private const val LEGACY_CURRENT_CREW_KEY: String = "current_crew"
        private const val LEGACY_MEMBERSHIPS_KEY: String = "memberships"
    }
}

internal interface CrewStoreCipher {
    public fun encryptString(plaintext: String): String

    public fun decryptString(envelope: String): String?
}
