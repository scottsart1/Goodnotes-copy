package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.StorageInfo
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.library.FolderStyle
import com.inkwell.notes.library.LibraryText
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** Single text field dialog (rename document or folder, new folder name). */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank()
    LibDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = { if (valid) onConfirm(value.text.trim()) },
        confirmEnabled = valid,
        dismissLabel = stringResource(R.string.lib_cancel),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            label = { Text(stringResource(R.string.lib_name)) },
            shape = R8,
            colors = libTextFieldColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text.trim()) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Name (optional), colour and icon of a folder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderEditorDialog(
    title: String,
    initialName: String?,
    initialColor: Int,
    initialIcon: String,
    confirmLabel: String,
    onConfirm: (name: String, color: Int, icon: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    var name by remember { mutableStateOf(TextFieldValue(initialName.orEmpty(), TextRange(0, initialName.orEmpty().length))) }
    var color by remember { mutableIntStateOf(initialColor) }
    var icon by remember { mutableStateOf(initialIcon.ifBlank { FolderStyle.DEFAULT_ICON }) }
    val focus = remember { FocusRequester() }
    val valid = initialName == null || name.text.isNotBlank()
    LibDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = { if (valid) onConfirm(name.text.trim(), color, icon) },
        confirmEnabled = valid,
        dismissLabel = stringResource(R.string.lib_cancel),
        wide = true,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (initialName != null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.lib_name)) },
                    shape = R8,
                    colors = libTextFieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(20.dp))
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
            Text(stringResource(R.string.lib_colour), style = MaterialTheme.typography.titleSmall, color = c.inkMuted)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FolderStyle.swatches.forEachIndexed { i, argb ->
                    val selected = argb == color
                    val swatch = FolderStyle.color(argb, c.inkMuted)
                    val label = FolderStyle.swatchNames.getOrElse(i) { "" }
                    Box(
                        Modifier
                            .size(Dimens.minTouch)
                            .clip(CircleShape)
                            .selectable(selected = selected, role = Role.RadioButton) { color = argb }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (argb == 0) c.surfaceAlt else swatch)
                                .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.outline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = if (argb == 0) c.ink else c.onAccent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.lib_icon), style = MaterialTheme.typography.titleSmall, color = c.inkMuted)
            Spacer(Modifier.height(8.dp))
            val tint = FolderStyle.color(color, c.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (n in FolderStyle.iconNames) {
                    val selected = n == icon
                    Box(
                        Modifier
                            .size(Dimens.minTouch)
                            .clip(R8)
                            .background(if (selected) c.accentContainer else Color.Transparent)
                            .border(1.dp, if (selected) c.accent else Color.Transparent, R8)
                            .selectable(selected = selected, role = Role.RadioButton) { icon = n }
                            .semantics { contentDescription = FolderStyle.iconLabel(n) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(FolderStyle.icon(n), null, Modifier.size(Dimens.icon), tint = if (selected) tint else c.inkMuted) }
                }
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    LibDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        destructive = destructive,
        dismissLabel = stringResource(R.string.lib_cancel),
    ) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.inkMuted)
    }
}

