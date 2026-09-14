package com.pomo.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.transition.MaterialFadeThrough
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pomo.BuildConfig
import com.pomo.MainActivity
import com.pomo.R
import com.pomo.backup.BackupIdentityConflictException
import com.pomo.backup.BackupRepository
import com.pomo.backup.PomoBackup
import com.pomo.cues.CompletionCueFamily
import com.pomo.cues.StateCueEvent
import com.pomo.tags.TagStore
import com.pomo.ui.screens.SettingsItem
import com.pomo.ui.screens.SettingsScreen
import com.pomo.ui.theme.PomoTheme
import com.pomo.ui.theme.THEME_MODE_PREF_KEY
import com.pomo.ui.theme.ThemeMode
import com.pomo.ui.theme.displayName
import com.pomo.ui.theme.preferenceValue
import com.pomo.ui.theme.themeMode
import com.pomo.util.UtilPreferenceManager
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public class SettingsFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val gson = Gson()

    private val pairingDialog = mutableStateOf<PairingSheetData?>(null)
    private val rotateConfirm = mutableStateOf(false)
    private val scanResult = mutableStateOf<ScanResultData?>(null)
    private val restorePreview = mutableStateOf<RestorePreviewData?>(null)

    private var pendingRestore: PomoBackup? = null

    private val backupRepository: BackupRepository by lazy { BackupRepository(requireContext()) }

    override fun onDestroyView() {
        tagManagerDialog.value = false
        pairingDialog.value = null
        rotateConfirm.value = false
        scanResult.value = null
        restorePreview.value = null
        pendingRestore = null
        super.onDestroyView()
    }

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

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.MIME_TYPE)) { uri ->
            val target = uri ?: return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val written = backupRepository.writeTo(target)
                showMessage(if (written) R.string.backup_export_done else R.string.backup_export_failed)
            }
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val source = uri ?: return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val backup = backupRepository.readFrom(source)
                if (backup == null) {
                    showMessage(R.string.backup_restore_invalid)
                    return@launch
                }
                pendingRestore = backup
                restorePreview.value = backup.toPreview()
            }
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
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var themeMode by remember { mutableStateOf(prefs.themeMode()) }
                var showCuePreviews by rememberSaveable { mutableStateOf(false) }
                val items = remember { buildItems(onCuePreviewsClick = { showCuePreviews = true }) }
                val cuePreviewItems = remember { buildCuePreviewItems() }
                DisposableEffect(prefs) {
                    val listener =
                        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                            if (key == THEME_MODE_PREF_KEY) themeMode = sp.themeMode()
                        }
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
                }
                PomoTheme(mode = themeMode) {
                    BackHandler(enabled = showCuePreviews) { showCuePreviews = false }
                    SettingsScreen(
                        sharedPreferences = prefs,
                        items = if (showCuePreviews) cuePreviewItems else items,
                        title =
                            if (showCuePreviews) {
                                getString(R.string.state_cues_previews_title)
                            } else {
                                getString(R.string.settings_title)
                            },
                        backContentDescription =
                            if (showCuePreviews) {
                                getString(R.string.back_to_settings)
                            } else {
                                getString(R.string.back_to_profile)
                            },
                        showUpdateSection = !showCuePreviews && BuildConfig.APPLICATION_ID == "com.pomo",
                        showSearch = !showCuePreviews,
                        onBack = {
                            if (showCuePreviews) {
                                showCuePreviews = false
                            } else {
                                findNavController().popBackStack()
                            }
                        },
                    )

                    pairingDialog.value?.let { data ->
                        PairingSheet(
                            data = data,
                            onCopy = { copyToClipboard(it) },
                            onShare = { sharePairingPayload(data.payload) },
                            onRotate = {
                                pairingDialog.value = null
                                rotateConfirm.value = true
                            },
                            onScan = {
                                pairingDialog.value = null
                                launchQrScanner()
                            },
                            onDismiss = { pairingDialog.value = null },
                            connectedProvider = { (activity as? MainActivity)?.service?.connectedClientCount() ?: 0 },
                        )
                    }
                    if (rotateConfirm.value) {
                        RotateTokenConfirmDialog(
                            onConfirm = {
                                rotateConfirm.value = false
                                doRotatePairingToken()
                            },
                            onDismiss = { rotateConfirm.value = false },
                        )
                    }
                    scanResult.value?.let { data ->
                        ScanResultDialog(data = data, onDismiss = { scanResult.value = null })
                    }
                    restorePreview.value?.let { data ->
                        RestoreConfirmDialog(
                            data = data,
                            onConfirm = {
                                restorePreview.value = null
                                runRestore()
                            },
                            onDismiss = {
                                restorePreview.value = null
                                pendingRestore = null
                            },
                        )
                    }
                    if (tagManagerDialog.value) {
                        TagManagerDialog(
                            onDismiss = { tagManagerDialog.value = false },
                        )
                    }
                    if (defaultTagPicker.value) {
                        val tagStore = remember { TagStore(requireContext()) }
                        TagPickerSheet(
                            tags = tagStore.getTags(),
                            currentTag = tagStore.getDefaultTag(),
                            onSelect = { tag ->
                                if (tag != null) tagStore.setDefaultTag(tag)
                                defaultTagPicker.value = false
                            },
                            onDismiss = { defaultTagPicker.value = false },
                            showUntagged = false,
                            title = getString(R.string.default_tag_title),
                            subtitle = getString(R.string.default_tag_picker_sub),
                        )
                    }
                }
            }
        }
    }

    private fun buildItems(onCuePreviewsClick: () -> Unit): List<SettingsItem> =
        buildList {
            add(SettingsItem.Section(getString(R.string.category_connection)))
            add(
                SettingsItem.BoolPref(
                    key = "phone_server_enabled",
                    title = getString(R.string.phone_api_enabled_title),
                    summary = getString(R.string.phone_api_enabled_summary),
                    default = true,
                    icon = Icons.Outlined.WifiTethering,
                ),
            )
            add(
                SettingsItem.BoolPref(
                    key = "phone_server_wifi_only",
                    title = getString(R.string.phone_api_wifi_only_title),
                    summary = getString(R.string.phone_api_wifi_only_summary),
                    default = true,
                    icon = Icons.Outlined.Wifi,
                ),
            )
            add(
                SettingsItem.IntPref(
                    key = "phone_server_port",
                    title = getString(R.string.phone_api_port_title),
                    summary = getString(R.string.phone_api_port_summary),
                    default = 9876,
                    min = 1024,
                    max = 65535,
                    allowRandom = true,
                    icon = Icons.Outlined.Lan,
                ),
            )
            add(
                SettingsItem.Action(
                    title = getString(R.string.pair_desktop_title),
                    summary = getString(R.string.pair_desktop_summary),
                    onClick = ::onPairingClick,
                    icon = Icons.Outlined.QrCode2,
                ),
            )
            add(SettingsItem.Section(getString(R.string.category_timer)))
            add(
                SettingsItem.IntPref(
                    key = "pomodoro_duration",
                    title = getString(R.string.pomodoro_duration_title),
                    summary = "",
                    default = 25,
                    min = 1,
                    max = 180,
                    unit = "minutes",
                    presets = listOf(15, 25, 45, 60),
                    icon = Icons.Outlined.Timer,
                ),
            )
            add(
                SettingsItem.IntPref(
                    key = "short_break_duration",
                    title = getString(R.string.short_break_title),
                    summary = "",
                    default = 5,
                    min = 1,
                    max = 60,
                    unit = "minutes",
                    presets = listOf(5, 10, 15, 20),
                    icon = Icons.Outlined.Coffee,
                ),
            )
            add(
                SettingsItem.IntPref(
                    key = "long_break_duration",
                    title = getString(R.string.long_break_title),
                    summary = getString(R.string.long_break_interval_summary),
                    default = 15,
                    min = 1,
                    max = 120,
                    unit = "minutes",
                    presets = listOf(15, 30, 45, 60),
                    icon = Icons.Outlined.Snooze,
                ),
            )

            add(SettingsItem.Section(getString(R.string.category_session_tags)))
            add(
                SettingsItem.Action(
                    title = getString(R.string.session_tags_title),
                    summary = "",
                    summaryProvider = {
                        val tags = TagStore(requireContext()).getTags()
                        if (tags.isEmpty()) getString(R.string.session_tags_summary) else tags.joinToString(" · ")
                    },
                    onClick = ::showTagManager,
                    icon = Icons.AutoMirrored.Outlined.Label,
                ),
            )
            add(
                SettingsItem.Action(
                    title = getString(R.string.default_tag_title),
                    summary = getString(R.string.default_tag_summary),
                    onClick = { defaultTagPicker.value = true },
                    icon = Icons.Outlined.Flag,
                    valueProvider = { TagStore(requireContext()).getDefaultTag() },
                ),
            )

            add(SettingsItem.Section(getString(R.string.category_goals)))
            add(
                SettingsItem.IntPref(
                    key = "daily_goal",
                    title = getString(R.string.daily_goal_title),
                    summary = getString(R.string.daily_goal_summary),
                    default = 8,
                    min = 0,
                    max = 50,
                    unit = "blocks a day",
                    presets = listOf(4, 8, 12),
                    icon = Icons.Outlined.Flag,
                ),
            )

            add(SettingsItem.Section(getString(R.string.category_state_cues)))
            add(SettingsItem.Note(getString(R.string.state_cues_note)))
            val cueChannelsOn: (SharedPreferences) -> Boolean = { prefs ->
                prefs.getBoolean("vibrate_enabled", true) || prefs.getBoolean("sound_enabled", true)
            }
            val cueChannelKeys = listOf("vibrate_enabled", "sound_enabled")
            add(
                SettingsItem.BoolPref(
                    key = "vibrate_enabled",
                    title = getString(R.string.vibrate_title),
                    summary = getString(R.string.vibrate_summary),
                    default = true,
                    icon = Icons.Outlined.Vibration,
                ),
            )
            add(
                SettingsItem.BoolPref(
                    key = "sound_enabled",
                    title = getString(R.string.sound_title),
                    summary = getString(R.string.sound_summary),
                    default = true,
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                ),
            )
            add(
                SettingsItem.BoolPref(
                    key = "stronger_completion_cues",
                    title = getString(R.string.state_cues_stronger_title),
                    summary = getString(R.string.state_cues_stronger_summary),
                    default = false,
                    icon = Icons.Outlined.Bolt,
                    enabledWhen = cueChannelsOn,
                    enabledPrefKeys = cueChannelKeys,
                ),
            )
            add(
                SettingsItem.BoolPref(
                    key = "ring_until_dismissed",
                    title = getString(R.string.ring_until_dismissed_title),
                    summary = getString(R.string.ring_until_dismissed_summary),
                    default = false,
                    icon = Icons.Outlined.NotificationsActive,
                    enabledWhen = cueChannelsOn,
                    enabledPrefKeys = cueChannelKeys,
                ),
            )
            add(
                SettingsItem.ChoicePref(
                    key = "ring_sound",
                    title = getString(R.string.ring_sound_title),
                    summary = getString(R.string.ring_sound_summary),
                    default = UtilPreferenceManager.RING_SOUND_SYSTEM_ALARM,
                    choices =
                        listOf(
                            SettingsItem.Choice(
                                UtilPreferenceManager.RING_SOUND_SYSTEM_ALARM,
                                getString(R.string.ring_sound_system),
                            ),
                            SettingsItem.Choice(
                                UtilPreferenceManager.RING_SOUND_POMO_CUE,
                                getString(R.string.ring_sound_pomo),
                            ),
                        ),
                    icon = Icons.Outlined.MusicNote,
                    visibleWhen = { prefs ->
                        cueChannelsOn(prefs) && prefs.getBoolean("ring_until_dismissed", false)
                    },
                    enabledPrefKeys = listOf("vibrate_enabled", "sound_enabled", "ring_until_dismissed"),
                ),
            )
            add(
                SettingsItem.Action(
                    title = getString(R.string.state_cues_previews_title),
                    summary = getString(R.string.state_cues_previews_summary),
                    onClick = onCuePreviewsClick,
                    icon = Icons.Outlined.GraphicEq,
                    enabledWhen = cueChannelsOn,
                    enabledPrefKeys = cueChannelKeys,
                ),
            )

            add(SettingsItem.Section(getString(R.string.category_theme)))
            add(
                SettingsItem.SegmentedPref(
                    key = THEME_MODE_PREF_KEY,
                    icon = Icons.Outlined.Palette,
                    title = getString(R.string.theme_mode_title),
                    summary = getString(R.string.theme_mode_summary),
                    default = ThemeMode.System.preferenceValue,
                    choices =
                        listOf(
                            SettingsItem.Choice(
                                ThemeMode.System.preferenceValue,
                                ThemeMode.System.displayName,
                                icon = Icons.Outlined.BrightnessAuto,
                            ),
                            SettingsItem.Choice(
                                ThemeMode.Light.preferenceValue,
                                ThemeMode.Light.displayName,
                                icon = Icons.Outlined.LightMode,
                            ),
                            SettingsItem.Choice(
                                ThemeMode.Dark.preferenceValue,
                                ThemeMode.Dark.displayName,
                                icon = Icons.Outlined.DarkMode,
                            ),
                        ),
                ),
            )

            add(SettingsItem.Section(getString(R.string.category_data)))
            add(
                SettingsItem.Action(
                    title = getString(R.string.backup_export_title),
                    summary = getString(R.string.backup_export_summary),
                    onClick = ::launchBackupExport,
                    icon = Icons.Outlined.Download,
                ),
            )
            add(
                SettingsItem.Action(
                    title = getString(R.string.backup_restore_title),
                    summary = getString(R.string.backup_restore_summary),
                    onClick = ::launchBackupImport,
                    icon = Icons.Outlined.Upload,
                ),
            )

            add(SettingsItem.Section(getString(R.string.category_info)))
            add(
                SettingsItem.Action(
                    title = getString(R.string.release_notes_title),
                    summary = getString(R.string.release_notes_summary, BuildConfig.VERSION_NAME),
                    icon = Icons.AutoMirrored.Outlined.Article,
                    onClick = {
                        runCatching { findNavController().navigate(R.id.navigation_release_notes) }
                    },
                ),
            )
            add(
                SettingsItem.Action(
                    title = getString(R.string.about_title),
                    summary = getString(R.string.about_summary),
                    icon = Icons.Outlined.Info,
                    onClick = {
                        runCatching { findNavController().navigate(R.id.navigation_about) }
                    },
                ),
            )
        }

    private fun buildCuePreviewItems(): List<SettingsItem> =
        buildList {
            add(SettingsItem.Section(getString(R.string.state_cues_completion_section)))
            addCompletionCueItems()
            add(SettingsItem.Section(getString(R.string.state_cues_manual_section)))
            add(SettingsItem.Note(getString(R.string.state_cues_manual_note)))
            addManualHapticItems()
        }

    private fun MutableList<SettingsItem>.addCompletionCueItems() {
        add(
            SettingsItem.CompletionCuePreview(
                family = CompletionCueFamily.FocusComplete,
                title = getString(R.string.state_cues_focus_complete_title),
                summary = getString(R.string.state_cues_focus_complete_summary),
                serviceProvider = { (activity as? MainActivity)?.service },
                onFeedback = ::showMessage,
            ),
        )
        add(
            SettingsItem.CompletionCuePreview(
                family = CompletionCueFamily.ShortBreakComplete,
                title = getString(R.string.state_cues_short_break_complete_title),
                summary = getString(R.string.state_cues_short_break_complete_summary),
                serviceProvider = { (activity as? MainActivity)?.service },
                onFeedback = ::showMessage,
            ),
        )
        add(
            SettingsItem.CompletionCuePreview(
                family = CompletionCueFamily.LongBreakComplete,
                title = getString(R.string.state_cues_long_break_complete_title),
                summary = getString(R.string.state_cues_long_break_complete_summary),
                serviceProvider = { (activity as? MainActivity)?.service },
                onFeedback = ::showMessage,
            ),
        )
    }

    private fun MutableList<SettingsItem>.addManualHapticItems() {
        add(
            SettingsItem.ManualHapticPreview(
                event = StateCueEvent.StartOrResumeTapped,
                title = getString(R.string.state_cues_start_resume_title),
                summary = getString(R.string.state_cues_start_resume_summary),
                icon = Icons.Outlined.PlayCircle,
                serviceProvider = { (activity as? MainActivity)?.service },
                onFeedback = ::showMessage,
            ),
        )
        add(
            SettingsItem.ManualHapticPreview(
                event = StateCueEvent.PauseTapped,
                title = getString(R.string.state_cues_pause_title),
                summary = getString(R.string.state_cues_pause_summary),
                icon = Icons.Outlined.PauseCircle,
                serviceProvider = { (activity as? MainActivity)?.service },
                onFeedback = ::showMessage,
            ),
        )
        add(
            SettingsItem.ManualHapticPreview(
                event = StateCueEvent.SkipTapped,
                title = getString(R.string.state_cues_skip_title),
                summary = getString(R.string.state_cues_skip_summary),
                icon = Icons.Outlined.SkipNext,
                serviceProvider = { (activity as? MainActivity)?.service },
                onFeedback = ::showMessage,
            ),
        )
        add(
            SettingsItem.ManualHapticPreview(
                event = StateCueEvent.ResetTapped,
                title = getString(R.string.state_cues_reset_title),
                summary = getString(R.string.state_cues_reset_summary),
                icon = Icons.Outlined.RestartAlt,
                serviceProvider = { (activity as? MainActivity)?.service },
                onFeedback = ::showMessage,
            ),
        )
    }

    private val tagManagerDialog = mutableStateOf(false)
    private val defaultTagPicker = mutableStateOf(false)

    private fun showTagManager() {
        tagManagerDialog.value = true
    }

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

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?,
    ) {
        val activity = activity as? MainActivity ?: return
        when (key) {
            "daily_goal" -> {
                activity.service?.updateDailyGoal()
                activity.service?.syncConfig()
            }
            "pomodoro_duration", "short_break_duration", "long_break_duration",
            "long_break_after", "phone_server_port", "phone_server_enabled", "phone_server_wifi_only",
            -> {
                activity.service?.syncConfig()
            }
        }
    }

    private fun onPairingClick() {
        val service = (activity as? MainActivity)?.service
        if (service == null) {
            showMessage(R.string.pair_desktop_unavailable)
            return
        }
        pairingDialog.value =
            PairingSheetData(
                url = service.pairingUrl,
                token = service.pairingToken,
                payload = service.pairingPayload,
            )
    }

    private fun doRotatePairingToken() {
        val service = (activity as? MainActivity)?.service
        if (service == null) {
            showMessage(R.string.pair_desktop_unavailable)
        } else {
            service.rotatePairingToken()
            showMessage(R.string.rotate_pairing_token_done)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.pair_desktop_title), text))
        showMessage(R.string.pairing_copied)
    }

    private fun sharePairingPayload(payload: String) {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload)
            }
        startActivity(Intent.createChooser(intent, getString(R.string.pairing_share_title)))
    }

    private fun launchQrScanner() {
        val intent =
            Intent("com.google.zxing.client.android.SCAN").apply {
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
        val message =
            when {
                service == null -> getString(R.string.scan_pairing_qr_service_unavailable)
                scannedToken == service.pairingToken -> getString(R.string.scan_pairing_qr_match)
                else -> getString(R.string.scan_pairing_qr_other)
            }

        scanResult.value = ScanResultData(message = message, url = scannedUrl)
    }

    private fun launchBackupExport() {
        val today = LocalDate.now().toString()
        val fileName = BackupRepository.suggestedFileName(today)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            viewLifecycleOwner.lifecycleScope.launch {
                val uri = backupRepository.writeToDownloads(fileName)
                showMessage(if (uri != null) R.string.backup_export_done else R.string.backup_export_failed)
            }
        } else {
            runCatching { exportBackupLauncher.launch(fileName) }
                .onFailure { showMessage(R.string.backup_no_file_picker) }
        }
    }

    private fun launchBackupImport() {
        // Some file providers hand JSON out as a generic stream, so accept both and validate on read.
        val mimeTypes = arrayOf(BackupRepository.MIME_TYPE, "application/octet-stream")
        runCatching { importBackupLauncher.launch(mimeTypes) }
            .onFailure { showMessage(R.string.backup_no_file_picker) }
    }

    private fun runRestore() {
        val backup = pendingRestore ?: return
        pendingRestore = null
        viewLifecycleOwner.lifecycleScope.launch {
            val summary =
                try {
                    backupRepository.restore(backup)
                } catch (_: BackupIdentityConflictException) {
                    showMessage(R.string.backup_restore_identity_conflict)
                    return@launch
                } catch (_: Exception) {
                    null
                }
            if (summary == null) {
                showMessage(R.string.backup_restore_failed)
                return@launch
            }
            (activity as? MainActivity)?.service?.refreshFromHistory()
            showMessage(
                getString(
                    R.string.backup_restore_done,
                    resources.getQuantityString(
                        R.plurals.backup_sessions,
                        summary.sessionsAdded,
                        summary.sessionsAdded,
                    ),
                    resources.getQuantityString(
                        R.plurals.backup_crews,
                        summary.membershipsAdded,
                        summary.membershipsAdded,
                    ),
                ),
            )
        }
    }

    private fun PomoBackup.toPreview(): RestorePreviewData =
        RestorePreviewData(
            sessionCount = history.sessions.size,
            crewCount = crew.memberships.size,
            hasIdentity = crew.identityPrivateKey.isNotBlank(),
            exportedOn =
                exportedAtEpochSeconds
                    .takeIf { it > 0L }
                    ?.let {
                        Instant.ofEpochSecond(it)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                    }
                    ?: getString(R.string.backup_exported_unknown),
        )

    private fun showMessage(messageRes: Int) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
