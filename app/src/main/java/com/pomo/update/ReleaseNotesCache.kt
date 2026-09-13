package com.pomo.update

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** One cached release entry as shown on the release notes screen. */
data class ReleaseEntry(
    val versionName: String,
    val releaseNotes: String,
    val publishedAt: String?,
)

/**
 * Release notes survive offline: GitHub releases are immutable in practice, so the
 * screen renders from this cache first and refreshes in the background. Prefs-backed
 * JSON, matching the TagStore persistence style; no Room needed for a changelog.
 */
internal class ReleaseNotesCache internal constructor(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val gson = Gson()

    public fun get(): List<ReleaseEntry> {
        val json = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ReleaseEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    public fun putAll(entries: List<ReleaseEntry>) {
        prefs.edit().putString(PREF_KEY, gson.toJson(entries)).apply()
    }

    private companion object {
        const val PREF_KEY = "pomo_release_notes_cache"
    }
}
