package com.pomo.crew

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

public class CrewRelayTransport(
    private val nostrPrivateKey: String,
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .callTimeout(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    public suspend fun publish(
        crewId: String,
        payload: String,
        relays: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        val event = signedEvent(crewId, payload)
        relays.filterValidRelayUrls()
            .map { relay -> publishToRelay(relay, event) }
            .any { it }
    }

    public suspend fun pull(crewId: String, relays: List<String>): List<String> = withContext(Dispatchers.IO) {
        relays.filterValidRelayUrls()
            .flatMap { relay -> pullFromRelay(relay, crewId) }
            .distinct()
    }

    private fun publishToRelay(relay: String, event: JsonObject): Boolean = try {
        val latch = CountDownLatch(1)
        var accepted = false
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(gson.toJson(listOf("EVENT", event)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseArray(text) ?: return
                if (message.firstString() == "OK") {
                    accepted = message.getOrNull(2)?.asBoolean == true
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(relay).build(), listener)
        latch.await(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        socket.cancel()
        accepted
    } catch (_: Exception) {
        false
    }

    private fun pullFromRelay(relay: String, crewId: String): List<String> = try {
        val latch = CountDownLatch(1)
        val payloads = mutableListOf<String>()
        val subscriptionId = "pomo-${UUID.randomUUID()}"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val filter = mapOf(
                    "kinds" to listOf(CrewDefaults.SNAPSHOT_EVENT_KIND),
                    "#d" to listOf(crewId),
                    "limit" to PULL_LIMIT,
                )
                webSocket.send(gson.toJson(listOf("REQ", subscriptionId, filter)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseArray(text) ?: return
                when (message.firstString()) {
                    "EVENT" -> message.getOrNull(2)
                        ?.asJsonObject
                        ?.takeIf { it.get("kind")?.asInt == CrewDefaults.SNAPSHOT_EVENT_KIND }
                        ?.takeIf { event -> event.tags().any { it.firstOrNull() == "d" && it.getOrNull(1) == crewId } }
                        ?.get("content")
                        ?.asString
                        ?.let { payloads.add(it) }
                    "EOSE" -> {
                        webSocket.send(gson.toJson(listOf("CLOSE", subscriptionId)))
                        webSocket.close(1000, null)
                        latch.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(relay).build(), listener)
        latch.await(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        socket.cancel()
        payloads
    } catch (_: Exception) {
        emptyList()
    }

    private fun signedEvent(crewId: String, payload: String): JsonObject {
        val pubkey = CrewNostrKeys.publicKeyHex(nostrPrivateKey)
        val tags = listOf(listOf("d", crewId))
        val createdAt = System.currentTimeMillis() / 1000L
        val eventId = eventId(pubkey, createdAt, tags, payload)
        val signature = CrewNostrKeys.signSchnorr(eventId, nostrPrivateKey)
        return JsonObject().apply {
            addProperty("id", eventId)
            addProperty("pubkey", pubkey)
            addProperty("created_at", createdAt)
            addProperty("kind", CrewDefaults.SNAPSHOT_EVENT_KIND)
            add("tags", gson.toJsonTree(tags))
            addProperty("content", payload)
            addProperty("sig", signature)
        }
    }

    private fun eventId(
        pubkey: String,
        createdAt: Long,
        tags: List<List<String>>,
        payload: String,
    ): String {
        val serialized = gson.toJson(
            listOf(
                0,
                pubkey,
                createdAt,
                CrewDefaults.SNAPSHOT_EVENT_KIND,
                tags,
                payload,
            ),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
            .let { bytes -> with(CrewNostrKeys) { bytes.toHex() } }
    }

    private fun parseArray(text: String): JsonArray? = try {
        JsonParser.parseString(text).asJsonArray
    } catch (_: Exception) {
        null
    }

    private fun JsonArray.firstString(): String? = getOrNull(0)?.asString

    private fun JsonArray.getOrNull(index: Int) = if (index in 0 until size()) get(index) else null

    private fun JsonObject.tags(): List<List<String>> {
        val tags = getAsJsonArray("tags") ?: return emptyList()
        return tags.mapNotNull { tag ->
            runCatching { tag.asJsonArray.map { it.asString } }.getOrNull()
        }
    }

    public companion object {
        public fun filterValidRelayUrls(relays: List<String>): List<String> =
            relays.filter { relay ->
                runCatching {
                    val uri = URI(relay)
                    uri.scheme == "wss" && !uri.host.isNullOrBlank()
                }.getOrDefault(false)
            }.distinct()

        private const val RELAY_TIMEOUT_MS: Long = 5_000L
        private const val PULL_LIMIT: Int = 100
    }

    private fun List<String>.filterValidRelayUrls(): List<String> =
        filterValidRelayUrls(this)
}
