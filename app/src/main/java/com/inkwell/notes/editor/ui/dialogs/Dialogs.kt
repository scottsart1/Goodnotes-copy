package com.inkwell.notes.editor.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.LibrarySort
import com.inkwell.notes.core.storage.db.ReusableElementEntity
import com.inkwell.notes.editor.ImageImporter
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.toolbar.ActionRow
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import java.io.File

/** Single text field dialog (rename, bookmark title, element name, preset name). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    allowEmpty: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val valid = allowEmpty || value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.widthIn(min = 280.dp, max = 480.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = it.text.take(200)) },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text.trim()) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Inkwell.colors.danger else Inkwell.colors.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

/** Picks another notebook or PDF from the library. */
@Composable
fun DocumentPickerDialog(
    graph: AppGraph,
    title: String,
    excludeIds: Set<String>,
    kinds: Set<DocKind>,
    onPick: (DocumentMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val flow = remember(kinds) {
        graph.library.observeDocuments(LibraryFilter(inFolderOnly = false, kinds = kinds, sort = LibrarySort.MODIFIED_DESC))
            .catch { emit(emptyList()) }
    }
    val docs by flow.collectAsState(initial = null)
    val shown = docs.orEmpty().filter { it.id !in excludeIds && it.trashedAt == null && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.widthIn(min = 300.dp, max = 520.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(Dimens.icon)) },
                    placeholder = { Text(stringResource(R.string.editor_picker_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    docs == null -> Unit
                    shown.isEmpty() -> Text(stringResource(R.string.editor_picker_empty), style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.inkMuted, modifier = Modifier.padding(vertical = 24.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                        items(shown, key = { it.id }) { d ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Dimens.minTouch)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) { onPick(d) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (d.kind == DocKind.PDF) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description, null, Modifier.size(Dimens.icon), tint = Inkwell.colors.inkMuted)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(d.title, style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        androidx.compose.ui.res.pluralStringResource(R.plurals.editor_page_count, d.pageCount, d.pageCount),
                                        style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

/** Where a picture for the image tool comes from. */
@Composable
fun ImageSourceDialog(onPhotos: () -> Unit, onCamera: () -> Unit, onPaste: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_insert_image)) },
        text = {
            Column(Modifier.widthIn(min = 260.dp)) {
                ActionRow(Icons.Outlined.PhotoLibrary, stringResource(R.string.editor_image_photos), onPhotos)
                ActionRow(Icons.Outlined.PhotoCamera, stringResource(R.string.editor_image_camera), onCamera)
                ActionRow(Icons.Outlined.ContentPaste, stringResource(R.string.editor_image_paste), onPaste)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

/** Saved reusable elements; tap inserts one into the current page. */
@Composable
fun MyElementsDialog(
    elements: List<ReusableElementEntity>?,
    onInsert: (ReusableElementEntity) -> Unit,
    onRename: (ReusableElementEntity) -> Unit,
    onDelete: (ReusableElementEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_my_elements)) },
        text = {
            Column(Modifier.widthIn(min = 300.dp, max = 520.dp)) {
                when {
                    elements == null -> Unit
                    elements.isEmpty() -> Text(stringResource(R.string.editor_elements_empty), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(elements, key = { it.id }) { e ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) { onInsert(e) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ElementThumb(e.thumbnailPath)
                                Text(e.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                IconAction(Icons.Outlined.Edit, stringResource(R.string.editor_rename), { onRename(e) }, iconSize = 18.dp)
                                IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.editor_delete), { onDelete(e) }, iconSize = 18.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_close)) } },
    )
}

@Composable
private fun ElementThumb(path: String?) {
    val c = Inkwell.colors
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { ImageImporter.decodeFile(File(p), 160) } }
    }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt).border(1.dp, c.outline, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.padding(4.dp).size(44.dp)) }
    }
}

/** Rename or delete a pen preset (long-press in the presets row). */
@Composable
fun PresetEditDialog(name: String, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    TextInputDialog(
        title = stringResource(R.string.editor_preset_edit),
        label = stringResource(R.string.editor_preset_name),
        initial = name,
        confirmLabel = stringResource(R.string.editor_save),
        onConfirm = onRename,
        onDismiss = onDismiss,
        extra = {
            TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.editor_preset_delete), color = Inkwell.colors.danger)
            }
        },
    )
}
