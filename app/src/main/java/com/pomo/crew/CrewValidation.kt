package com.pomo.crew

import android.graphics.BitmapFactory
import java.text.BreakIterator
import java.text.Normalizer
import java.time.LocalDate
import java.util.Base64
import java.util.Locale

public object CrewValidation {
    public const val MAX_DISPLAY_NAME_GRAPHEMES: Int = 24
    public const val MAX_CREW_NAME_GRAPHEMES: Int = 40
    public const val MAX_DAILY_AGGREGATES: Int = 30
    public const val MAX_RELAYS: Int = 8
    public const val MAX_SNAPSHOT_BYTES: Int = 32 * 1024
    public const val MAX_AVATAR_BYTES: Int = 10 * 1024
    public const val MAX_AVATAR_DIMENSION: Int = 1024

    /**
     * Days of dense daily history a snapshot may carry in [CrewStatsExtras]. Covers the 12-week
     * consistency heatmap with room to spare, and costs about a kilobyte on the wire.
     */
    public const val MAX_HISTORY_DAYS: Int = 120
    public const val HOUR_BUCKETS: Int = 24
    public const val WEEKDAY_BUCKETS: Int = 7
    public const val MAX_SHARED_TAGS: Int = 8

    public fun normalizeDisplayName(value: String): String? = normalizeName(value, MAX_DISPLAY_NAME_GRAPHEMES)

    public fun normalizeCrewName(value: String): String? = normalizeName(value, MAX_CREW_NAME_GRAPHEMES)

    public fun isValidSnapshot(snapshot: CrewSnapshot): Boolean {
        if (snapshot.version != CrewDefaults.PROTOCOL_VERSION) return false
        if (!isLowerHex(snapshot.crewId, expectedLength = 32)) return false
        if (!isLowerHex(snapshot.identityPublicKey, expectedLength = 64)) return false
        if (normalizeDisplayName(snapshot.displayName) != snapshot.displayName) return false
        if (!isValidAvatar(snapshot.avatarBase64)) return false
        if (snapshot.allTimeFocusMinutes < 0 || snapshot.currentStreak < 0) return false
        if (snapshot.publishedAtEpochSeconds <= 0L || snapshot.lastFocusedAtEpochSeconds < 0L) return false
        if (snapshot.utcOffsetMinutes !in MIN_UTC_OFFSET_MINUTES..MAX_UTC_OFFSET_MINUTES) return false
        if (!isIsoDate(snapshot.localDate)) return false
        if (snapshot.dailyAggregates.size > MAX_DAILY_AGGREGATES) return false
        if (snapshot.dailyAggregates.map { it.localDate }.distinct().size != snapshot.dailyAggregates.size) return false
        if (snapshot.dailyAggregates.any { aggregate ->
                !isIsoDate(aggregate.localDate) ||
                    aggregate.focusMinutes < 0 ||
                    aggregate.completedWorkBlocks < 0
            }
        ) {
            return false
        }
        if (snapshot.dailyAggregates != snapshot.dailyAggregates.sortedByDescending { it.localDate }) return false
        return isValidStatsExtras(snapshot.stats)
    }

    private fun isValidAvatar(value: String?): Boolean {
        if (value == null) return true
        if (value.isBlank()) return false
        return runCatching {
            val bytes = Base64.getDecoder().decode(value)
            if (bytes.size > MAX_AVATAR_BYTES) return@runCatching false
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth <= MAX_AVATAR_DIMENSION && options.outHeight <= MAX_AVATAR_DIMENSION
        }.getOrDefault(false)
    }

