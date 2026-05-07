package com.pomoremote.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pomoremote.service.PomodoroService
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PhoneServer(
    private val service: PomodoroService,
    private val port: Int = DEFAULT_PORT
) {
    private val gson = Gson()
    private val sessions = mutableSetOf<DefaultWebSocketServerSession>()
    private val sessionsMutex = Mutex()
    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return

        engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(WebSockets)
            routing {
                get("/api/status") {
                    if (!call.isAuthorized()) return@get call.unauthorized()
                    call.respondJson(service.stateSnapshot())
                }

                post("/api/toggle") {
                    if (!call.isAuthorized()) return@post call.unauthorized()
                    call.respondJson(success(service.toggleTimerBlocking()))
                }

                post("/api/skip") {
                    if (!call.isAuthorized()) return@post call.unauthorized()
                    call.respondJson(success(service.skipTimerBlocking()))
                }

                post("/api/reset") {
                    if (!call.isAuthorized()) return@post call.unauthorized()
                    call.respondJson(success(service.resetTimerBlocking()))
                }

                post("/api/extend") {
                    if (!call.isAuthorized()) return@post call.unauthorized()
                    val minutes = parseMinutes(call.receiveText())
                        ?: return@post call.respondBadRequest("invalid minutes")
                    call.respondJson(success(service.extendTimerBlocking(minutes)))
                }

                get("/api/config") {
                    if (!call.isAuthorized()) return@get call.unauthorized()
                    call.respondJson(service.getConfigPayload())
                }

                post("/api/config") {
                    if (!call.isAuthorized()) return@post call.unauthorized()
                    val state = try {
                        service.applyConfigPayload(call.receiveText())
                    } catch (_: Exception) {
                        return@post call.respondBadRequest("invalid config")
                    }
                    call.respondJson(success(state))
                }

                get("/api/history") {
                    if (!call.isAuthorized()) return@get call.unauthorized()
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

                    sessionsMutex.withLock { sessions.add(this) }
                    try {
                        send(Frame.Text(stateMessage()))
                        for (frame in incoming) {
                            if (frame is Frame.Close) break
                        }
                    } finally {
                        sessionsMutex.withLock { sessions.remove(this) }
                    }
                }
            }
        }.start(wait = false)

        Log.d(TAG, "Phone API listening on port $port")
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
    }

    suspend fun broadcastState() {
        val message = stateMessage()
        val deadSessions = mutableListOf<DefaultWebSocketServerSession>()
        sessionsMutex.withLock {
            sessions.forEach { session ->
                try {
                    session.send(Frame.Text(message))
                } catch (e: Exception) {
                    deadSessions.add(session)
                }
            }
            sessions.removeAll(deadSessions.toSet())
        }
    }

    private suspend fun stateMessage(): String = gson.toJson(
        mapOf(
            "type" to "state",
            "data" to service.stateSnapshot()
        )
    )

    private fun success(state: Any): Map<String, Any> = mapOf("success" to true, "state" to state)

    private fun parseMinutes(body: String): Int? {
        return try {
            JsonParser.parseString(body).asJsonObject.get("minutes")?.asInt
        } catch (_: Exception) {
            null
        }?.takeIf { it in 1..240 }
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

    private suspend fun io.ktor.server.application.ApplicationCall.unauthorized() {
        respondText(
            gson.toJson(mapOf("success" to false, "error" to "unauthorized")),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized
        )
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondBadRequest(error: String) {
        respondText(
            gson.toJson(mapOf("success" to false, "error" to error)),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest
        )
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(payload: Any) {
        withContext(Dispatchers.IO) {
            respondText(gson.toJson(payload), ContentType.Application.Json)
        }
    }

    companion object {
        const val DEFAULT_PORT = 9876
        private const val TAG = "PhoneServer"
    }
}
