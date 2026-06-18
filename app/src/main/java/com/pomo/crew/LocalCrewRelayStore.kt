package com.pomo.crew

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

public class LocalCrewRelayStore(context: Context) {
    private val prefs = context.getSharedPreferences("crew_relay_echo", Context.MODE_PRIVATE)
    private val gson = Gson()

    public fun publish(snapshot: CrewSnapshot, relays: List<String>) {
        val current = loadAll().toMutableMap()
        val key = eventKey(snapshot.crewId, snapshot.identityPublicKey)
        current[key] = StoredSnapshot(
            relays = relays,
            payload = CrewSnapshotCodec.encodePlaintext(snapshot),
        )
        prefs.edit().putString(SNAPSHOTS_KEY, gson.toJson(current)).apply()
    }

    public fun pull(crewId: String): List<CrewSnapshot> {
        return loadAll()
            .filterKeys { it.startsWith("$crewId:") }
            .values
            .mapNotNull { CrewSnapshotCodec.decodePlaintext(it.payload) }
    }

    private fun loadAll(): Map<String, StoredSnapshot> {
        val json = prefs.getString(SNAPSHOTS_KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, StoredSnapshot>>() {}.type
            gson.fromJson<Map<String, StoredSnapshot>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun eventKey(crewId: String, identityPublicKey: String): String = "$crewId:$identityPublicKey"

    private data class StoredSnapshot(
        val relays: List<String> = emptyList(),
        val payload: String = "",
    )

    private companion object {
        private const val SNAPSHOTS_KEY: String = "snapshots"
    }
}