    /**
     * Optional extras are validated only when present: a snapshot from a build that never sends
     * them is still a perfectly good snapshot, and a malformed one must not cost us the member.
     */
    private fun isValidStatsExtras(stats: CrewStatsExtras?): Boolean {
        if (stats == null) return true
        if (!isValidBuckets(stats.hourBuckets, HOUR_BUCKETS)) return false
        if (!isValidBuckets(stats.weekdayBuckets, WEEKDAY_BUCKETS)) return false
        if (stats.allTimeWorkBlocks != null && stats.allTimeWorkBlocks < 0) return false
        if (stats.allTimeActiveDays != null && stats.allTimeActiveDays < 0) return false
        if (stats.bestStreak != null && stats.bestStreak < 0) return false
        if (stats.firstFocusLocalDate != null && !isIsoDate(stats.firstFocusLocalDate)) return false
        if (stats.bestDayLocalDate != null && !isIsoDate(stats.bestDayLocalDate)) return false
        if (stats.bestWeekStartDate != null && !isIsoDate(stats.bestWeekStartDate)) return false
        if (stats.bestMonthStartDate != null && !isIsoDate(stats.bestMonthStartDate)) return false
        if (listOf(
                stats.bestDayFocusMinutes,
                stats.bestDayWorkBlocks,
                stats.bestWeekFocusMinutes,
                stats.bestWeekWorkBlocks,
                stats.bestMonthFocusMinutes,
                stats.bestMonthWorkBlocks,
            ).any { it != null && it < 0 }
        ) {
            return false
        }
        return isValidHistory(stats) && isValidTagBreakdown(stats.tagBreakdown)
    }

    private fun isValidTagBreakdown(tags: List<CrewTagAggregate>?): Boolean {
        if (tags == null) return true
        if (tags.size > MAX_SHARED_TAGS + 1 || tags.count { it.isOther } > 1) return false
        val namedTags = tags.filterNot { it.isOther }
        if (namedTags.size > MAX_SHARED_TAGS) return false
        if (namedTags.any { it.tag.isBlank() || it.tag.length > 24 }) return false
        if (namedTags.map { it.tag }.distinct().size != namedTags.size) return false
        if (tags.any { it.isOther && it.tag.isNotEmpty() }) return false
        return tags.all { tag ->
            listOf(tag.today, tag.yesterday, tag.sevenDays, tag.thirtyDays, tag.allTime).all { range ->
                range.sessions >= 0 && range.focusMinutes >= 0
            }
        }
    }

    private fun isValidHistory(stats: CrewStatsExtras): Boolean {
        val minutes = stats.historyFocusMinutes
        val blocks = stats.historyWorkBlocks
        val start = stats.historyStartDate
        // The three history fields only mean anything together, so treat a partial set as absent.
        if (minutes == null && blocks == null && start == null) return true
        if (minutes == null || blocks == null || start == null) return false
        if (!isIsoDate(start)) return false
        if (minutes.size != blocks.size) return false
        if (minutes.size > MAX_HISTORY_DAYS) return false
        return minutes.all { it >= 0 } && blocks.all { it >= 0 }
    }

    private fun isValidBuckets(
        buckets: List<Int>?,
        expectedSize: Int,
    ): Boolean {
        if (buckets == null) return true
        return buckets.size == expectedSize && buckets.all { it >= 0 }
    }

    public fun isLowerHex(
        value: String,
        expectedLength: Int,
    ): Boolean = value.length == expectedLength && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun normalizeName(
        value: String,
        maxGraphemes: Int,
    ): String? {
        val normalized =
            Normalizer.normalize(value, Normalizer.Form.NFC)
                .trim()
                .replace(WHITESPACE, " ")
        if (normalized.isBlank()) return null
        if (normalized.any(::isUnsafeNameCharacter)) return null
        if (graphemeCount(normalized) > maxGraphemes) return null
        return normalized
    }

    private fun graphemeCount(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        var count = 0
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            val next = iterator.next()
            if (next != BreakIterator.DONE) count += 1
            boundary = next
        }
        return count
    }

    private fun isUnsafeNameCharacter(character: Char): Boolean {
        val type = Character.getType(character)
        return character == '\n' ||
            character == '\r' ||
            type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            character in BIDI_OVERRIDES
    }

    private fun isIsoDate(value: String): Boolean = runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)

    private val WHITESPACE: Regex = Regex("\\s+")
    private val BIDI_OVERRIDES: Set<Char> =
        setOf(
            '\u202A',
            '\u202B',
            '\u202D',
            '\u202E',
            '\u202C',
            '\u2066',
            '\u2067',
            '\u2068',
            '\u2069',
        )
    private const val MIN_UTC_OFFSET_MINUTES: Int = -18 * 60
    private const val MAX_UTC_OFFSET_MINUTES: Int = 18 * 60
}
