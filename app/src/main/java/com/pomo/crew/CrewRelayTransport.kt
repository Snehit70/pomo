package com.pomo.crew

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

public data class CrewRelayEvent(
    val relayUrl: String,
    val eventId: String,
    val authorPublicKey: String,
    val createdAtEpochSeconds: Long,
    val content: String,
)

public data class CrewRelayResult(
    val relayUrl: String,
    val events: List<CrewRelayEvent>,
    val error: String? = null,
)

public data class CrewRelayPublishResult(
    val relayUrl: String,
    val accepted: Boolean,
    val error: String? = null,
)

public class CrewRelayTransport(
    private val nostrPrivateKey: String,
) {
    private val gson = Gson()
    private val client =
        OkHttpClient.Builder()
            .callTimeout(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    public suspend fun publish(
        crewId: String,
        payload: String,
        relays: List<String>,
    ): Boolean = publishResults(crewId, payload, relays).any { it.accepted }

    public suspend fun publishResults(
        crewId: String,
        payload: String,
        relays: List<String>,
    ): List<CrewRelayPublishResult> =
        coroutineScope {
            val event = withContext(Dispatchers.Default) { signedEvent(crewId, payload) }
            filterValidRelayUrls(relays)
                .map { relay -> async(Dispatchers.IO) { publishToRelay(relay, event) } }
                .awaitAll()
        }

    public suspend fun pull(
        crewId: String,
        relays: List<String>,
    ): List<CrewRelayEvent> =
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            coroutineScope {
                filterValidRelayUrls(relays)
                    .map { relay -> async(Dispatchers.IO) { pullFromRelay(relay, crewId).events } }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.eventId }
            }
        }.orEmpty()

    public fun pullIncrementally(
        crewId: String,
        relays: List<String>,
    ): Flow<CrewRelayResult> =
        channelFlow {
            filterValidRelayUrls(relays).forEach { relay ->
                launch(Dispatchers.IO) {
                    send(pullFromRelay(relay, crewId))
                }
            }
        }

    public fun observe(
        crewId: String,
        relays: List<String>,
    ): Flow<CrewRelayEvent> =
        callbackFlow {
            val subscriptionId = "pomo-${UUID.randomUUID()}"
            val sockets =
                filterValidRelayUrls(relays).mapNotNull { relay ->
                    runCatching {
                        client.newWebSocket(
                            Request.Builder().url(relay).build(),
                            object : WebSocketListener() {
                                override fun onOpen(
                                    webSocket: WebSocket,
                                    response: Response,
                                ) {
                                    webSocket.send(gson.toJson(listOf("REQ", subscriptionId, crewFilter(crewId))))
                                }

                                override fun onMessage(
                                    webSocket: WebSocket,
                                    text: String,
                                ) {
                                    decodeRelayEvent(text, crewId, relay)?.let { trySend(it) }
                                }
                            },
                        )
                    }.getOrNull()
                }

            awaitClose {
                sockets.forEach { socket ->
                    socket.send(gson.toJson(listOf("CLOSE", subscriptionId)))
                    socket.close(1000, null)
                    socket.cancel()
                }
            }
        }

    internal fun signedEvent(
        crewId: String,
        payload: String,
    ): JsonObject {
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

    internal fun decodeRelayEvent(
        text: String,
        crewId: String,
        relayUrl: String,
    ): CrewRelayEvent? {
        val message = parseArray(text) ?: return null
        if (message.firstString() != "EVENT") return null
        val event = message.getOrNull(2)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (event.get("kind")?.asInt != CrewDefaults.SNAPSHOT_EVENT_KIND) return null
        if (event.tags().none { it.firstOrNull() == "d" && it.getOrNull(1) == crewId }) return null
        val id = event.get("id")?.asString ?: return null
        val pubkey = event.get("pubkey")?.asString ?: return null
        val signature = event.get("sig")?.asString ?: return null
        val createdAt = event.get("created_at")?.asLong ?: return null
        val content = event.get("content")?.asString ?: return null
        val tags = event.tags()
        if (!CrewValidation.isLowerHex(id, 64) || !CrewValidation.isLowerHex(pubkey, 64)) return null
        if (!CrewValidation.isLowerHex(signature, 128)) return null
        val expectedId = eventId(pubkey, createdAt, tags, content)
        if (id != expectedId || !CrewNostrKeys.verifySchnorr(id, signature, pubkey)) return null
        return CrewRelayEvent(relayUrl, id, pubkey, createdAt, content)
    }

    private fun publishToRelay(
        relay: String,
        event: JsonObject,
    ): CrewRelayPublishResult =
        try {
            val latch = CountDownLatch(1)
            var accepted = false
            var error: String? = null
            val listener =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(gson.toJson(listOf("EVENT", event)))
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val message = parseArray(text) ?: return
                        if (message.firstString() == "OK") {
                            accepted = message.getOrNull(2)?.asBoolean == true
                            if (!accepted) {
                                error = message.getOrNull(3)?.asString ?: "relay rejected event"
                            }
                            webSocket.close(1000, null)
                            latch.countDown()
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        error = t.message ?: "relay failure"
                        latch.countDown()
                    }
                }
            val socket = client.newWebSocket(Request.Builder().url(relay).build(), listener)
            val completed = latch.await(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed && error == null) error = "timeout"
            socket.cancel()
            CrewRelayPublishResult(relayUrl = relay, accepted = accepted, error = error)
        } catch (exception: Exception) {
            CrewRelayPublishResult(relayUrl = relay, accepted = false, error = exception.message ?: "relay failure")
        }

    private fun pullFromRelay(
        relay: String,
        crewId: String,
    ): CrewRelayResult =
        try {
            val latch = CountDownLatch(1)
            val events = Collections.synchronizedList(mutableListOf<CrewRelayEvent>())
            val subscriptionId = "pomo-${UUID.randomUUID()}"
            var error: String? = null
            val listener =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(gson.toJson(listOf("REQ", subscriptionId, crewFilter(crewId, PULL_LIMIT))))
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val message = parseArray(text) ?: return
                        when (message.firstString()) {
                            "EVENT" -> decodeRelayEvent(text, crewId, relay)?.let(events::add)
                            "EOSE" -> {
                                webSocket.send(gson.toJson(listOf("CLOSE", subscriptionId)))
                                webSocket.close(1000, null)
                                latch.countDown()
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        error = t.message ?: "relay failure"
                        latch.countDown()
                    }
                }
            val socket = client.newWebSocket(Request.Builder().url(relay).build(), listener)
            val completed = latch.await(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed && error == null) error = "timeout"
            socket.cancel()
            CrewRelayResult(relay, events.toList(), error)
        } catch (exception: Exception) {
            CrewRelayResult(relay, emptyList(), exception.message ?: "relay failure")
        }

    private fun eventId(
        pubkey: String,
        createdAt: Long,
        tags: List<List<String>>,
        payload: String,
    ): String {
        val serialized = gson.toJson(listOf(0, pubkey, createdAt, CrewDefaults.SNAPSHOT_EVENT_KIND, tags, payload))
        return MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
            .let { bytes -> with(CrewNostrKeys) { bytes.toHex() } }
    }

    private fun parseArray(text: String): JsonArray? =
        try {
            JsonParser.parseString(text).asJsonArray
        } catch (_: Exception) {
            null
        }

    private fun crewFilter(
        crewId: String,
        limit: Int? = null,
    ): Map<String, Any> =
        buildMap {
            put("kinds", listOf(CrewDefaults.SNAPSHOT_EVENT_KIND))
            put("#d", listOf(crewId))
            if (limit != null) put("limit", limit)
        }

    private fun JsonArray.firstString(): String? = getOrNull(0)?.asString

    private fun JsonArray.getOrNull(index: Int) = if (index in 0 until size()) get(index) else null

    private fun JsonObject.tags(): List<List<String>> {
        val tags = getAsJsonArray("tags") ?: return emptyList()
        return tags.mapNotNull { tag -> runCatching { tag.asJsonArray.map { it.asString } }.getOrNull() }
    }

    public companion object {
        public fun filterValidRelayUrls(relays: List<String>): List<String> =
            relays.filter { relay ->
                runCatching {
                    val uri = URI(relay)
                    uri.scheme == "wss" && !uri.host.isNullOrBlank()
                }.getOrDefault(false)
            }.distinct()

        private const val RELAY_TIMEOUT_MS: Long = 2_750L
        private const val REFRESH_TIMEOUT_MS: Long = 3_000L
        private const val PULL_LIMIT: Int = 1_000
    }
}
