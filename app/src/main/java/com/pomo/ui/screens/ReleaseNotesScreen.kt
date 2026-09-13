package com.pomo.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pomo.BuildConfig
import com.pomo.R
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.theme.JetBrainsMono
import com.pomo.ui.theme.PomoRadius
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.SuccessGreenDark
import com.pomo.ui.theme.SuccessGreenLight
import com.pomo.update.GithubUpdateChecker
import com.pomo.update.ReleaseEntry
import com.pomo.update.ReleaseNotesCache
import com.pomo.update.ReleasesResult
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private sealed interface ReleaseNotesUiState {
    /** Nothing saved yet; first network fetch in flight. */
    data object FirstFetch : ReleaseNotesUiState

    data class Loaded(
        val releases: List<ReleaseEntry>,
        val fromCache: Boolean,
    ) : ReleaseNotesUiState

    data object NoCacheOffline : ReleaseNotesUiState

    data object NoCacheError : ReleaseNotesUiState
}

/**
 * Changelog screen, cached-first: GitHub releases render instantly from the local
 * cache and refresh in the background; offline with a cache shows saved notes with
 * a soft hint. Only "never fetched + offline" is a hard error.
 */
@Composable
public fun ReleaseNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val client =
        remember {
            OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    val checker = remember { GithubUpdateChecker(client) }
    val cache = remember { ReleaseNotesCache(context) }
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<ReleaseNotesUiState>(ReleaseNotesUiState.FirstFetch, reloadKey) {
        val cached = cache.get()
        if (cached.isNotEmpty()) {
            value = ReleaseNotesUiState.Loaded(cached, fromCache = true)
        }
        when (val result = checker.releases()) {
            is ReleasesResult.Success -> {
                cache.putAll(result.releases)
                value = ReleaseNotesUiState.Loaded(result.releases, fromCache = false)
            }
            ReleasesResult.Offline ->
                if (cached.isEmpty()) value = ReleaseNotesUiState.NoCacheOffline
            ReleasesResult.RateLimited, ReleasesResult.MalformedMetadata ->
                if (cached.isEmpty()) value = ReleaseNotesUiState.NoCacheError
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_to_settings),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    text = stringResource(R.string.release_notes_title),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.release_notes_installed_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
        ) {
            when (val s = state) {
                ReleaseNotesUiState.FirstFetch -> {
                    NotesCard {
                        Text(
                            stringResource(R.string.release_notes_first_fetch_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.release_notes_first_fetch_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp,
                        )
                    }
                }
                ReleaseNotesUiState.NoCacheOffline -> {
                    RetryCard(
                        title = stringResource(R.string.release_notes_nocache_offline_title),
                        body = stringResource(R.string.release_notes_nocache_offline_body),
                        onRetry = { reloadKey++ },
                    )
                }
                ReleaseNotesUiState.NoCacheError -> {
                    RetryCard(
                        title = stringResource(R.string.release_notes_nocache_error_title),
                        body = stringResource(R.string.release_notes_nocache_error_body),
                        onRetry = { reloadKey++ },
                    )
                }
                is ReleaseNotesUiState.Loaded -> LoadedNotes(s, onOpenChangelog = { openChangelog(context) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoadedNotes(
    state: ReleaseNotesUiState.Loaded,
    onOpenChangelog: () -> Unit,
) {
    if (state.fromCache) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.release_notes_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val installedMatch = state.releases.firstOrNull { it.versionName == BuildConfig.VERSION_NAME }
    // When the installed build has no matching release (too old, or unpublished),
    // the latest release takes the primary card under a Latest chip instead.
    val primary = installedMatch ?: state.releases.firstOrNull()
    if (primary != null) {
        val installed = primary
        val chipText =
            stringResource(
                if (installedMatch != null) {
                    R.string.release_notes_installed_chip
                } else {
                    R.string.release_notes_latest_chip
                },
            )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(PomoRadius.Lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                val successGreen = if (PomoTokens.colors.isDark) SuccessGreenDark else SuccessGreenLight
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        installed.versionName,
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = JetBrainsMono),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        chipText.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = successGreen,
                        modifier =
                            Modifier
                                .background(successGreen.copy(alpha = 0.12f), CircleShape)
                                .border(1.dp, successGreen.copy(alpha = 0.35f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                installed.publishedAt?.let { iso ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.release_notes_released, formatDate(iso)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.release_notes_whats_changed).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val bullets = parseNotes(installed.releaseNotes)
                if (bullets.isEmpty()) {
                    Text(
                        stringResource(R.string.release_notes_no_notes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        bullets.forEach { bullet ->
                            Row {
                                Box(
                                    modifier =
                                        Modifier
                                            .padding(top = 8.dp)
                                            .size(5.dp)
                                            .background(MaterialTheme.colorScheme.outline, CircleShape),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    inlineCode(bullet),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 22.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val earlier = state.releases.filter { it != primary }
    if (earlier.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        SectionHeader(
            stringResource(R.string.release_notes_earlier),
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(PomoRadius.Lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Column {
                earlier.forEachIndexed { index, entry ->
                    if (index > 0) {
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        )
                    }
                    EarlierReleaseRow(entry)
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenChangelog)
                .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.release_notes_full_changelog),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun EarlierReleaseRow(entry: ReleaseEntry) {
    var expanded by remember(entry.versionName) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.versionName,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            entry.publishedAt?.let { iso ->
                Text(
                    formatDate(iso),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } ?: Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val bullets = parseNotes(entry.releaseNotes)
        if (expanded && bullets.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                bullets.forEach { bullet ->
                    Row {
                        Box(
                            modifier =
                                Modifier
                                    .padding(top = 8.dp)
                                    .size(5.dp)
                                    .background(MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            inlineCode(bullet),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(PomoRadius.Lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun RetryCard(
    title: String,
    body: String,
    onRetry: () -> Unit,
) {
    NotesCard {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(14.dp))
        PomoButton(onClick = onRetry, variant = PomoButtonVariant.Tonal) {
            Text(stringResource(R.string.release_notes_retry))
        }
    }
}

/** GitHub markdown notes reduce to plain bullets: headings dropped, dashes trimmed. */
private fun parseNotes(body: String): List<String> =
    body
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("<") }
        .map { it.trimStart('-', '*', '•', ' ') }
        .filter { it.isNotEmpty() }

/** Renders `backtick` spans in mono; everything else stays in the base style. */
private fun inlineCode(text: String): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (true) {
            val start = text.indexOf('`', i)
            if (start == -1) {
                append(text.substring(i))
                break
            }
            val end = text.indexOf('`', start + 1)
            if (end == -1) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, start))
            pushStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = 13.sp))
            append(text.substring(start + 1, end))
            pop()
            i = end + 1
        }
    }

private fun formatDate(iso: String): String =
    runCatching {
        LocalDate.parse(iso.substringBefore('T'))
            .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }.getOrDefault("")

private fun openChangelog(context: android.content.Context) {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${com.pomo.update.GithubUpdateChecker.DEFAULT_REPO}/releases"))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.link_unavailable, Toast.LENGTH_SHORT).show()
    }
}
