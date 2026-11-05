package com.pomo.network

import com.google.gson.Gson
import com.pomo.models.Session
import com.pomo.timer.TimerState

/**
 * Parse and validate desk history flush payloads for `POST /api/sessions/import`.
 *
 * Free of Android/Ktor so rules are unit-testable. Callers supply [nowSeconds], already-known
 * session starts, and previously-accepted client ids for idempotency.
 */
public object SessionImportPayloads {
    private val gson = Gson()

    /** Completed offline sessions older than this are rejected as implausible. */
    public const val MAX_START_AGE_SECONDS: Long = 14L * 24L * 60L * 60L

    /**
     * ADR 0002: a desk Skip may send its partial work time as `completed: false`. Shorter
     * partials record nothing on the phone either, so they are rejected here.
     */
    public const val MIN_PARTIAL_WORK_SECONDS: Int = 60

    /** Starts more than this far ahead of phone wall-clock are rejected. */
    public const val MAX_START_FUTURE_SKEW_SECONDS: Long = 5L * 60L

    public data class Result(
        val source: String?,
        val accepted: List<Accepted>,
        val rejected: List<Rejected>,
    )

    public data class Accepted(
        val clientId: String,
        val session: Session,
        /** True when start or client_id already exists — treat as success, skip Room write. */
        val alreadyPresent: Boolean,
    )

    public data class Rejected(
        val clientId: String,
        val error: String,
    )

    public data class WireBody(
        val source: String? = null,
        val sessions: List<WireSession>? = null,
    )

    public data class WireSession(
        val client_id: String? = null,
        val type: String? = null,
        val duration: Int? = null,
        val completed: Boolean? = null,
        val start: Long? = null,
        val tag: String? = null,
    )

    private data class ValidRow(
        val clientId: String,
        val type: String,
        val duration: Int,
        val completed: Boolean,
        val start: Long?,
        val tag: String?,
    )

    public fun parseAndValidate(
        body: String,
        nowSeconds: Long,
        knownStarts: Set<Long> = emptySet(),
        knownClientIds: Set<String> = emptySet(),
    ): Result {
        val wire =
            try {
                gson.fromJson(body, WireBody::class.java)
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid import body", e)
            } ?: throw IllegalArgumentException("import body must be a JSON object")

        val rejected = mutableListOf<Rejected>()
        val valid = mutableListOf<ValidRow>()

        for (item in wire.sessions.orEmpty()) {
            val clientId = item.client_id?.trim().orEmpty()
            if (clientId.isEmpty()) {
                rejected += Rejected(clientId = "", error = "client_id required")
                continue
            }

            val type = item.type?.trim().orEmpty()
            if (type !in ALLOWED_TYPES) {
                rejected += Rejected(clientId = clientId, error = "invalid type")
                continue
            }

            val duration = item.duration
            if (duration == null || duration <= 0) {
                rejected += Rejected(clientId = clientId, error = "duration must be > 0")
                continue
            }

            val completed = item.completed
            if (completed == null) {
                rejected += Rejected(clientId = clientId, error = "completed required")
                continue
            }
            if (!completed) {
                if (type != TimerState.PHASE_WORK) {
                    rejected += Rejected(clientId = clientId, error = "partial must be work")
                    continue
                }
                if (duration < MIN_PARTIAL_WORK_SECONDS) {
                    rejected += Rejected(clientId = clientId, error = "partial under 60s")
                    continue
                }
            }

            val start = item.start
            if (start != null && !isPlausibleStart(start, nowSeconds)) {
                rejected += Rejected(clientId = clientId, error = "start out of range")
                continue
            }

            val tag = item.tag?.trim()?.takeIf { it.isNotEmpty() }
            valid +=
                ValidRow(
                    clientId = clientId,
                    type = type,
                    duration = duration,
                    completed = completed,
                    start = start,
                    tag = tag,
                )
        }

        // Assign missing starts: reverse walk so list order stays chronological (oldest first)
        // and identical durations never share a primary key.
        val usedStarts = knownStarts.toMutableSet()
        val assigned = MutableList<Long?>(valid.size) { valid[it].start }
        var cursor = nowSeconds
        for (i in valid.indices.reversed()) {
            val existing = assigned[i]
            if (existing != null) {
                if (existing < cursor) {
                    cursor = existing
                }
                continue
            }
            var start = cursor - valid[i].duration.toLong()
            while (start in usedStarts || assigned.any { it == start }) {
                start -= 1L
            }
            assigned[i] = start
            cursor = start
        }

        val accepted = mutableListOf<Accepted>()
        val claimedClientIds = knownClientIds.toMutableSet()

        for (i in valid.indices) {
            val row = valid[i]
            val start = assigned[i]
            if (start == null || !isPlausibleStart(start, nowSeconds)) {
                rejected += Rejected(clientId = row.clientId, error = "start out of range")
                continue
            }

            val session =
                Session(
                    type = row.type,
                    start = start,
                    duration = row.duration,
                    completed = row.completed,
                    tag = row.tag,
                )

            val alreadyPresent =
                start in usedStarts || row.clientId in claimedClientIds

            accepted +=
                Accepted(
                    clientId = row.clientId,
                    session = session,
                    alreadyPresent = alreadyPresent,
                )

            if (!alreadyPresent) {
                usedStarts += start
            }
            claimedClientIds += row.clientId
        }

        return Result(
            source = wire.source,
            accepted = accepted,
            rejected = rejected,
        )
    }

    public fun isPlausibleStart(
        start: Long,
        nowSeconds: Long,
    ): Boolean {
        if (start < 0L) return false
        if (start > nowSeconds + MAX_START_FUTURE_SKEW_SECONDS) return false
        if (start < nowSeconds - MAX_START_AGE_SECONDS) return false
        return true
    }

    private val ALLOWED_TYPES: Set<String> =
        setOf(
            TimerState.PHASE_WORK,
            TimerState.PHASE_SHORT,
            TimerState.PHASE_LONG,
        )
}
