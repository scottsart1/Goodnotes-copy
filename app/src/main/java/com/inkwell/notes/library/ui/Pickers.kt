package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.library.DropRules
import com.inkwell.notes.library.DropTarget
import com.inkwell.notes.library.FolderStyle
import com.inkwell.notes.library.ItemSet
import com.inkwell.notes.library.LibraryText
import com.inkwell.notes.library.LibraryUiState
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/**
 * "Move to folder": the whole folder tree with the library's top level first. Folders being
 * moved (and their sub-folders) are disabled, as is the place everything already is. A new
 * folder can be created inside the highlighted folder and is selected right away.
 */
@Composable
internal fun MoveDialog(
    items: ItemSet,
    state: LibraryUiState,
    canMoveTo: (DropTarget) -> Boolean,
    onMove: (DropTarget) -> Unit,
    onCreateFolder: (name: String, parentId: String?, onCreated: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    val forbidden = remember(items, state.index) { DropRules.forbiddenTargets(items, state.index) }
    var target by remember { mutableStateOf<DropTarget?>(null) }
    var naming by remember { mutableStateOf(false) }
    val rows = state.index.allRows()
    LibDialog(
        title = pluralStringResource(R.plurals.lib_dialog_move_title, items.size, items.size),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.lib_move_here),
        onConfirm = { target?.let(onMove) },
        confirmEnabled = target?.let(canMoveTo) == true,
        dismissLabel = stringResource(R.string.lib_cancel),
        wide = true,
    ) {
        Column {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                item(key = "root") {
                    PickerRow(
                        icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                        iconTint = c.inkMuted,
                        label = stringResource(R.string.lib_move_root),
                        depth = 0,
                        selected = target == DropTarget.Root,
                        enabled = canMoveTo(DropTarget.Root),
                        hint = if (!canMoveTo(DropTarget.Root)) stringResource(R.string.lib_move_current) else null,
                        onClick = { target = DropTarget.Root },
                    )
                }
                items(rows, key = { it.folder.id }) { row ->
                    val t = DropTarget.IntoFolder(row.folder.id)
                    val allowed = row.folder.id !in forbidden && canMoveTo(t)
                    PickerRow(
                        icon = FolderStyle.icon(row.folder.icon),
                        iconTint = FolderStyle.color(row.folder.color, c.inkMuted),
                        label = row.folder.name,
                        depth = row.depth + 1,
                        selected = target == t,
                        enabled = allowed,
                        hint = if (row.folder.id !in forbidden && !allowed) stringResource(R.string.lib_move_current) else null,
                        onClick = { target = t },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(R8)
                    .clickable(role = Role.Button) { naming = true }
                    .heightIn(min = Dimens.minTouch)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(Dimens.icon), tint = c.accent)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.lib_new_folder), style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        }
    }
    if (naming) {
        val parentId = (target as? DropTarget.IntoFolder)?.folderId
        val parentName = parentId?.let { state.index[it]?.name }
        NameDialog(
            title = parentName?.let { stringResource(R.string.lib_dialog_new_folder_in, it) } ?: stringResource(R.string.lib_dialog_new_folder),
            initial = "",
            confirmLabel = stringResource(R.string.lib_create),
            onConfirm = { name ->
                naming = false
                onCreateFolder(name, parentId) { id -> target = DropTarget.IntoFolder(id) }
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    depth: Int,
    selected: Boolean,
    enabled: Boolean,
    hint: String?,
    onClick: () -> Unit,
) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouch)
            .clip(R8)
            .background(if (selected) c.accentContainer else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = (8 + depth * 18).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(Dimens.icon), tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) c.ink else c.inkMuted.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
    }
}

internal fun kindIcon(kind: DocKind): ImageVector = when (kind) {
    DocKind.NOTEBOOK -> Icons.Outlined.EditNote
    DocKind.PDF -> Icons.Outlined.PictureAsPdf
    DocKind.WHITEBOARD -> Icons.Outlined.Gesture
    DocKind.TYPED -> Icons.AutoMirrored.Outlined.Article
}

/** "Open side by side with...": pick the second document. */
@Composable
internal fun SplitPickerDialog(
    doc: DocumentMeta,
    candidates: List<DocumentMeta>,
    nowMs: Long,
    onPick: (DocumentMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    var query by remember { mutableStateOf("") }
    val others = remember(candidates, doc.id) {
        candidates.filter { it.id != doc.id }.sortedByDescending { maxOf(it.openedAt, it.modifiedAt) }
    }
    val shown = others.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    LibDialog(
        title = stringResource(R.string.lib_dialog_split),
        onDismiss = onDismiss,
        confirmLabel = null,
        onConfirm = {},
        dismissLabel = stringResource(R.string.lib_cancel),
        wide = true,
    ) {
        Column {
            Text(
                stringResource(R.string.lib_split_subtitle, doc.title),
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            if (others.isEmpty()) {
                Text(stringResource(R.string.lib_split_none), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.lib_split_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                shape = R8,
                colors = libTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(shown, key = { it.id }) { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(R8)
                            .clickable(role = Role.Button) { onPick(d) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(c.surfaceAlt), contentAlignment = Alignment.Center) {
                            Icon(kindIcon(d.kind), null, Modifier.size(18.dp), tint = c.inkMuted)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                LibraryText.metaLine(d, nowMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
