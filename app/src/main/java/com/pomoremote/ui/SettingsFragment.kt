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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import androidx.navigation.fragment.findNavController
import com.pomoremote.MainActivity
import com.pomoremote.R
import com.google.android.material.transition.MaterialFadeThrough
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val gson = Gson()
    private val scanQrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }

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
            if (service == null) {
                showMessage(R.string.pair_desktop_unavailable)
            } else {
                showPairingDialog(service.pairingUrl, service.pairingToken, service.pairingPayload)
            }
            true
        }

        findPreference<androidx.preference.Preference>("scan_pairing_qr")?.setOnPreferenceClickListener {
            launchQrScanner()
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

    private fun showPairingDialog(url: String, token: String, payload: String) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val qrSize = (220 * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        content.addView(labelValueView(R.string.pairing_url_label, url))
        content.addView(labelValueView(R.string.pairing_token_label, token))
        content.addView(labelValueView(R.string.pairing_payload_label, payload))

        val qrBitmap = createQrBitmap(payload, qrSize)
        if (qrBitmap != null) {
            val qrView = ImageView(context).apply {
                setImageBitmap(qrBitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                    topMargin = (12 * density).toInt()
                }
                contentDescription = getString(R.string.pair_desktop_title)
            }
            content.addView(qrView)
        }

        val scrollView = ScrollView(context).apply {
            addView(content)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.pair_desktop_title)
            .setView(scrollView)
            .setPositiveButton(R.string.pairing_copy) { _, _ -> copyPairingPayload(payload) }
            .setNegativeButton(R.string.pairing_share) { _, _ -> sharePairingPayload(payload) }
            .setNeutralButton(android.R.string.ok, null)
            .show()
    }

    private fun labelValueView(labelRes: Int, value: String): TextView {
        return TextView(requireContext()).apply {
            text = getString(labelRes) + "\n" + value
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun createQrBitmap(payload: String, size: Int): Bitmap? {
        return try {
            val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun copyPairingPayload(payload: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.pair_desktop_title), payload))
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
