package com.pomo.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pomo.R
import com.pomo.cues.CompletionCueFamily
import com.pomo.cues.CuePreviewChannel
import com.pomo.cues.CueVariant
import com.pomo.cues.StateCueEvent
import com.pomo.service.PomodoroService
import com.pomo.ui.components.CompactEditorField
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.PomoDialog
import com.pomo.ui.components.PomoSwitch
import com.pomo.ui.components.SectionHeader
import com.pomo.ui.components.SegmentedToggle
import com.pomo.ui.components.SegmentedToggleOption
import com.pomo.ui.theme.JetBrainsMono
import com.pomo.ui.theme.PomoRadius
import com.pomo.ui.theme.PomoTokens
import kotlin.random.Random

public sealed interface SettingsItem {
    public data class Section(val title: String) : SettingsItem

    public data class Note(val text: String) : SettingsItem

    public data class IntPref(
        val key: String,
        val title: String,
        val summary: String,
        val default: Int,
        val min: Int = 1,
        val max: Int = 999,
        val unit: String? = null,
        val presets: List<Int>? = null,
        val allowRandom: Boolean = false,
        val icon: ImageVector? = null,
    ) : SettingsItem

    public data class BoolPref(
        val key: String,
        val title: String,
        val summary: String,
        val default: Boolean,
        val icon: ImageVector? = null,
        /** When set, the row dims and ignores input unless the predicate holds. */
        val enabledWhen: ((SharedPreferences) -> Boolean)? = null,
        /** Preference keys the predicate reads; drives recomposition. */
        val enabledPrefKeys: List<String> = emptyList(),
    ) : SettingsItem

    public data class ChoicePref(
        val key: String,
        val title: String,
        val summary: String,
        val default: String,
        val choices: List<Choice>,
        val icon: ImageVector? = null,
        val enabledWhen: ((SharedPreferences) -> Boolean)? = null,
        val enabledPrefKeys: List<String> = emptyList(),
        /** When set, the row is removed from the card entirely unless the predicate holds. */
        val visibleWhen: ((SharedPreferences) -> Boolean)? = null,
    ) : SettingsItem

    public data class SegmentedPref(
        val key: String,
        val title: String,
        val summary: String,
        val default: String,
        val choices: List<Choice>,
        val icon: ImageVector? = null,
    ) : SettingsItem

    public data class Action(
        val title: String,
        val summary: String,
        val onClick: () -> Unit,
        val icon: ImageVector? = null,
        val valueProvider: (() -> String?)? = null,
        /** When set, re-evaluated on recomposition to replace the static summary. */
        val summaryProvider: (() -> String?)? = null,
        val enabledWhen: ((SharedPreferences) -> Boolean)? = null,
        val enabledPrefKeys: List<String> = emptyList(),
    ) : SettingsItem

    public data class CompletionCuePreview(
        val family: CompletionCueFamily,
        val title: String,
        val summary: String,
        val serviceProvider: () -> PomodoroService?,
        val onFeedback: (Int) -> Unit,
    ) : SettingsItem

    public data class ManualHapticPreview(
        val event: StateCueEvent,
        val title: String,
        val summary: String,
        val icon: ImageVector? = null,
        val serviceProvider: () -> PomodoroService?,
        val onFeedback: (Int) -> Unit,
    ) : SettingsItem

    public data class Choice(
        val value: String,
        val label: String,
        val icon: ImageVector? = null,
    )
}

private data class SettingsGroup(
    val title: String?,
    val items: List<SettingsItem>,
)

private fun groupSettings(items: List<SettingsItem>): List<SettingsGroup> {
    val out = mutableListOf<SettingsGroup>()
    var currentTitle: String? = null
    var current = mutableListOf<SettingsItem>()
    items.forEach { item ->
        if (item is SettingsItem.Section) {
            if (current.isNotEmpty() || currentTitle != null) {
                out += SettingsGroup(currentTitle, current.toList())
            }
            currentTitle = item.title
            current = mutableListOf()
        } else {
            current += item
        }
    }
    if (current.isNotEmpty() || currentTitle != null) {
        out += SettingsGroup(currentTitle, current.toList())
    }
    return out
}

