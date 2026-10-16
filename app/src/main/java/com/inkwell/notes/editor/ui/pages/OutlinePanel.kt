package com.inkwell.notes.editor.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.logic.OutlineTree
import com.inkwell.notes.editor.ui.MenuItemSpec
import com.inkwell.notes.editor.ui.OverflowMenu
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.components.SectionLabel
import com.inkwell.notes.editor.ui.toolbar.ActionRow
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.flow.catch

/** Bookmarks and the document outline; tapping an entry goes to its page. */
@Composable
fun OutlinePanelContent(
    controller: NotebookController,
    onNavigate: (Int) -> Unit,
    onAddCurrentPage: () -> Unit,
    onRename: (OutlineEntry) -> Unit,
    onGenerate: () -> Unit,
    aiAvailable: Boolean,
) {
    val c = Inkwell.colors
    val outlineFlow = remember(controller.docId) { controller.graph.library.observeOutline(controller.docId).catch { emit(emptyList()) } }
    val entries by outlineFlow.collectAsState(initial = emptyList())
    val pages = controller.pages
    val indexById = pages.withIndex().associate { it.value.id to it.index }
    val nodes = OutlineTree.flatten(entries, indexById)
    val bookmarks = pages.withIndex().filter { it.value.bookmarked }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                ActionRow(Icons.Outlined.Add, stringResource(R.string.editor_outline_add_current), onAddCurrentPage, enabled = pages.isNotEmpty())
                if (aiAvailable) ActionRow(Icons.Outlined.AutoAwesome, stringResource(R.string.editor_outline_generate), onGenerate, enabled = pages.isNotEmpty())
            }
            HorizontalDivider(color = c.outline)
        }
        item { SectionLabel(stringResource(R.string.editor_bookmarks), Modifier.padding(horizontal = 16.dp)) }
        if (bookmarks.isEmpty()) {
            item { Hint(stringResource(R.string.editor_bookmarks_empty)) }
        } else {
            items(bookmarks, key = { "b" + it.value.id }) { (index, page) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.minTouch)
                        .clickable(role = Role.Button) { onNavigate(index) }
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Bookmark, null, Modifier.size(18.dp), tint = c.accent)
                    Text(
                        page.bookmarkTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.editor_page_n, index + 1),
                        style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.padding(horizontal = 8.dp))
                    IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.editor_remove_bookmark), { controller.toggleBookmark(page) }, iconSize = 18.dp)
                }
            }
        }
        item { SectionLabel(stringResource(R.string.editor_outline), Modifier.padding(start = 16.dp, top = 8.dp)) }
        if (nodes.isEmpty()) {
            item { Hint(stringResource(if (aiAvailable) R.string.editor_outline_empty_ai else R.string.editor_outline_empty_hint)) }
        } else {
            items(nodes, key = { "o" + it.entry.id }) { node ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.minTouch)
                        .clickable(role = Role.Button, enabled = node.pageIndex != null) { node.pageIndex?.let(onNavigate) }
                        .padding(start = (16 + node.depth * 18).dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        node.entry.title,
                        style = if (node.depth == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = if (node.pageIndex == null) c.inkMuted else c.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(node.pageIndex?.let { "${it + 1}" } ?: "–", style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.padding(horizontal = 8.dp))
                    Box {
                        IconAction(Icons.Outlined.MoreVert, stringResource(R.string.editor_outline_entry_actions), { menu = true }, iconSize = 18.dp)
                        OverflowMenu(
                            menu, { menu = false },
                            listOf(
                                MenuItemSpec(stringResource(R.string.editor_rename), Icons.Outlined.Edit) { onRename(node.entry) },
                                MenuItemSpec(stringResource(R.string.editor_delete), Icons.Outlined.DeleteOutline, 1) {
                                    controller.launchOp(R.string.editor_outline_delete_failed) {
                                        // Children move up a level instead of disappearing.
                                        entries.filter { it.parentId == node.entry.id }.forEach {
                                            controller.graph.library.upsertOutlineEntry(it.copy(parentId = node.entry.parentId))
                                        }
                                        controller.graph.library.deleteOutlineEntry(node.entry.id)
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Inkwell.colors.inkMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
