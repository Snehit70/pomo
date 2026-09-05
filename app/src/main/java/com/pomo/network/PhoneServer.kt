package com.pomo.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pomo.service.PomodoroService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext

public class PhoneServer(
    private val service: PomodoroService,
    private val port: Int = DEFAULT_PORT,
) {
    private val gson = Gson()
    private val sessions = mutableSetOf<DefaultWebSocketServerSession>()
    private val sessionsLock = Any()
    private val unauthorizedAttemptsLock = Any()
    private val unauthorizedAttempts = mutableListOf<Long>()
    private var engine: ApplicationEngine? = null
    public val isRunning: Boolean
        get() = engine != null

    public fun start() {
        if (engine != null) return

        val newEngine =
            embeddedServer(CIO, host = "0.0.0.0", port = port) {
                install(WebSockets)
                routing {
                    get("/api/status") {
                        if (!call.isAuthorized()) return@get call.rejectUnauthorized()
                        val state = service.stateSnapshot()
                        val serverTime = System.currentTimeMillis() / 1000L
                        call.respondText(
                            PhoneMessages.statusJson(gson, state, serverTime),
                            ContentType.Application.Json,
                        )
                    }

                    post("/api/sessions/import") {
                        if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                        val body = call.receiveText()
                        val result =
                            try {
                                service.importSessions(body)
                            } catch (_: IllegalArgumentException) {
                                return@post call.respondBadRequest("invalid import body")
                            } catch (_: Exception) {
                                return@post call.respondBadRequest("invalid import body")
                            }
                        call.respondJson(result)
                    }

                    post("/api/timer/adopt") {
                        if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                        val body = call.receiveText()
                        val outcome =
                            try {
                                service.adoptTimer(body)
                            } catch (_: IllegalArgumentException) {
                                return@post call.respondBadRequest("invalid adopt body")
                            } catch (_: Exception) {
                                return@post call.respondBadRequest("invalid adopt body")
                            }
                        when (outcome) {
                            is PomodoroService.AdoptResult.Success ->
                                call.respondJson(success(outcome.state))
                            is PomodoroService.AdoptResult.Conflict ->
                                call.respondText(
                                    gson.toJson(
                                        mapOf(
                                            "success" to false,
                                            "error" to "timer_busy",
                                            "state" to outcome.state,
                                        ),
                                    ),
                                    ContentType.Application.Json,
                                    HttpStatusCode.Conflict,
                                )
                        }
                    }

                    post("/api/toggle") {
                        if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                        val state = service.toggleTimerBlocking()
                        LinkLog.record("link cmd toggle → ${LinkLog.describe(state.status, state.phase, state.remaining)}")
                        call.respondJson(success(state))
                    }

                    post("/api/skip") {
                        if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                        val state = service.skipTimerBlocking()
                        LinkLog.record("link cmd skip → ${LinkLog.describe(state.status, state.phase, state.remaining)}")
                        call.respondJson(success(state))
                    }

                    post("/api/reset") {
                        if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                        val state = service.resetTimerBlocking()
                        LinkLog.record("link cmd reset → ${LinkLog.describe(state.status, state.phase, state.remaining)}")
                        call.respondJson(success(state))
                    }

                    post("/api/extend") {
                        if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                        val secondsDelta =
                            parseAddTimeSeconds(call.receiveText())
                                ?: return@post call.respondBadRequest("invalid add-time delta")
                        val state = service.addTimeBlocking(secondsDelta)
                        LinkLog.record("link cmd extend +${secondsDelta}s → ${LinkLog.describe(state.status, state.phase, state.remaining)}")
                        call.respondJson(success(state))
                    }

                    get("/api/config") {
                        if (!call.isAuthorized()) return@get call.rejectUnauthorized()
                        call.respondJson(service.getConfigPayload())
                    }

                    post("/api/config") {
                        if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                        val state =
                            try {
                                service.applyConfigPayload(call.receiveText())
                            } catch (_: Exception) {
                                return@post call.respondBadRequest("invalid config")
                            }
                        LinkLog.record("link config applied")
                        call.respondJson(success(state))
                    }

                    get("/api/history") {
                        if (!call.isAuthorized()) return@get call.rejectUnauthorized()
                        call.respondJson(service.getHistoryPayload())
                    }

                    webSocket("/ws") {
                        val hello =
                            try {
                                incoming.receive()
                            } catch (_: ClosedReceiveChannelException) {
                                close()
                                return@webSocket
                            }

                        val token = if (hello is Frame.Text) parseHelloToken(hello.readText()) else null
                        if (token != service.pairingToken) {
                            LinkLog.record("link rejected (bad token)")
                            close()
                            return@webSocket
                        }

                        LinkLog.record("link connected")
                        synchronized(sessionsLock) { sessions.add(this) }
                        try {
                            send(Frame.Text(stateMessage()))
                            for (frame in incoming) {
                                if (frame is Frame.Close) break
                            }
                        } finally {
                            synchronized(sessionsLock) { sessions.remove(this) }
                            LinkLog.record("link disconnected")
                        }
                    }
                }
            }

        // The phone API is optional. If the port is taken (e.g. a second install
        // serving on the same port) the bind fails — catch it so a non-critical
        // feature can't take the whole service down. The timer keeps running.
        try {
            newEngine.start(wait = false)
            engine = newEngine
            Log.d(TAG, "Phone API listening on port $port")
            LinkLog.record("api listening :$port")
        } catch (e: Exception) {
            Log.w(TAG, "Phone API failed to start on port $port: ${e.message}")
            LinkLog.record("api failed to start :$port")
            runCatching { newEngine.stop(gracePeriodMillis = 0, timeoutMillis = 0) }
            engine = null
        }
    }

    public fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
        synchronized(sessionsLock) { sessions.clear() }
        LinkLog.record("api stopped")
    }

    public suspend fun broadcastState() {
        sendToAll(stateMessage())
    }

    /**
     * Sends a one-shot event frame to every subscribed client.
     *
     * Events describe things that happened, not current state — a hardware
     * client cannot tell a natural phase completion from a manual skip using
     * state snapshots alone. Clients that do not recognise the frame type
     * ignore it, so this is safe to add to an existing protocol.
     */
    public suspend fun broadcastEvent(
        event: String,
        phase: String,
    ) {
        sendToAll(PhoneMessages.event(gson, event, phase))
    }

    private suspend fun sendToAll(message: String) {
        val deadSessions = mutableListOf<DefaultWebSocketServerSession>()
        val activeSessions = synchronized(sessionsLock) { sessions.toList() }

        activeSessions.forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (_: Exception) {
                deadSessions.add(session)
            }
        }

        if (deadSessions.isNotEmpty()) {
            synchronized(sessionsLock) {
                sessions.removeAll(deadSessions.toSet())
            }
        }
    }

    private suspend fun stateMessage(): String {
        val state = service.stateSnapshot()
        val serverTime = System.currentTimeMillis() / 1000L
        return PhoneMessages.state(gson, state, serverTime)
    }

    private fun success(state: Any): Map<String, Any> = mapOf("success" to true, "state" to state)

    private fun parseAddTimeSeconds(body: String): Int? {
        val seconds =
            try {
                val obj = JsonParser.parseString(body).asJsonObject
                when {
                    obj.has("seconds_delta") -> obj.get("seconds_delta")?.asInt
                    obj.has("seconds") -> obj.get("seconds")?.asInt
                    obj.has("minutes") -> obj.get("minutes")?.asInt?.let { it * 60 }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        return seconds?.takeIf { it > 0 }
    }

    private fun parseHelloToken(body: String): String? {
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            if (obj.get("type")?.asString == "hello") obj.get("token")?.asString else null
        } catch (_: Exception) {
            null
        }
    }

    private fun io.ktor.server.application.ApplicationCall.isAuthorized(): Boolean {
        return request.headers["X-Pomo-Token"] == service.pairingToken
    }

    private suspend fun io.ktor.server.application.ApplicationCall.rejectUnauthorized() {
        if (isRateLimited()) {
            return respondText(
                gson.toJson(mapOf("success" to false, "error" to "too many unauthorized requests")),
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
        }
        unauthorized()
    }

    private fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - UNAUTHORIZED_WINDOW_MS
        return synchronized(unauthorizedAttemptsLock) {
            unauthorizedAttempts.removeAll { it < windowStart }
            unauthorizedAttempts.add(now)
            unauthorizedAttempts.size > MAX_UNAUTHORIZED_ATTEMPTS
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.unauthorized() {
        respondText(
            gson.toJson(mapOf("success" to false, "error" to "unauthorized")),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondBadRequest(error: String) {
        respondText(
            gson.toJson(mapOf("success" to false, "error" to error)),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(payload: Any) {
        withContext(Dispatchers.IO) {
            respondText(gson.toJson(payload), ContentType.Application.Json)
        }
    }

    public companion object {
        public const val DEFAULT_PORT: Int = 9876
        private const val MAX_UNAUTHORIZED_ATTEMPTS: Int = 20
        private const val UNAUTHORIZED_WINDOW_MS: Long = 60_000
        private const val TAG: String = "PhoneServer"
    }
}
