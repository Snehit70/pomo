package com.pomo.network

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session-scoped activity log for desk-link traffic (phone API + WebSocket).
 *
 * Answers "when did the plugin connect, what did it ask for, and what did the
 * timer pick and why" without digging through logcat. Bounded in-memory ring,
 * so it can never grow storage; the Settings screen shows it with Copy/Share.
 * Never records tokens, URLs, or request bodies.
 */
public object LinkLog {
    public const val CAPACITY: Int = 200

    private val lock = Any()
    private val entries = ArrayDeque<Pair<Long, String>>()
    private val whitespace = Regex("\\s+")

    public fun record(message: String) {
        val clean = message.replace(whitespace, " ").trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(System.currentTimeMillis() to clean)
        }
    }

    /** Oldest-first `HH:mm:ss message` lines for display and export. */
    public fun snapshot(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        synchronized(lock) {
            return entries.joinToString("\n") { (epoch, message) ->
                "${time.format(Date(epoch))} $message"
            }
        }
    }

    public fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** Compact timer summary, e.g. `running 15:00 work`. */
    public fun describe(
        status: String,
        phase: String,
        remainingSeconds: Double,
    ): String {
        val total = remainingSeconds.toInt().coerceAtLeast(0).coerceAtMost(59999)
        val mm = (total / 60).toString().padStart(2, '0')
        val ss = (total % 60).toString().padStart(2, '0')
        return "$status $mm:$ss $phase"
    }
}
