package com.pomo.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialFadeThrough
import com.pomo.MainActivity
import com.pomo.crew.CrewRankingMode
import com.pomo.crew.CrewRepository
import com.pomo.ui.screens.CrewScreen
import com.pomo.ui.screens.CrewScreenState
import com.pomo.ui.theme.PomoTheme
import com.pomo.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

public class CrewFragment : Fragment() {
    private val screenState = MutableStateFlow(CrewScreenState(isLoading = true))
    private val rankingMode = MutableStateFlow(CrewRankingMode.Today)
    private val initialJoinCode = MutableStateFlow<String?>(null)
    private lateinit var repository: CrewRepository
    private var liveBoardJob: Job? = null
    private var pendingRecoveryExport: PendingRecoveryExport? = null

    private val exportRecoveryDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val pending = pendingRecoveryExport ?: return@registerForActivityResult
            pendingRecoveryExport = null
            if (uri == null) return@registerForActivityResult
            if (writeRecoveryFile(uri, pending.recovery)) {
                showMessage("Recovery exported")
            } else {
                showMessage("Could not write recovery file")
            }
        }

    private val importRecoveryDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val recovery = readRecoveryFile(uri) ?: run {
                showMessage("Could not read recovery file")
                return@registerForActivityResult
            }
            promptRecoveryPassphrase(
                title = "Restore Recovery",
                confirmLabel = "Restore",
                helperText = "Restoring replaces this phone's current Crew identity and active v2 memberships.",
            ) { passphrase ->
                val restored = repository.restoreRecovery(recovery, passphrase.toCharArray())
                if (restored) {
                    showMessage("Recovery restored")
                    refreshBoard()
                    startLiveBoard()
                } else {
                    showMessage("Recovery restore failed")
                }
            }
        }

    private val confirmDeviceCredentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = pendingRecoveryExport ?: return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK) {
                pendingRecoveryExport = null
                showMessage("Recovery export canceled")
                return@registerForActivityResult
            }
            exportRecoveryDocumentLauncher.launch(pending.suggestedFileName)
        }

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        repository = CrewRepository(requireContext())
        initialJoinCode.value = arguments?.getString("crewJoinPayload")
            ?.let { payload -> "pomo://crew/join/v2/$payload" }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PomoTheme(mode = mainActivity?.prefs?.themeMode ?: ThemeMode.System) {
                val currentState by screenState.collectAsState()
                val currentInitialJoinCode by initialJoinCode.collectAsState()
                CrewScreen(
                    state = currentState,
                    onCreateCrew = { crewName, displayName -> createCrew(crewName, displayName) },
                    onJoinCrew = { joinCode, displayName -> joinCrew(joinCode, displayName) },
                    onSwitchCrew = { crewId -> switchCrew(crewId) },
                    onLeaveCrew = { crewId -> leaveCrew(crewId) },
                    onDisplayNameChange = { displayName -> updateDisplayName(displayName) },
                    onRankingModeChange = { mode -> rankingMode.value = mode },
                    onMemberHiddenChange = { identityPublicKey, hidden ->
                        setMemberHidden(identityPublicKey, hidden)
                    },
                    onExportRecovery = ::requestRecoveryExport,
                    onImportRecovery = ::requestRecoveryImport,
                    initialJoinCode = currentInitialJoinCode,
                    onInitialJoinCodeConsumed = { initialJoinCode.value = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBoard()
        startLiveBoard()
    }

    override fun onPause() {
        liveBoardJob?.cancel()
        liveBoardJob = null
        super.onPause()
    }

    private fun refreshBoard() {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            publishBoard(repository.currentBoard(rankingMode.value))
        }
    }

    private fun createCrew(crewName: String, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            publishBoard(repository.createSoloCrew(displayName, crewName))
            startLiveBoard()
        }
    }

    private fun joinCrew(joinCode: String, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            val board = repository.joinCrew(joinCode, displayName)
            publishBoard(board, errorMessage = if (board == null) "Invalid join code" else null)
            if (board != null) {
                startLiveBoard()
            }
        }
    }

    private fun switchCrew(crewId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            publishBoard(repository.switchCrew(crewId))
            startLiveBoard()
        }
    }

    private fun leaveCrew(crewId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            publishBoard(repository.leaveCrew(crewId))
            startLiveBoard()
        }
    }

    private fun updateDisplayName(displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            screenState.value = screenState.value.copy(isLoading = true)
            publishBoard(repository.updateDisplayName(displayName))
            startLiveBoard()
        }
    }

    private fun setMemberHidden(identityPublicKey: String, hidden: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            publishBoard(repository.setMemberHidden(identityPublicKey, hidden))
        }
    }

    private fun startLiveBoard() {
        liveBoardJob?.cancel()
        liveBoardJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                screenState.value = screenState.value.copy(isSyncing = true)
                try {
                    val republishedLocalHistory = repository.republishStaleLocalHistory()
                    if (republishedLocalHistory) {
                        publishBoard(repository.currentBoard(rankingMode.value))
                    }
                    repository.republishCurrentCrewIfStale()
                    repository.refreshCurrentCrew().collect { }
                } finally {
                    screenState.value = screenState.value.copy(isSyncing = false)
                }
            }
            launch {
                repository.observeLiveSnapshots().collect { }
            }
            repository.observeCurrentBoard(rankingMode).collect { board ->
                publishBoard(board)
            }
        }
    }

    private fun publishBoard(board: com.pomo.crew.CrewBoard?, errorMessage: String? = null) {
        screenState.value = CrewScreenState(
            isLoading = false,
            isSyncing = screenState.value.isSyncing,
            board = board,
            archivedMemberships = repository.currentArchivedMemberships(),
            errorMessage = errorMessage,
        )
    }

    private fun requestRecoveryExport() {
        promptRecoveryPassphrase(
            title = "Export Recovery",
            confirmLabel = "Continue",
            helperText = "This creates an encrypted recovery file for your current Crew identity.",
        ) { passphrase ->
            val keyguardManager = requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguardManager.isDeviceSecure) {
                showMessage("Set a device lock screen before exporting recovery")
                return@promptRecoveryPassphrase
            }
            pendingRecoveryExport = PendingRecoveryExport(
                recovery = repository.createRecovery(passphrase.toCharArray()),
                suggestedFileName = "pomo-crew-recovery-${System.currentTimeMillis()}.txt",
            )
            @Suppress("DEPRECATION")
            val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                "Confirm device unlock",
                "Unlock to export your Crew recovery",
            )
            if (intent == null) {
                pendingRecoveryExport = null
                showMessage("Could not start device credential confirmation")
                return@promptRecoveryPassphrase
            }
            confirmDeviceCredentialLauncher.launch(intent)
        }
    }

    private fun requestRecoveryImport() {
        AlertDialog.Builder(requireContext())
            .setTitle("Restore Recovery")
            .setMessage("Restoring replaces this phone's current Crew identity and active v2 memberships. Export the current identity first if you may need it later.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Export current") { _, _ -> requestRecoveryExport() }
            .setPositiveButton("Choose file") { _, _ ->
                importRecoveryDocumentLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
            }
            .show()
    }

    private fun promptRecoveryPassphrase(
        title: String,
        confirmLabel: String,
        helperText: String,
        onConfirm: (String) -> Unit,
    ) {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Passphrase"
        }
        val confirm = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Confirm passphrase"
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
            addView(confirm)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(helperText)
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(confirmLabel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val passphrase = input.text?.toString().orEmpty().trim()
                        val confirmation = confirm.text?.toString().orEmpty().trim()
                        when {
                            passphrase.length < 12 -> input.error = "Use at least 12 characters"
                            passphrase != confirmation -> confirm.error = "Passphrases do not match"
                            else -> {
                                dialog.dismiss()
                                onConfirm(passphrase)
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun readRecoveryFile(uri: Uri): String? =
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText().trim().ifBlank { null }
        }

    private fun writeRecoveryFile(uri: Uri, recovery: String): Boolean = runCatching {
        requireContext().contentResolver.openOutputStream(uri)?.use { output ->
            output.writer().use { writer -> writer.write(recovery) }
        } ?: return false
        true
    }.getOrDefault(false)

    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private data class PendingRecoveryExport(
        val recovery: String,
        val suggestedFileName: String,
    )
}
