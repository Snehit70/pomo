package com.pomo.crew

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pomo.util.UtilPreferenceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

public class LocalCrewRelayStore(context: Context) {
    private val prefs = context.getSharedPreferences("crew_relay_echo", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val transport = CrewRelayTransport(UtilPreferenceManager(context).crewNostrPrivateKey)

    public suspend fun publish(
        crewId: String,
        identityPublicKey: String,
        payload: String,
        relays: List<String>,
    ) {
        val current = loadAll().toMutableMap()
        val key = eventKey(crewId, identityPublicKey)
        current[key] = StoredSnapshot(
            relays = relays,
            payload = payload,
        )
        prefs.edit().putString(SNAPSHOTS_KEY, gson.toJson(current)).apply()
        transport.publish(crewId, payload, relays)
    }

    public suspend fun pull(crewId: String, crewKey: String, relays: List<String>): List<CrewSnapshot> {
        val remoteSnapshots = transport.pull(crewId, relays)
            .mapNotNull { CrewSnapshotCodec.decodeEncrypted(it, crewKey) }
        val localSnapshots = loadAll()
            .filterKeys { it.startsWith("$crewId:") }
            .values
            .mapNotNull { CrewSnapshotCodec.decodeEncrypted(it.payload, crewKey) }
        return remoteSnapshots + localSnapshots
    }

    public fun observe(crewId: String, crewKey: String, relays: List<String>): Flow<CrewSnapshot> =
        transport.observe(crewId, relays)
            .mapNotNull { CrewSnapshotCodec.decodeEncrypted(it, crewKey) }

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
