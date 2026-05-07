package com.pomoremote.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.transition.MaterialFadeThrough
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.pomoremote.MainActivity
import com.pomoremote.R
import com.pomoremote.ui.screens.SettingsItem
import com.pomoremote.ui.screens.SettingsScreen
import com.pomoremote.ui.theme.PomoRemoteTheme

public class SettingsFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val gson = Gson()

    private val scanQrLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val contents = result.data?.getStringExtra("SCAN_RESULT")
            if (contents.isNullOrBlank()) {
                showMessage(R.string.scan_pairing_qr_invalid)
                return@registerForActivityResult
            }
            handleScannedPairingPayload(contents)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        // Apply XML-declared defaults if they haven't been seeded yet.
        PreferenceManager.setDefaultValues(requireContext(), R.xml.preferences, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val items = buildItems()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PomoRemoteTheme {
                    SettingsScreen(sharedPreferences = prefs, items = items)
                }
            }
        }
    }

    private fun buildItems(): List<SettingsItem> = listOf(
        SettingsItem.Section(getString(R.string.category_connection)),
        SettingsItem.IntPref(
            key = "phone_server_port",
            title = getString(R.string.phone_api_port_title),
            summary = getString(R.string.phone_api_port_summary),
            default = 9876,
        ),
        SettingsItem.Action(
            title = getString(R.string.pair_desktop_title),
            summary = getString(R.string.pair_desktop_summary),
            onClick = ::onPairingClick,
        ),
        SettingsItem.Action(
            title = getString(R.string.scan_pairing_qr_title),
            summary = getString(R.string.scan_pairing_qr_summary),
            onClick = ::launchQrScanner,
        ),

        SettingsItem.Section(getString(R.string.category_timer)),
        SettingsItem.IntPref(
            key = "pomodoro_duration",
            title = getString(R.string.pomodoro_duration_title),
            summary = getString(R.string.pomodoro_duration_summary),
            default = 25,
        ),
        SettingsItem.IntPref(
            key = "short_break_duration",
            title = getString(R.string.short_break_title),
            summary = getString(R.string.short_break_summary),
            default = 5,
        ),
        SettingsItem.IntPref(
            key = "long_break_duration",
            title = getString(R.string.long_break_title),
            summary = getString(R.string.long_break_summary),
            default = 15,
        ),

        SettingsItem.Section(getString(R.string.category_goals)),
        SettingsItem.IntPref(
            key = "daily_goal",
            title = getString(R.string.daily_goal_title),
            summary = getString(R.string.daily_goal_summary),
            default = 8,
        ),
        SettingsItem.IntPref(
            key = "day_start_hour",
            title = getString(R.string.day_start_hour_title),
            summary = getString(R.string.day_start_hour_summary),
            default = 3,
        ),

        SettingsItem.Section(getString(R.string.category_notifications)),
        SettingsItem.BoolPref(
            key = "vibrate_enabled",
            title = getString(R.string.vibrate_title),
            summary = getString(R.string.vibrate_summary),
            default = true,
        ),
        SettingsItem.BoolPref(
            key = "sound_enabled",
            title = getString(R.string.sound_title),
            summary = getString(R.string.sound_summary),
            default = true,
        ),

        SettingsItem.Section(getString(R.string.category_info)),
        SettingsItem.Action(
            title = getString(R.string.about_title),
            summary = getString(R.string.about_summary),
            iconRes = R.drawable.ic_info,
            onClick = {
                runCatching { findNavController().navigate(R.id.navigation_about) }
            },
        ),
    )

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val activity = activity as? MainActivity ?: return
        when (key) {
            "daily_goal", "day_start_hour" -> {
                activity.service?.updateDailyGoal()
                activity.service?.syncConfig()
            }
            "pomodoro_duration", "short_break_duration", "long_break_duration",
            "long_break_after", "phone_server_port" -> {
                activity.service?.syncConfig()
            }
        }
    }

    private fun onPairingClick() {
        val service = (activity as? MainActivity)?.service
        if (service == null) {
            showMessage(R.string.pair_desktop_unavailable)
        } else {
            showPairingDialog(service.pairingUrl, service.pairingToken, service.pairingPayload)
        }
    }

    private fun showPairingDialog(url: String, token: String, payload: String) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val qrSize = (220 * density).toInt()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }
        content.addView(labelValueView(R.string.pairing_url_label, url))
        content.addView(labelValueView(R.string.pairing_token_label, token))
        content.addView(labelValueView(R.string.pairing_payload_label, payload))

        createQrBitmap(payload, qrSize)?.let { qr ->
            content.addView(ImageView(ctx).apply {
                setImageBitmap(qr)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                    topMargin = (12 * density).toInt()
                }
                contentDescription = getString(R.string.pair_desktop_title)
            })
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.pair_desktop_title)
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton(R.string.pairing_copy) { _, _ -> copyPairingPayload(payload) }
            .setNegativeButton(R.string.pairing_share) { _, _ -> sharePairingPayload(payload) }
            .setNeutralButton(android.R.string.ok, null)
            .show()
    }

    private fun labelValueView(labelRes: Int, value: String): TextView =
        TextView(requireContext()).apply {
            text = getString(labelRes) + "\n" + value
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
        }

    private fun createQrBitmap(payload: String, size: Int): Bitmap? = try {
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun copyPairingPayload(payload: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.pair_desktop_title), payload))
        showMessage(R.string.pairing_copied)
    }

    private fun sharePairingPayload(payload: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.pairing_share_title)))
    }

    private fun launchQrScanner() {
        val intent = Intent("com.google.zxing.client.android.SCAN").apply {
            putExtra("SCAN_MODE", "QR_CODE_MODE")
        }
        if (intent.resolveActivity(requireContext().packageManager) == null) {
            showMessage(R.string.scan_pairing_qr_missing)
            return
        }
        scanQrLauncher.launch(intent)
    }

    private fun handleScannedPairingPayload(payload: String) {
        val parsed = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull()
        val scannedUrl = parsed?.get("url")?.let { runCatching { it.asString }.getOrNull() }
        val scannedToken = parsed?.get("token")?.let { runCatching { it.asString }.getOrNull() }

        if (scannedUrl.isNullOrBlank() || scannedToken.isNullOrBlank()) {
            showMessage(R.string.scan_pairing_qr_invalid)
            return
        }

        val service = (activity as? MainActivity)?.service
        val message = when {
            service == null -> getString(R.string.scan_pairing_qr_service_unavailable)
            scannedToken == service.pairingToken -> getString(R.string.scan_pairing_qr_match)
            else -> getString(R.string.scan_pairing_qr_other)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.scan_pairing_qr_title)
            .setMessage(message + "\n\n" + scannedUrl)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showMessage(messageRes: Int) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }
}
