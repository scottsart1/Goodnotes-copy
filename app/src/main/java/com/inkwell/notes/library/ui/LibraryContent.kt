package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibraryView
import com.inkwell.notes.library.CardDecor
import com.inkwell.notes.library.DropTarget
import com.inkwell.notes.library.ItemKey
import com.inkwell.notes.library.LibraryCollection
import com.inkwell.notes.library.LibraryLocation
import com.inkwell.notes.library.LibraryUiState
import com.inkwell.notes.ui.theme.Inkwell

/** The pinned row, folders and documents of the current view, as a grid or a list. */
@Composable
internal fun LibraryContent(
    state: LibraryUiState,
    decor: Map<String, CardDecor>,
    env: CardEnv,
    controller: LibraryController,
    drag: DragDropState,
    aiNaming: Boolean,
    columns: Int,
    horizontalPadding: Dp,
    onEmptyAction: () -> Unit,
    onDeepSearch: (String) -> Unit,
    gridState: LazyGridState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (!state.loaded) {
        Box(modifier.fillMaxSize())
        return
    }
    if (state.folderMissing || state.isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyView(state, onEmptyAction, onDeepSearch)
        }
        return
    }
    val showSections = state.pinned.isNotEmpty() || state.subFolders.isNotEmpty()
    if (state.view == LibraryView.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            state = gridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = horizontalPadding - 8.dp, end = horizontalPadding - 8.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.query.isNotEmpty()) {
                item(key = "deep", span = { GridItemSpan(maxLineSpan) }) {
                    DeepSearchRow(state.query, onDeepSearch, Modifier.padding(horizontal = 8.dp))
                }
            }
            if (state.pinned.isNotEmpty()) {
                item(key = "h:pinned", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.lib_section_pinned), Modifier.padding(horizontal = 8.dp))
                }
                item(key = "pinned", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.pinned, key = { "p:" + it.id }) { doc ->
                            DocumentItem(doc, decor[doc.id], env, controller, drag, aiNaming, list = false, modifier = Modifier.width(184.dp))
                        }
                    }
                }
            }
            if (state.subFolders.isNotEmpty()) {
                item(key = "h:folders", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.lib_section_folders), Modifier.padding(horizontal = 8.dp))
                }
                items(state.subFolders, key = { "f:" + it.id }) { folder ->
                    FolderItem(folder, state.folderCounts[folder.id] ?: 0, controller, drag, Modifier.padding(horizontal = 8.dp))
                }
            }
            if (state.documents.isNotEmpty()) {
                if (showSections) {
                    item(key = "h:files", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(stringResource(R.string.lib_section_files), Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                items(state.documents, key = { "d:" + it.id }) { doc ->
                    DocumentItem(doc, decor[doc.id], env, controller, drag, aiNaming, list = false)
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = horizontalPadding - 8.dp, end = horizontalPadding - 8.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.query.isNotEmpty()) {
                item(key = "deep") { DeepSearchRow(state.query, onDeepSearch, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
            }
            if (state.pinned.isNotEmpty()) {
                item(key = "h:pinned") { SectionHeader(stringResource(R.string.lib_section_pinned), Modifier.padding(horizontal = 8.dp)) }
                items(state.pinned, key = { "p:" + it.id }) { doc ->
                    DocumentItem(doc, decor[doc.id], env, controller, drag, aiNaming, list = true)
                }
            }
            if (state.subFolders.isNotEmpty()) {
                item(key = "h:folders") { SectionHeader(stringResource(R.string.lib_section_folders), Modifier.padding(horizontal = 8.dp)) }
                items(state.subFolders, key = { "f:" + it.id }) { folder ->
                    FolderItem(folder, state.folderCounts[folder.id] ?: 0, controller, drag, Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            if (state.documents.isNotEmpty()) {
                if (showSections) {
                    item(key = "h:files") { SectionHeader(stringResource(R.string.lib_section_files), Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                }
                items(state.documents, key = { "d:" + it.id }) { doc ->
                    DocumentItem(doc, decor[doc.id], env, controller, drag, aiNaming, list = true)
                }
            }
        }
    }
}

@Composable
private fun DocumentItem(
    doc: DocumentMeta,
    decor: CardDecor?,
    env: CardEnv,
    controller: LibraryController,
    drag: DragDropState,
    aiNaming: Boolean,
    list: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val key = ItemKey.Doc(doc.id)
    val selected = key in controller.selection
    var pointerMenu by remember { mutableStateOf<DpOffset?>(null) }
    var buttonMenu by remember { mutableStateOf(false) }
    var heightPx by remember { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val openLabel = stringResource(R.string.lib_action_open)
    val selectLabel = stringResource(R.string.lib_select)
    val moreLabel = stringResource(R.string.lib_action_more_for, doc.title)
    val onAction: (DocAction) -> Unit = { a ->
        buttonMenu = false
        pointerMenu = null
        controller.onDocAction(doc, a)
    }
    Box(
        modifier
            .clip(R12)
            .background(if (selected) c.accentContainer else Color.Transparent)
            .onSizeChanged { heightPx = it.height }
            .itemGestures(
                key = doc.id,
                interactionSource = interaction,
                drag = drag,
                onTap = { controller.tapDocument(doc) },
                onLongPress = { controller.longPress(key) },
                onSecondaryClick = { pos ->
                    pointerMenu = with(density) { DpOffset(pos.x.toDp(), (pos.y - heightPx).toDp()) }
                },
                payload = { controller.payloadFor(key) },
                onDrop = controller::drop,
            )
            .indication(interaction, ripple())
            .semantics {
                role = Role.Button
                this.selected = selected
                onClick(openLabel) { controller.tapDocument(doc); true }
                onLongClick(selectLabel) { controller.longPress(key); true }
            },
    ) {
        val more: @Composable () -> Unit = {
            Box {
                MoreButton(moreLabel) { buttonMenu = true }
                DropdownMenu(expanded = buttonMenu, onDismissRequest = { buttonMenu = false }, shape = R12, containerColor = c.surface) {
                    DocumentMenuItems(doc, aiNaming, onAction)
                }
            }
        }
        if (list) DocumentListRow(doc, decor, env, selected, more) else DocumentGridCard(doc, decor, env, selected, more)
        DropdownMenu(
            expanded = pointerMenu != null,
            onDismissRequest = { pointerMenu = null },
            offset = pointerMenu ?: DpOffset.Zero,
            shape = R12,
            containerColor = c.surface,
        ) { DocumentMenuItems(doc, aiNaming, onAction) }
    }
}

@Composable
private fun FolderItem(folder: Folder, count: Int, controller: LibraryController, drag: DragDropState, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val key = ItemKey.Fold(folder.id)
    val selected = key in controller.selection
    val target = DropTarget.IntoFolder(folder.id)
    var pointerMenu by remember { mutableStateOf<DpOffset?>(null) }
    var buttonMenu by remember { mutableStateOf(false) }
    var heightPx by remember { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val openLabel = stringResource(R.string.lib_folder_open)
    val selectLabel = stringResource(R.string.lib_select)
    val moreLabel = stringResource(R.string.lib_action_more_for, folder.name)
    val onAction: (FolderAction) -> Unit = { a ->
        buttonMenu = false
        pointerMenu = null
        controller.onFolderAction(folder, a)
    }
    Box(
        modifier
            .clip(R12)
            .dropTarget(drag, target)
            .onSizeChanged { heightPx = it.height }
            .itemGestures(
                key = folder.id,
                interactionSource = interaction,
                drag = drag,
                onTap = { controller.tapFolder(folder) },
                onLongPress = { controller.longPress(key) },
                onSecondaryClick = { pos ->
                    pointerMenu = with(density) { DpOffset(pos.x.toDp(), (pos.y - heightPx).toDp()) }
                },
                payload = { controller.payloadFor(key) },
                onDrop = controller::drop,
            )
            .indication(interaction, ripple())
            .semantics {
                role = Role.Button
                this.selected = selected
                onClick(openLabel) { controller.tapFolder(folder); true }
                onLongClick(selectLabel) { controller.longPress(key); true }
            },
    ) {
        FolderTile(
            folder = folder,
            itemCount = count,
            selected = selected,
            dropHovered = drag.hovered == target,
            selectionMode = controller.selectionMode,
            moreButton = {
                Box {
                    MoreButton(moreLabel) { buttonMenu = true }
                    DropdownMenu(expanded = buttonMenu, onDismissRequest = { buttonMenu = false }, shape = R12, containerColor = c.surface) {
                        FolderMenuItems(onAction)
                    }
                }
            },
        )
        DropdownMenu(
            expanded = pointerMenu != null,
            onDismissRequest = { pointerMenu = null },
            offset = pointerMenu ?: DpOffset.Zero,
            shape = R12,
            containerColor = c.surface,
        ) { FolderMenuItems(onAction) }
    }
}

@Composable
private fun DeepSearchRow(query: String, onDeepSearch: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(R12)
            .border(1.dp, c.outline, R12)
            .clickable(role = Role.Button) { onDeepSearch(query) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = c.accent)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.lib_deep_search_row, query),
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = c.inkMuted)
    }
}

@Composable
private fun EmptyView(state: LibraryUiState, onEmptyAction: () -> Unit, onDeepSearch: (String) -> Unit) {
    if (state.query.isNotEmpty()) {
        EmptyState(
            Icons.Outlined.Search,
            stringResource(R.string.lib_empty_search_title, state.query),
            stringResource(R.string.lib_empty_search_body),
            stringResource(R.string.lib_empty_action_deep_search),
            { onDeepSearch(state.query) },
        )
        return
    }
    if (state.folderMissing) {
        EmptyState(
            Icons.Outlined.FolderOff,
            stringResource(R.string.lib_folder_missing_title),
            stringResource(R.string.lib_folder_missing_body),
            stringResource(R.string.lib_empty_action_back),
            onEmptyAction,
        )
        return
    }
    when (val loc = state.location) {
        LibraryLocation.Root -> EmptyState(
            Icons.AutoMirrored.Outlined.LibraryBooks,
            stringResource(R.string.lib_empty_root_title),
            stringResource(R.string.lib_empty_root_body),
            stringResource(R.string.lib_empty_action_new_notebook),
            onEmptyAction,
        )
        is LibraryLocation.InFolder -> EmptyState(
            Icons.Outlined.FolderOpen,
            stringResource(R.string.lib_empty_folder_title),
            stringResource(R.string.lib_empty_folder_body),
            stringResource(R.string.lib_empty_action_new_notebook),
            onEmptyAction,
        )
        is LibraryLocation.Collection -> {
            val (icon, texts) = when (loc.collection) {
                LibraryCollection.RECENT -> Icons.Outlined.History to Triple(R.string.lib_empty_recent_title, R.string.lib_empty_recent_body, R.string.lib_empty_action_browse)
                LibraryCollection.FAVORITES -> Icons.Outlined.StarOutline to Triple(R.string.lib_empty_favorites_title, R.string.lib_empty_favorites_body, R.string.lib_empty_action_browse)
                LibraryCollection.WHITEBOARDS -> Icons.Outlined.Gesture to Triple(R.string.lib_empty_whiteboards_title, R.string.lib_empty_whiteboards_body, R.string.lib_empty_action_new_whiteboard)
                LibraryCollection.DOCUMENTS -> Icons.AutoMirrored.Outlined.Article to Triple(R.string.lib_empty_documents_title, R.string.lib_empty_documents_body, R.string.lib_empty_action_new_typed)
                LibraryCollection.PDFS -> Icons.Outlined.PictureAsPdf to Triple(R.string.lib_empty_pdfs_title, R.string.lib_empty_pdfs_body, R.string.lib_empty_action_import_pdf)
                LibraryCollection.ARCHIVE -> Icons.Outlined.Archive to Triple(R.string.lib_empty_archive_title, R.string.lib_empty_archive_body, R.string.lib_empty_action_back)
            }
            EmptyState(icon, stringResource(texts.first), stringResource(texts.second), stringResource(texts.third), onEmptyAction)
        }
    }
}