@Composable
public fun SettingsScreen(
    sharedPreferences: SharedPreferences,
    items: List<SettingsItem>,
    title: String = "Settings",
    backContentDescription: String = "Back",
    searchContentDescription: String = "Search settings",
    showUpdateSection: Boolean = false,
    showSearch: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    val groups = remember(items) { groupSettings(items) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

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
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backContentDescription,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (showSearch) {
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = searchContentDescription,
                        tint =
                            if (searchOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
        }
        if (showSearch && searchOpen) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(PomoRadius.Md))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(PomoRadius.Md),
                        )
                        .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .wrapContentHeight(Alignment.CenterVertically),
                    decorationBox = { innerField ->
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        innerField()
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.settings_search_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val trimmedQuery = if (showSearch) query.trim() else ""
        val visibleGroups =
            remember(groups, trimmedQuery) { filterGroups(groups, trimmedQuery) }
        val updatesQueryHit =
            trimmedQuery.isEmpty() ||
                listOf(
                    stringResource(R.string.updates_title),
                    stringResource(R.string.updates_check_title),
                    stringResource(R.string.updates_idle_summary, ""),
                    stringResource(R.string.updates_check_action),
                ).any { it.lowercase().contains(trimmedQuery.lowercase()) }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (showUpdateSection && updatesQueryHit) {
                item(key = "updates") {
                    UpdateSection()
                }
            }
            items(visibleGroups, key = { it.title ?: "_" }) { group ->
                SettingsGroupCard(group, sharedPreferences, filtering = trimmedQuery.isNotEmpty())
            }
            if (visibleGroups.isEmpty() && !(showUpdateSection && updatesQueryHit)) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    ) {
                        Icon(
                            Icons.Outlined.SearchOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.settings_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun filterGroups(
    groups: List<SettingsGroup>,
    query: String,
): List<SettingsGroup> {
    if (query.isEmpty()) return groups
    val q = query.lowercase()
    return groups.mapNotNull { group ->
        val matches = group.items.filter { settingsItemMatches(it, q) }
        if (matches.isEmpty()) null else SettingsGroup(group.title, matches)
    }
}

private fun settingsItemMatches(
    item: SettingsItem,
    q: String,
): Boolean =
    when (item) {
        is SettingsItem.Section -> false
        is SettingsItem.Note -> item.text.lowercase().contains(q)
        is SettingsItem.IntPref -> item.title.lowercase().contains(q) || item.summary.lowercase().contains(q)
        is SettingsItem.BoolPref -> item.title.lowercase().contains(q) || item.summary.lowercase().contains(q)
        is SettingsItem.ChoicePref -> item.title.lowercase().contains(q) || item.summary.lowercase().contains(q)
        is SettingsItem.SegmentedPref -> item.title.lowercase().contains(q) || item.summary.lowercase().contains(q)
        is SettingsItem.Action -> {
            val summary = item.summaryProvider?.invoke() ?: item.summary
            val value = item.valueProvider?.invoke().orEmpty()
            item.title.lowercase().contains(q) ||
                summary.lowercase().contains(q) ||
                value.lowercase().contains(q)
        }
        is SettingsItem.CompletionCuePreview -> item.title.lowercase().contains(q) || item.summary.lowercase().contains(q)
        is SettingsItem.ManualHapticPreview -> item.title.lowercase().contains(q) || item.summary.lowercase().contains(q)
    }

@Composable
private fun SettingsGroupCard(
    group: SettingsGroup,
    prefs: SharedPreferences,
    filtering: Boolean,
) {
    Column {
        if (group.title != null) {
            SectionHeader(group.title, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        // Bumped whenever a tracked preference changes so visibleWhen rows re-evaluate.
        var prefEpoch by remember { mutableStateOf(0) }
        val trackedKeys =
            remember(group) {
                group.items.flatMap { item ->
                    when (item) {
                        is SettingsItem.BoolPref -> item.enabledPrefKeys
                        is SettingsItem.ChoicePref -> item.enabledPrefKeys
                        is SettingsItem.Action -> item.enabledPrefKeys
                        else -> emptyList()
                    }
                }.toSet()
            }
        DisposableEffect(prefs, trackedKeys) {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> prefEpoch++ }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        val visibleItems =
            remember(group.items, prefEpoch) {
                group.items.filter { item ->
                    (item as? SettingsItem.ChoicePref)?.visibleWhen?.invoke(prefs) ?: true
                }
            }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(PomoRadius.Lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column {
                visibleItems.forEachIndexed { i, item ->
                    // While search filters a card, the mock drops dividers between hits.
                    if (i > 0 && !filtering) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    when (item) {
                        is SettingsItem.Section -> Unit
                        is SettingsItem.Note -> NoteRow(item)
                        is SettingsItem.IntPref -> IntPrefRow(prefs, item)
                        is SettingsItem.BoolPref ->
                            GatedRow(prefs, item.enabledWhen, item.enabledPrefKeys) {
                                BoolPrefRow(prefs, item, it)
                            }
                        is SettingsItem.ChoicePref ->
                            GatedRow(prefs, item.enabledWhen, item.enabledPrefKeys) {
                                ChoicePrefRow(prefs, item, it)
                            }
                        is SettingsItem.SegmentedPref -> SegmentedPrefRow(prefs, item)
                        is SettingsItem.Action ->
                            GatedRow(prefs, item.enabledWhen, item.enabledPrefKeys) {
                                ActionRow(item, it)
                            }
                        is SettingsItem.CompletionCuePreview -> CompletionCuePreviewRow(prefs, item)
                        is SettingsItem.ManualHapticPreview -> ManualHapticPreviewRow(prefs, item)
                    }
                }
            }
        }
    }
}

/** Dims the content and swallows clicks while [enabledWhen] fails on the tracked [keys]. */
@Composable
private fun GatedRow(
    prefs: SharedPreferences,
    enabledWhen: ((SharedPreferences) -> Boolean)?,
    keys: List<String>,
    content: @Composable (enabled: Boolean) -> Unit,
) {
    if (enabledWhen == null) {
        content(true)
        return
    }
    var enabled by remember { mutableStateOf(enabledWhen(prefs)) }
    DisposableEffect(prefs, keys) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
                enabled = enabledWhen(sp)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Box(modifier = Modifier.alpha(if (enabled) 1f else 0.38f)) {
        content(enabled)
        if (!enabled) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
    }
}

@Composable
private fun NoteRow(item: SettingsItem.Note) {
    Text(
        text = item.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun IntPrefRow(
    prefs: SharedPreferences,
    item: SettingsItem.IntPref,
) {
    var current by remember(item.key) {
        mutableStateOf(prefs.getString(item.key, item.default.toString()) ?: item.default.toString())
    }
    DisposableEffect(item.key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, k ->
                if (k == item.key) {
                    current = sp.getString(item.key, item.default.toString()) ?: item.default.toString()
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var editing by remember { mutableStateOf(false) }

    PrefRow(
        title = item.title,
        summary = item.summary,
        valueText = current,
        valueUnit = item.unit?.let { unitLabelFor(it) },
        onClick = { editing = true },
        leadingIcon = item.icon,
        valueMono = true,
    )

    if (editing) {
        NumberEditorDialog(
            title = item.title,
            initial = current.toIntOrNull() ?: item.default,
            min = item.min,
            max = item.max,
            unit = item.unit,
            presets = item.presets,
            allowRandom = item.allowRandom,
            onDismiss = { editing = false },
            onConfirm = { value ->
                prefs.edit().putString(item.key, value.toString()).apply()
                editing = false
            },
        )
    }
}

private fun unitLabelFor(unit: String): String =
    when (unit) {
        "minutes" -> "min"
        "blocks a day" -> "blocks"
        else -> unit
    }

/**
 * Compact integer editor: typing is first-class, preset chips commit on tap,
 * the range error only appears while input is invalid, Enter commits.
 */
@Composable
private fun NumberEditorDialog(
    title: String,
    initial: Int,
    min: Int,
    max: Int,
    unit: String?,
    presets: List<Int>?,
    allowRandom: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in min..max
    val suffix = unit?.let { unitLabelFor(it) } ?: ""

    PomoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        body = {
            Column {
                CompactEditorField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(9) },
                    suffix = suffix.ifEmpty { null },
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    onConfirm = { if (valid) onConfirm(parsed!!) },
                    trailing = {
                        if (allowRandom) {
                            IconButton(
                                onClick = { text = Random.nextInt(min, max + 1).toString() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Casino,
                                    contentDescription = stringResource(R.string.number_editor_random),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                if (!valid) {
                    Text(
                        stringResource(R.string.number_editor_range, min, max),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (presets != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        presets.forEach { preset ->
                            EditorChip(
                                label = preset.toString(),
                                selected = parsed == preset,
                                onClick = {
                                    text = preset.toString()
                                    onConfirm(preset)
                                },
                            )
                        }
                    }
                }
            }
        },
        actions = {
            PomoButton(onClick = onDismiss, variant = PomoButtonVariant.Ghost, compact = true) {
                Text(stringResource(android.R.string.cancel))
            }
            PomoButton(
                onClick = { parsed?.let(onConfirm) },
                variant = PomoButtonVariant.Tonal,
                enabled = valid,
                compact = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.number_editor_ok)) }
        },
    )
}

/** Mock-style preset chip: 32dp tall, 8dp radius outline, commits on tap. */
@Composable
private fun EditorChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = PomoTokens.colors
    Box(
        modifier =
            Modifier
                .height(32.dp)
                .background(
                    if (selected) tokens.focus.copy(alpha = 0.18f) else Color.Transparent,
                    RoundedCornerShape(PomoRadius.Sm),
                )
                .border(
                    1.dp,
                    if (selected) Color.Transparent else tokens.outline,
                    RoundedCornerShape(PomoRadius.Sm),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) tokens.focus else tokens.onSurface,
        )
    }
}

@Composable
private fun ChoicePrefRow(
    prefs: SharedPreferences,
    item: SettingsItem.ChoicePref,
    enabled: Boolean = true,
) {
    var current by remember(item.key) {
        mutableStateOf(prefs.getString(item.key, item.default) ?: item.default)
    }
    DisposableEffect(item.key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, k ->
                if (k == item.key) current = sp.getString(item.key, item.default) ?: item.default
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val currentLabel =
        item.choices.firstOrNull { it.value == current }?.label
            ?: item.choices.firstOrNull { it.value == item.default }?.label
            ?: current

    // Two or three choices read better inline, matching the mock: stack + segmented, no dialog.
    if (item.choices.size in 2..3) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.icon != null) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SegmentedToggle(
                options = item.choices.map { SegmentedToggleOption(it.value, it.label, icon = it.icon) },
                selectedValue = current,
                onSelectedValueChange = { value ->
                    current = value
                    prefs.edit().putString(item.key, value).apply()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            )
        }
        return
    }

    var editing by remember { mutableStateOf(false) }
    PrefRow(
        title = item.title,
        summary = item.summary,
        valueText = currentLabel,
        onClick = { editing = true },
        leadingIcon = item.icon,
        enabled = enabled,
    )

    if (editing) {
        PomoDialog(
            onDismissRequest = { editing = false },
            title = { Text(item.title) },
            body = {
                Column {
                    item.choices.forEach { choice ->
                        Text(
                            text = choice.label,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        current = choice.value
                                        prefs.edit().putString(item.key, choice.value).apply()
                                        editing = false
                                    }
                                    .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                if (choice.value == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            },
            actions = {
                TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SegmentedPrefRow(
    prefs: SharedPreferences,
    item: SettingsItem.SegmentedPref,
) {
    var current by remember(item.key) {
        mutableStateOf(prefs.getString(item.key, item.default) ?: item.default)
    }
    DisposableEffect(item.key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, k ->
                if (k == item.key) current = sp.getString(item.key, item.default) ?: item.default
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.icon != null) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SegmentedToggle(
            options = item.choices.map { SegmentedToggleOption(it.value, it.label, icon = it.icon) },
            selectedValue = current,
            onSelectedValueChange = { value ->
                current = value
                prefs.edit().putString(item.key, value).apply()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BoolPrefRow(
    prefs: SharedPreferences,
    item: SettingsItem.BoolPref,
    enabled: Boolean = true,
) {
    var checked by remember(item.key) {
        mutableStateOf(prefs.getBoolean(item.key, item.default))
    }
    DisposableEffect(item.key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, k ->
                if (k == item.key) checked = sp.getBoolean(item.key, item.default)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    val next = !checked
                    checked = next
                    prefs.edit().putBoolean(item.key, next).apply()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PomoSwitch(
            checked = checked,
            onCheckedChange =
                if (enabled) {
                    { next ->
                        checked = next
                        prefs.edit().putBoolean(item.key, next).apply()
                    }
                } else {
                    null
                },
            enabled = enabled,
        )
    }
}

@Composable
private fun ActionRow(
    item: SettingsItem.Action,
    enabled: Boolean = true,
) {
    val providedValue = item.valueProvider?.invoke()
    val summary = item.summaryProvider?.invoke() ?: item.summary
    PrefRow(
        title = item.title,
        summary = summary,
        valueText = providedValue,
        onClick = item.onClick,
        leadingIcon = item.icon,
        enabled = enabled,
    )
}

@Composable
private fun CompletionCuePreviewRow(
    prefs: SharedPreferences,
    item: SettingsItem.CompletionCuePreview,
) {
    val context = LocalContext.current
    val soundEnabled = rememberPrefBoolean(prefs, "sound_enabled", true)
    val vibrationEnabled = rememberPrefBoolean(prefs, "vibrate_enabled", true)
    val strongerEnabled = rememberPrefBoolean(prefs, "stronger_completion_cues", false)
    val nextVariantNumber = rememberPrefInt(prefs, item.family.nextVariantPrefKey, CueVariant.Variant1.number)
    var selectedVariant by remember(item.family) { mutableStateOf(CueVariant.fromNumber(nextVariantNumber)) }
    val serviceProvider by rememberUpdatedState(item.serviceProvider)
    val vibrationAvailable = remember(context) { context.hasVibratorCapability() }

    DisposableEffect(nextVariantNumber) {
        selectedVariant = CueVariant.fromNumber(nextVariantNumber)
        onDispose { }
    }

    fun preview(channel: CuePreviewChannel) {
        val service = serviceProvider()
        if (service == null) {
            item.onFeedback(R.string.state_cues_preview_service_unavailable)
            return
        }
        service.previewCompletionCue(item.family, selectedVariant, channel).messageRes?.let(item.onFeedback)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            item.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SegmentedToggle(
            options =
                listOf(
                    SegmentedToggleOption(CueVariant.Variant1.number.toString(), context.getString(R.string.state_cues_variant_1)),
                    SegmentedToggleOption(CueVariant.Variant2.number.toString(), context.getString(R.string.state_cues_variant_2)),
                    SegmentedToggleOption(CueVariant.Variant3.number.toString(), context.getString(R.string.state_cues_variant_3)),
                ),
            selectedValue = selectedVariant.number.toString(),
            onSelectedValueChange = { selectedVariant = CueVariant.fromNumber(it.toInt()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                buildString {
                    append(context.getString(R.string.state_cues_preview_next_completion, nextVariantNumber))
                    append(" · ")
                    append(
                        context.getString(
                            if (soundEnabled) R.string.state_cues_preview_sound_on else R.string.state_cues_preview_sound_off_inline,
                        ),
                    )
                    append(" · ")
                    append(
                        when {
                            !vibrationEnabled -> context.getString(R.string.state_cues_preview_vibration_off_inline)
                            !vibrationAvailable -> context.getString(R.string.state_cues_preview_vibration_unavailable_inline)
                            else -> context.getString(R.string.state_cues_preview_vibration_on)
                        },
                    )
                    if (strongerEnabled) append(" · ${context.getString(R.string.state_cues_preview_stronger)}")
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PomoButton(
                onClick = { preview(CuePreviewChannel.Combined) },
                variant = PomoButtonVariant.Tonal,
                enabled = soundEnabled || (vibrationEnabled && vibrationAvailable),
                compact = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(context.getString(R.string.state_cues_preview_button)) }
            PomoButton(
                onClick = { preview(CuePreviewChannel.AudioOnly) },
                variant = PomoButtonVariant.Ghost,
                enabled = soundEnabled,
                compact = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(context.getString(R.string.state_cues_preview_audio)) }
            PomoButton(
                onClick = { preview(CuePreviewChannel.HapticOnly) },
                variant = PomoButtonVariant.Ghost,
                enabled = vibrationEnabled && vibrationAvailable,
                compact = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(context.getString(R.string.state_cues_preview_haptic)) }
        }
    }
}

@Composable
private fun ManualHapticPreviewRow(
    prefs: SharedPreferences,
    item: SettingsItem.ManualHapticPreview,
) {
    val context = LocalContext.current
    val serviceProvider by rememberUpdatedState(item.serviceProvider)
    val vibrationEnabled = rememberPrefBoolean(prefs, "vibrate_enabled", true)
    val vibrationAvailable = remember(context) { context.hasVibratorCapability() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        PomoButton(
            onClick = {
                val service = serviceProvider()
                if (service != null) {
                    service.previewManualCue(item.event).messageRes?.let(item.onFeedback)
                } else {
                    item.onFeedback(R.string.state_cues_preview_service_unavailable)
                }
            },
            variant = PomoButtonVariant.Ghost,
            enabled = vibrationEnabled && vibrationAvailable,
            compact = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.state_cues_preview_haptic_button)) }
    }
}

@Composable
private fun PrefRow(
    title: String,
    summary: String,
    valueText: String?,
    onClick: () -> Unit,
    leadingIcon: ImageVector?,
    valueMono: Boolean = false,
    valueUnit: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(alpha)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (valueText != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    valueText,
                    style =
                        if (valueMono) {
                            MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono)
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (valueUnit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        valueUnit,
                        style = MaterialTheme.typography.labelMedium,
                        color = PomoTokens.colors.onSurfaceFaint,
                    )
                }
            }
        }
        Spacer(Modifier.padding(end = 2.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberPrefBoolean(
    prefs: SharedPreferences,
    key: String,
    default: Boolean,
): Boolean {
    var value by remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
    DisposableEffect(key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
                if (changedKey == key) value = sp.getBoolean(key, default)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
private fun rememberPrefInt(
    prefs: SharedPreferences,
    key: String,
    default: Int,
): Int {
    var value by remember(key) { mutableStateOf(prefs.getInt(key, default)) }
    DisposableEffect(key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
                if (changedKey == key) value = sp.getInt(key, default)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

private fun Context.hasVibratorCapability(): Boolean {
    val vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    return vibrator.hasVibrator()
}