/** Simple tag editor: current tags as removable chips, a field to add, and suggestions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsDialog(initial: List<String>, suggestions: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    var tags by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun add(raw: String) {
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val existing = tags.map { it.lowercase() }.toMutableSet()
        val next = tags.toMutableList()
        for (p in parts) if (existing.add(p.lowercase())) next += p.take(48)
        tags = next
        input = ""
    }
    LibDialog(
        title = stringResource(R.string.lib_dialog_tags),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.lib_save),
        onConfirm = {
            val pending = input.trim()
            val final = if (pending.isNotEmpty() && tags.none { it.equals(pending, ignoreCase = true) }) tags + pending else tags
            onSave(final)
        },
        dismissLabel = stringResource(R.string.lib_cancel),
        wide = true,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (tags.isEmpty()) {
                Text(stringResource(R.string.lib_tags_none), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in tags) TagChip(t, onRemove = { tags = tags - t })
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { v -> if (v.endsWith(",")) add(v) else input = v },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.lib_tags_add_hint)) },
                shape = R8,
                colors = libTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(input) }),
                trailingIcon = {
                    if (input.isNotBlank()) {
                        Text(
                            stringResource(R.string.lib_tags_add),
                            style = MaterialTheme.typography.labelLarge,
                            color = c.accent,
                            modifier = Modifier.clip(R8).clickable(role = Role.Button) { add(input) }.padding(horizontal = 10.dp, vertical = 12.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            val unused = suggestions.filter { s -> tags.none { it.equals(s, ignoreCase = true) } }
                .filter { input.isBlank() || it.contains(input.trim(), ignoreCase = true) }
                .take(16)
            if (unused.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.lib_tags_suggestions), style = MaterialTheme.typography.titleSmall, color = c.inkMuted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in unused) TagChip(s, onClick = { tags = tags + s })
                }
            }
        }
    }
}

@Composable
private fun TagChip(text: String, onRemove: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val c = Inkwell.colors
    Row(
        Modifier
            .heightIn(min = Dimens.minTouch)
            .padding(vertical = 4.dp)
            .clip(R8)
            .background(if (onRemove != null) c.accentContainer else c.surface)
            .border(1.dp, if (onRemove != null) c.accent.copy(alpha = 0.4f) else c.outline, R8)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = if (onRemove != null) 2.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) {
            val label = stringResource(R.string.lib_tag_remove, text)
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, label, Modifier.size(14.dp), tint = c.inkMuted) }
        }
    }
}

/** Storage breakdown: what Inkwell uses (pages, PDFs, audio, other) and free space. */
@Composable
internal fun StorageDialog(info: StorageInfo?, onManage: () -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    LibDialog(
        title = stringResource(R.string.lib_storage_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.lib_close),
        onConfirm = onDismiss,
        dismissLabel = null,
        wide = true,
    ) {
        if (info == null) {
            Text(stringResource(R.string.lib_storage_loading), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            return@LibDialog
        }
        val other = (info.totalBytes - info.inkBytes - info.pdfBytes - info.audioBytes).coerceAtLeast(0)
        val parts = listOf(
            Triple(stringResource(R.string.lib_storage_ink), info.inkBytes, c.accent),
            Triple(stringResource(R.string.lib_storage_pdf), info.pdfBytes, c.inkMuted),
            Triple(stringResource(R.string.lib_storage_audio), info.audioBytes, c.success),
            Triple(stringResource(R.string.lib_storage_other), other, c.warning),
        )
        Column {
            Text(
                LibraryText.storageSummary(info.documentCount, info.totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
            )
            Spacer(Modifier.height(16.dp))
            val total = info.totalBytes.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(10.dp).clip(R8).background(c.surfaceAlt)) {
                for ((_, bytes, color) in parts) {
                    val f = bytes.toFloat() / total
                    if (f > 0.002f) Box(Modifier.weight(f).height(10.dp).background(color))
                }
                val rest = 1f - parts.sumOf { it.second }.toFloat() / total
                if (rest > 0.002f) Spacer(Modifier.weight(rest))
            }
            Spacer(Modifier.height(16.dp))
            for ((label, bytes, color) in parts) StorageLine(label, LibraryText.formatBytes(bytes), color)
            Spacer(Modifier.height(8.dp))
            Hairline()
            Spacer(Modifier.height(8.dp))
            StorageLine(stringResource(R.string.lib_storage_used), LibraryText.formatBytes(info.totalBytes), null)
            StorageLine(stringResource(R.string.lib_storage_free), LibraryText.formatBytes(info.freeBytes), null)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.lib_storage_manage),
                style = MaterialTheme.typography.labelLarge,
                color = c.accent,
                modifier = Modifier.clip(R8).clickable(role = Role.Button, onClick = onManage).padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun StorageLine(label: String, value: String, color: Color?) {
    val c = Inkwell.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    }
}

/** Format choice for bulk export; the folder picker follows. */
@Composable
internal fun ExportFormatDialog(count: Int, onChoose: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    val formats = listOf(
        ExportFormat.PDF to R.string.lib_export_format_pdf,
        ExportFormat.MARKDOWN to R.string.lib_export_format_md,
        ExportFormat.TEXT to R.string.lib_export_format_txt,
        ExportFormat.DOCX to R.string.lib_export_format_docx,
        ExportFormat.PNG to R.string.lib_export_format_png,
        ExportFormat.INKWELL to R.string.lib_export_format_inkwell,
    )
    var chosen by remember { mutableStateOf(ExportFormat.PDF) }
    LibDialog(
        title = pluralStringResource(R.plurals.lib_dialog_export_title, count, count),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.lib_export_choose_folder),
        onConfirm = { onChoose(chosen) },
        dismissLabel = stringResource(R.string.lib_cancel),
    ) {
        Column {
            Text(stringResource(R.string.lib_export_body), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            Spacer(Modifier.height(12.dp))
            for ((format, label) in formats) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.minTouch)
                        .clip(R8)
                        .selectable(selected = chosen == format, role = Role.RadioButton) { chosen = format }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = chosen == format,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.inkMuted),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
        }
    }
}
