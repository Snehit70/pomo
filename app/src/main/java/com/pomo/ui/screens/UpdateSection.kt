package com.pomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pomo.BuildConfig
import com.pomo.R
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.theme.PomoRadius
import com.pomo.update.ApkInstaller
import com.pomo.update.DownloadOutcome
import com.pomo.update.GithubUpdateChecker
import com.pomo.update.LatestRelease
import com.pomo.update.UpdateCheckResult
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data object UpToDate : UpdateUiState

    data class Available(val release: LatestRelease) : UpdateUiState

    data class Downloading(val release: LatestRelease, val progress: Float) : UpdateUiState

    data object Installing : UpdateUiState

    data class Failed(val messageRes: Int, val canOpenSettings: Boolean = false) : UpdateUiState
}

/**
 * The manual "Check for updates" row, first group of the settings list. Hosts its own
 * state (the codebase has no ViewModel layer); pure logic lives in [GithubUpdateChecker]
 * and [ApkInstaller]. Callers gate this to the canonical build — the `.demo` build hides it.
 */
@Composable
internal fun UpdateSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client =
        remember {
            OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    val checker = remember { GithubUpdateChecker(client) }
    val installer = remember(context) { ApkInstaller(client, context.cacheDir) }
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun check() {
        state = UpdateUiState.Checking
        scope.launch {
            state =
                when (val result = checker.check(BuildConfig.VERSION_NAME)) {
                    is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(result.release)
                    UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                    UpdateCheckResult.Offline -> UpdateUiState.Failed(R.string.updates_error_offline)
                    UpdateCheckResult.RateLimited -> UpdateUiState.Failed(R.string.updates_error_rate_limited)
                    UpdateCheckResult.MalformedMetadata -> UpdateUiState.Failed(R.string.updates_error_malformed)
                    UpdateCheckResult.MissingAsset -> UpdateUiState.Failed(R.string.updates_error_missing_asset)
                }
        }
    }

    fun install(release: LatestRelease) {
        if (!ApkInstaller.canInstall(context)) {
            state =
                UpdateUiState.Failed(
                    R.string.updates_error_install_permission,
                    canOpenSettings = true,
                )
            return
        }
        scope.launch {
            state = UpdateUiState.Downloading(release, 0f)
            val outcome =
                installer.download(release) { progress ->
                    state = UpdateUiState.Downloading(release, progress)
                }
            state =
                when (outcome) {
                    is DownloadOutcome.Ready -> {
                        if (ApkInstaller.canInstall(context)) {
                            ApkInstaller.launchInstaller(context, outcome.apk)
                            UpdateUiState.Installing
                        } else {
                            UpdateUiState.Failed(
                                R.string.updates_error_install_permission,
                                canOpenSettings = true,
                            )
                        }
                    }
                    DownloadOutcome.Offline -> UpdateUiState.Failed(R.string.updates_error_download_interrupted)
                    DownloadOutcome.Corrupt -> UpdateUiState.Failed(R.string.updates_error_corrupt)
                    DownloadOutcome.Failed -> UpdateUiState.Failed(R.string.updates_error_failed)
                }
        }
    }

    Column(modifier = modifier) {
        SectionHeader(
            stringResource(R.string.updates_title),
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(PomoRadius.Lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.updates_check_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    val summary =
                        when (val s = state) {
                            UpdateUiState.Idle -> stringResource(R.string.updates_idle_summary, BuildConfig.VERSION_NAME)
                            UpdateUiState.Checking -> stringResource(R.string.updates_checking_summary)
                            UpdateUiState.UpToDate -> stringResource(R.string.updates_up_to_date_summary, BuildConfig.VERSION_NAME)
                            is UpdateUiState.Available -> stringResource(R.string.updates_available_summary, s.release.versionName)
                            is UpdateUiState.Downloading -> stringResource(R.string.updates_downloading_summary, s.release.versionName)
                            UpdateUiState.Installing -> stringResource(R.string.updates_installing_summary)
                            is UpdateUiState.Failed -> stringResource(s.messageRes)
                        }
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (state is UpdateUiState.Available) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    val s = state
                    if (s is UpdateUiState.Downloading) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(s.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                when (val s = state) {
                    UpdateUiState.Idle ->
                        UpdateActionButton(stringResource(R.string.updates_check_action)) { check() }
                    UpdateUiState.Checking ->
                        UpdateActionButton(stringResource(R.string.updates_checking_action), enabled = false) {}
                    UpdateUiState.UpToDate ->
                        UpdateActionButton(stringResource(R.string.updates_again_action)) { check() }
                    is UpdateUiState.Available ->
                        PomoButton(
                            onClick = { install(s.release) },
                            variant = PomoButtonVariant.Tonal,
                            contentPadding =
                                PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 8.dp,
                                ),
                        ) { Text(stringResource(R.string.updates_install_action)) }
                    is UpdateUiState.Downloading -> Unit
                    UpdateUiState.Installing -> Unit
                    is UpdateUiState.Failed ->
                        if (s.canOpenSettings) {
                            UpdateActionButton(stringResource(R.string.updates_open_settings)) {
                                ApkInstaller.openInstallPermissionSettings(context)
                            }
                        } else {
                            UpdateActionButton(stringResource(R.string.updates_try_again)) { check() }
                        }
                }
            }
        }
    }
}

@Composable
private fun UpdateActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PomoButton(
        onClick = onClick,
        variant = PomoButtonVariant.Ghost,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) { Text(label) }
}
