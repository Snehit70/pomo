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

        engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(WebSockets)
            routing {
                get("/api/status") {
                    if (!call.isAuthorized()) return@get call.rejectUnauthorized()
                    call.respondJson(service.stateSnapshot())
                }

                post("/api/toggle") {
                    if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                    call.respondJson(success(service.toggleTimerBlocking()))
                }

                post("/api/skip") {
                    if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                    call.respondJson(success(service.skipTimerBlocking()))
                }

                post("/api/reset") {
                    if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                    call.respondJson(success(service.resetTimerBlocking()))
                }

                post("/api/extend") {
                    if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                    val secondsDelta = parseAddTimeSeconds(call.receiveText())
                        ?: return@post call.respondBadRequest("invalid add-time delta")
                    call.respondJson(success(service.addTimeBlocking(secondsDelta)))
                }

                get("/api/config") {
                    if (!call.isAuthorized()) return@get call.rejectUnauthorized()
                    call.respondJson(service.getConfigPayload())
                }

                post("/api/config") {
                    if (!call.isAuthorized()) return@post call.rejectUnauthorized()
                    val state = try {
                        service.applyConfigPayload(call.receiveText())
                    } catch (_: Exception) {
                        return@post call.respondBadRequest("invalid config")
                    }
                    call.respondJson(success(state))
                }

                get("/api/history") {
                    if (!call.isAuthorized()) return@get call.rejectUnauthorized()
                    call.respondJson(service.getHistoryPayload())
                }

                webSocket("/ws") {
                    val hello = try {
                        incoming.receive()
                    } catch (_: ClosedReceiveChannelException) {
                        close()
                        return@webSocket
                    }

                    val token = if (hello is Frame.Text) parseHelloToken(hello.readText()) else null
                    if (token != service.pairingToken) {
                        close()
                        return@webSocket
                    }

                    synchronized(sessionsLock) { sessions.add(this) }
                    try {
                        send(Frame.Text(stateMessage()))
                        for (frame in incoming) {
                            if (frame is Frame.Close) break
                        }
                    } finally {
                        synchronized(sessionsLock) { sessions.remove(this) }
                    }
                }
            }
        }.start(wait = false)

        Log.d(TAG, "Phone API listening on port $port")
    }

    public fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
        synchronized(sessionsLock) { sessions.clear() }
    }

    public suspend fun broadcastState() {
        val message = stateMessage()
        val deadSessions = mutableListOf<DefaultWebSocketServerSession>()
        val activeSessions = synchronized(sessionsLock) { sessions.toList() }

        activeSessions.forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                deadSessions.add(session)
            }
        }

        if (deadSessions.isNotEmpty()) {
            synchronized(sessionsLock) {
                sessions.removeAll(deadSessions.toSet())
            }
        }
    }

    private suspend fun stateMessage(): String = gson.toJson(
        mapOf(
            "type" to "state",
            "data" to service.stateSnapshot(),
        ),
    )

    private fun success(state: Any): Map<String, Any> = mapOf("success" to true, "state" to state)

    private fun parseAddTimeSeconds(body: String): Int? {
        val seconds = try {
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
