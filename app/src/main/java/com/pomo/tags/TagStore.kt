package com.pomo.tags

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

public class TagStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    public fun getTags(): List<String> {
        val json = prefs.getString(PREF_KEY_TAGS, null) ?: return DEFAULT_TAGS
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            DEFAULT_TAGS
        }
    }

    public fun setTags(tags: List<String>) {
        val array = JSONArray()
        tags.forEach { array.put(it) }
        prefs.edit().putString(PREF_KEY_TAGS, array.toString()).apply()
    }

    public fun addTag(name: String): List<String> {
        val current = getTags()
        if (current.contains(name)) return current
        return (current + name).also {
            setTags(it)
            getColorSlots(listOf(name))
        }
    }

    public fun removeTag(name: String): List<String> {
        val wasDefault = getDefaultTag() == name
        val result = getTags().filter { it != name }.also { setTags(it) }
        purgeColorSlot(name)
        if (wasDefault) {
            setDefaultTag(DEFAULT_TAGS.first())
        }
        return result
    }

    public fun renameTag(
        oldName: String,
        newName: String,
    ): RenameResult {
        if (oldName == newName) return RenameResult.Success(getTags())
        val current = getTags()
        if (current.contains(newName)) return RenameResult.Duplicate(newName)
        val wasDefault = getDefaultTag() == oldName
        val result = current.map { if (it == oldName) newName else it }.also { setTags(it) }
        renameColorSlot(oldName, newName)
        if (wasDefault) {
            setDefaultTag(newName)
        }
        return RenameResult.Success(result)
    }

    /** Return stable presentation slots for tags, assigning slots only when first encountered. */
    public fun getColorSlots(tags: Collection<String>): Map<String, Int> {
        val slots = readColorSlots().toMutableMap()
        val used = mutableSetOf<Int>()
        var changed = false
        val orderedTags = (getTags() + tags + slots.keys).filter { it.isNotBlank() }.distinct()
        orderedTags.forEach { tag ->
            val existing = slots[tag]
            if (existing == null || existing in used) {
                val replacement = nextColorSlot(used)
                if (existing != replacement) {
                    slots[tag] = replacement
                    changed = true
                }
                used += replacement
            } else {
                used += existing
            }
            if (existing == null) {
                changed = true
            }
        }
        if (changed) writeColorSlots(slots)
        return slots
    }

    public sealed interface RenameResult {
        public data class Success(val tags: List<String>) : RenameResult

        public data class Duplicate(val existingTag: String) : RenameResult
    }

    public fun hasTag(name: String): Boolean = getTags().contains(name)

    public fun getDefaultTag(): String {
        val stored = prefs.getString(PREF_KEY_DEFAULT_TAG, null) ?: return DEFAULT_TAGS.first()
        return if (getTags().contains(stored)) stored else DEFAULT_TAGS.first()
    }

    public fun setDefaultTag(tag: String) {
        prefs.edit().putString(PREF_KEY_DEFAULT_TAG, tag).apply()
    }

    public companion object {
        private const val PREF_KEY_TAGS = "pomo_session_tags"
        private const val PREF_KEY_DEFAULT_TAG = "pomo_default_tag"
        private const val PREF_KEY_TAG_COLOR_SLOTS = "pomo_tag_color_slots"
        internal const val COLOR_SLOT_COUNT = 16
        internal val DEFAULT_TAGS = listOf("Work", "Study", "Personal", "Exercise")
    }

    private fun renameColorSlot(
        oldName: String,
        newName: String,
    ) {
        val slots = getColorSlots(listOf(oldName)).toMutableMap()
        slots[newName] = slots.remove(oldName) ?: slots[newName] ?: 0
        writeColorSlots(slots)
    }

    private fun readColorSlots(): Map<String, Int> {
        val raw = prefs.getString(PREF_KEY_TAG_COLOR_SLOTS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key ->
                json.optInt(key, 0).coerceIn(0, COLOR_SLOT_COUNT - 1)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeColorSlots(slots: Map<String, Int>) {
        val json = JSONObject()
        slots.forEach { (tag, slot) -> json.put(tag, slot) }
        prefs.edit().putString(PREF_KEY_TAG_COLOR_SLOTS, json.toString()).apply()
    }

    private fun nextColorSlot(used: Set<Int>): Int {
        return (0 until COLOR_SLOT_COUNT).firstOrNull { it !in used } ?: 0
    }

    private fun purgeColorSlot(tag: String) {
        val slots = readColorSlots().toMutableMap()
        if (slots.remove(tag) != null) writeColorSlots(slots)
    }
}

/** Sort configured tags by usage, keeping their configured order when counts tie. */
internal fun orderTagsByUsage(
    tags: List<String>,
    usageCounts: Map<String, Int>,
): List<String> =
    tags.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<String>> { usageCounts[it.value] ?: 0 }
                .thenBy { it.index },
        )
        .map { it.value }
