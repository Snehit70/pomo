package com.pomo.crew

import com.google.gson.Gson

public object CrewSnapshotCodec {
    private val gson = Gson()

    public fun encodePlaintext(snapshot: CrewSnapshot): String = gson.toJson(snapshot)

    public fun decodePlaintext(payload: String): CrewSnapshot? = try {
        val snapshot = gson.fromJson(payload, CrewSnapshot::class.java)
        if (snapshot.crewId.isBlank() || snapshot.identityPublicKey.isBlank()) null else snapshot
    } catch (_: Exception) {
        null
    }
}
