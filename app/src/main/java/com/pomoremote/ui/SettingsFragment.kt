package com.pomoremote.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import androidx.navigation.fragment.findNavController
import com.pomoremote.MainActivity
import com.pomoremote.R
import com.google.android.material.transition.MaterialFadeThrough

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<androidx.preference.Preference>("about")?.setOnPreferenceClickListener {
            try {
                findNavController().navigate(R.id.navigation_about)
                true
            } catch (e: Exception) {
                false
            }
        }

        findPreference<androidx.preference.Preference>("pairing_info")?.setOnPreferenceClickListener {
            val service = (activity as? MainActivity)?.service
            val message = service?.pairingPayload
                ?: getString(R.string.pair_desktop_unavailable)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pair_desktop_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "daily_goal" || key == "day_start_hour") {
            (activity as? MainActivity)?.service?.updateDailyGoal()
            (activity as? MainActivity)?.service?.syncConfig()
        } else if (key == "pomodoro_duration" || key == "short_break_duration" ||
                   key == "long_break_duration" || key == "long_break_after" ||
                   key == "phone_server_port") {
            (activity as? MainActivity)?.service?.syncConfig()
        }
    }
}
