package com.inkwell.notes.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.editor.logic.OpenTab
import com.inkwell.notes.editor.logic.TopBarAction
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** One entry of an overflow menu; entries with a different [section] are separated by a divider. */
data class MenuItemSpec(
    val label: String,
    val icon: ImageVector? = null,
    val section: Int = 0,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun OverflowMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItemSpec>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        var lastSection: Int? = null
        items.forEach { item ->
            if (lastSection != null && item.section != lastSection) HorizontalDivider(color = Inkwell.colors.outline)
            lastSection = item.section
            DropdownMenuItem(
                text = { Text(item.label) },
                leadingIcon = item.icon?.let { { Icon(it, null, Modifier.size(Dimens.icon)) } },
                trailingIcon = if (item.checked) {
                    { Icon(Icons.Outlined.Check, null, Modifier.size(Dimens.icon)) }
                } else null,
                enabled = item.enabled,
                onClick = { onDismiss(); item.onClick() },
            )
        }
    }
}

fun topBarIcon(a: TopBarAction): ImageVector = when (a) {
    TopBarAction.SEARCH -> Icons.Outlined.Search
    TopBarAction.PAGES -> Icons.Outlined.GridView
    TopBarAction.OUTLINE -> Icons.AutoMirrored.Outlined.Toc
    TopBarAction.AI -> Icons.Outlined.AutoAwesome
    TopBarAction.RECORDINGS -> Icons.Outlined.GraphicEq
    TopBarAction.EXPORT -> Icons.Outlined.IosShare
    TopBarAction.FOCUS -> Icons.Outlined.CenterFocusWeak
}

fun topBarLabel(a: TopBarAction): Int = when (a) {
    TopBarAction.SEARCH -> R.string.editor_action_search
    TopBarAction.PAGES -> R.string.editor_action_pages
    TopBarAction.OUTLINE -> R.string.editor_action_outline
    TopBarAction.AI -> R.string.editor_ai
    TopBarAction.RECORDINGS -> R.string.editor_action_recordings
    TopBarAction.EXPORT -> R.string.editor_action_export
    TopBarAction.FOCUS -> R.string.editor_action_focus
}

/**
 * The editor's slim 48dp top bar: back, title (tap to rename), automatic-naming chip,
 * "Page X of N" and the document actions; what does not fit goes into the overflow menu.
 */
@Composable
fun EditorTopBar(
    title: String,
    autoNameChip: String?,
    pageLabel: String?,
    actions: List<TopBarAction>,
    activeActions: Set<TopBarAction>,
    onBack: (() -> Unit)?,
    onTitleClick: () -> Unit,
    onPageLabelClick: () -> Unit,
    onAction: (TopBarAction) -> Unit,
    overflow: List<MenuItemSpec>,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = Dimens.topBarHeight,
) {
    val c = Inkwell.colors
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.editor_back), onBack)
            val renameLabel = stringResource(R.string.editor_rename)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.minTouch)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = renameLabel, onClick = onTitleClick)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (autoNameChip != null) {
                    Text(
                        autoNameChip,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.inkMuted,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, c.outline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            if (pageLabel != null) {
                Text(
                    pageLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkMuted,
                    maxLines = 1,
                    modifier = Modifier
                        .heightIn(min = Dimens.minTouch)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onPageLabelClick)
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                )
            }
            actions.forEach { a ->
                IconAction(topBarIcon(a), stringResource(topBarLabel(a)), { onAction(a) }, selected = a in activeActions)
            }
            trailing?.invoke()
            if (overflow.isNotEmpty()) {
                Box {
                    IconAction(Icons.Outlined.MoreVert, stringResource(R.string.editor_more), { menuOpen = true })
                    OverflowMenu(menuOpen, { menuOpen = false }, overflow)
                }
            }
        }
        HorizontalDivider(color = c.outline)
    }
}

/** Slim strip of documents opened in this session. */
@Composable
fun TabsStrip(tabs: List<OpenTab>, currentDocId: String, onOpen: (String) -> Unit, onClose: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val state = rememberLazyListState()
    val currentIndex = tabs.indexOfFirst { it.docId == currentDocId }
    LaunchedEffect(currentIndex) { if (currentIndex >= 0) state.animateScrollToItem(currentIndex) }
    Column(modifier.fillMaxWidth().background(c.ground)) {
        LazyRow(state = state, modifier = Modifier.fillMaxWidth().height(Dimens.minTouch).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            items(tabs, key = { it.docId }) { tab ->
                val active = tab.docId == currentDocId
                val untitled = stringResource(R.string.editor_untitled)
                val closeLabel = stringResource(R.string.editor_close_tab, tab.title.ifEmpty { untitled })
                Box(Modifier.padding(horizontal = 2.dp).height(Dimens.minTouch)) {
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                            .border(1.dp, if (active) c.outline else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable(role = Role.Tab, enabled = !active) { onOpen(tab.docId) }
                            .semantics { selected = active }
                            .padding(start = 12.dp, end = 34.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tab.title.ifEmpty { untitled },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) c.ink else c.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(width = 36.dp, height = Dimens.minTouch)
                            .clickable(role = Role.Button) { onClose(tab.docId) }
                            .semantics { contentDescription = closeLabel },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, null, Modifier.size(14.dp), tint = c.inkMuted) }
                }
            }
        }
        HorizontalDivider(color = c.outline)
    }
}
