package com.pomo.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Edit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pomo.R
import com.pomo.tags.TagStore
import com.pomo.ui.components.PomoButton
import com.pomo.ui.components.PomoButtonVariant
import com.pomo.ui.components.PomoDialog
import com.pomo.ui.components.PomoSheet
import com.pomo.ui.theme.PomoTokens
import com.pomo.ui.theme.tagPalette
import com.pomo.ui.theme.tagPaletteLight

@Composable
internal fun TagManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tagStore = remember { TagStore(context) }
    var tags by remember { mutableStateOf(tagStore.getTags()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var deletingTag by remember { mutableStateOf<String?>(null) }

    PomoSheet(title = stringResource(R.string.session_tags_title), onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.session_tags_sheet_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            if (tags.isEmpty()) {
                Text(
                    stringResource(R.string.session_tags_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            tags.forEachIndexed { index, tag ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }
                TagRow(
                    tag = tag,
                    color = tagColorSlot(tag, index, tagStore),
                    onEdit = { editingTag = tag },
                    onDelete = { deletingTag = tag },
                )
            }
        }
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAddDialog = true }
                        .background(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.shapes.medium,
                        )
                        .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).padding(end = 2.dp),
                )
                Text(
                    stringResource(R.string.session_tags_add),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (showAddDialog) {
        TagInputDialog(
            title = stringResource(R.string.session_tags_add),
            initial = "",
            onConfirm = { name ->
                tags = tagStore.addTag(name)
                showAddDialog = false
                Toast.makeText(context, R.string.session_tags_add_done, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAddDialog = false },
        )
    }

    editingTag?.let { oldName ->
        TagInputDialog(
            title = stringResource(R.string.session_tags_edit),
            initial = oldName,
            onConfirm = { newName ->
                when (val result = tagStore.renameTag(oldName, newName)) {
                    is TagStore.RenameResult.Success -> {
                        tags = result.tags
                        Toast.makeText(context, R.string.session_tags_edit_done, Toast.LENGTH_SHORT).show()
                    }
                    is TagStore.RenameResult.Duplicate -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.session_tags_duplicate, result.existingTag),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                editingTag = null
            },
            onDismiss = { editingTag = null },
        )
    }

    deletingTag?.let { tagName ->
        PomoDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(stringResource(R.string.session_tags_delete)) },
            body = { Text(stringResource(R.string.session_tags_delete_confirm, tagName)) },
            actions = {
                PomoButton(onClick = { deletingTag = null }, variant = PomoButtonVariant.Ghost) {
                    Text(stringResource(android.R.string.cancel))
                }
                PomoButton(
                    onClick = {
                        tags = tagStore.removeTag(tagName)
                        deletingTag = null
                        Toast.makeText(context, R.string.session_tags_delete_done, Toast.LENGTH_SHORT).show()
                    },
                    variant = PomoButtonVariant.Ghost,
                ) {
                    Text(
                        stringResource(R.string.session_tags_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

@Composable
private fun TagRow(
    tag: String,
    color: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
        )
        Text(
            tag,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        IconButton(onClick = onEdit) {
            Icon(
                Icons.AutoMirrored.Outlined.Edit,
                contentDescription = stringResource(R.string.session_tags_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.session_tags_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Stable presentation color for a tag, mirroring the stats pie palette. */
@Composable
private fun tagColorSlot(
    tag: String,
    index: Int,
    tagStore: TagStore,
): Color {
    val slots = remember(tag) { tagStore.getColorSlots(listOf(tag)) }
    val palette = if (PomoTokens.colors.isDark) tagPalette else tagPaletteLight
    return palette[slots[tag] ?: index % palette.size]
}

@Composable
private fun TagInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var submitted by remember { mutableStateOf(false) }
    val name = text.trim()
    val valid = name.isNotEmpty()

    PomoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        body = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.session_tags_add_hint)) },
                    singleLine = true,
                    isError = submitted && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (submitted && !valid) {
                    Text(
                        stringResource(R.string.session_tags_error_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        actions = {
            PomoButton(onClick = onDismiss, variant = PomoButtonVariant.Ghost) {
                Text(stringResource(android.R.string.cancel))
            }
            PomoButton(
                onClick = {
                    if (valid) onConfirm(name) else submitted = true
                },
                variant = PomoButtonVariant.Tonal,
                enabled = true,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
internal fun TagPickerSheet(
    tags: List<String>,
    currentTag: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val tagStore = remember { TagStore(LocalContext.current) }

    PomoSheet(title = stringResource(R.string.session_tags_title), onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            PickerRow(
                label = stringResource(R.string.session_tags_untagged_picker),
                color = null,
                selected = currentTag == null,
                onClick = { onSelect(null) },
            )
            tags.forEach { tag ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                PickerRow(
                    label = tag,
                    color = tagColorSlot(tag, tags.indexOf(tag), tagStore),
                    selected = currentTag == tag,
                    onClick = { onSelect(tag) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PickerRow(
    label: String,
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .background(color, CircleShape),
            )
        } else {
            Icon(
                Icons.Outlined.LabelOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Checkbox(checked = selected, onCheckedChange = null)
    }
}
