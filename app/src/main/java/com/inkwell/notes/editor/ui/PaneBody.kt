package com.inkwell.notes.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.ai.ui.AiPanel
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.audio.ui.RecordingsPanel
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.logic.Placement
import com.inkwell.notes.editor.pageAiContext
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.components.Segmented
import com.inkwell.notes.editor.ui.pages.OutlinePanelContent
import com.inkwell.notes.editor.ui.pages.PageCallbacks
import com.inkwell.notes.editor.ui.pages.PagesPanelContent
import com.inkwell.notes.editor.ui.panels.OverlayPanel
import com.inkwell.notes.editor.ui.panels.PanelHeader
import com.inkwell.notes.editor.ui.panels.SearchPanelContent
import com.inkwell.notes.editor.ui.panels.SidePanel
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.launch

private const val LEFT_PANEL_DP = 300f
private const val SEARCH_PANEL_DP = 360f

/**
 * Canvas plus side panels. Panels sit beside the canvas when there is room, float over its
 * edge in narrower panes, and become a bottom sheet (pages) or cover the pane (others) on phones.
 * The canvas keeps its place in the composition so it is never recreated.
 */
@Composable
fun PaneBody(
    controller: NotebookController,
    ui: PaneUiState,
    actions: PaneActions,
    nav: AppNavigator,
    paneWidthDp: Float,
    aiOutlineAvailable: Boolean,
    modifier: Modifier = Modifier,
    canvasContent: @Composable () -> Unit,
) {
    val left = ui.left
    val right = ui.right
    val leftMode = left?.let { Placement.panelMode(paneWidthDp, LEFT_PANEL_DP) }
    val pushLeft = leftMode == Placement.PanelMode.PUSH
    val remaining = paneWidthDp - if (pushLeft) LEFT_PANEL_DP else 0f
    val resizable = right == RightPanel.AI || right == RightPanel.RECORDINGS
    val rightWidth = Placement.panelWidth(if (resizable) ui.rightWidth.value else SEARCH_PANEL_DP, remaining)
    val rightMode = right?.let { Placement.panelMode(remaining, rightWidth) }

    val pageCallbacks = PageCallbacks(
        onPaper = { ui.dialog = PaneDialog.Paper(it) },
        onExport = { indices -> controller.uiScope.launch { controller.saveNow(); ui.dialog = PaneDialog.Export(indices) } },
        onCopyTo = { ui.dialog = PaneDialog.PickCopyTarget(it) },
        onExtract = { actions.extractPages(it) },
        onDelete = { ui.dialog = PaneDialog.ConfirmDeletePages(it) },
        onBookmark = { ui.dialog = PaneDialog.BookmarkTitle(it.id) },
    )
    val leftContent: @Composable () -> Unit = {
        LeftPanelContent(controller, ui, actions, pageCallbacks, aiOutlineAvailable, closeOnNavigate = leftMode == Placement.PanelMode.FULL)
    }
    val rightContent: @Composable () -> Unit = { RightPanelContent(controller, ui, actions, nav) }

    Box(modifier) {
        Row(Modifier.fillMaxSize()) {
            if (left != null && pushLeft) SidePanel(LEFT_PANEL_DP.dp, atEnd = false) { leftContent() }
            Box(Modifier.weight(1f).fillMaxHeight()) { canvasContent() }
            if (right != null && rightMode == Placement.PanelMode.PUSH) {
                SidePanel(
                    rightWidth.dp,
                    atEnd = true,
                    onResize = if (resizable) ({ w -> ui.rightWidth = w.coerceIn(320.dp, 640.dp) }) else null,
                ) { rightContent() }
            }
        }
        if (left != null && leftMode == Placement.PanelMode.OVERLAY) OverlayPanel(LEFT_PANEL_DP.dp, atEnd = false) { leftContent() }
        if (right != null && rightMode == Placement.PanelMode.OVERLAY) OverlayPanel(rightWidth.dp, atEnd = true) { rightContent() }
        if (right != null && rightMode == Placement.PanelMode.FULL) OverlayPanel(null, atEnd = true) { rightContent() }
    }
    if (left != null && leftMode == Placement.PanelMode.FULL) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val height = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
        ModalBottomSheet(onDismissRequest = { ui.left = null }, sheetState = sheet, containerColor = Inkwell.colors.surface) {
            Column(Modifier.fillMaxWidth().height(height)) { leftContent() }
        }
    }
}

@Composable
private fun LeftPanelContent(
    controller: NotebookController,
    ui: PaneUiState,
    actions: PaneActions,
    callbacks: PageCallbacks,
    aiOutlineAvailable: Boolean,
    closeOnNavigate: Boolean,
) {
    val tab = ui.left ?: return
    val navigate: (Int) -> Unit = { index ->
        controller.scrollToPage(index)
        if (closeOnNavigate) ui.left = null
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Segmented(
                    options = listOf(LeftPanel.PAGES, LeftPanel.OUTLINE),
                    selected = tab,
                    label = { stringResource(if (it == LeftPanel.PAGES) R.string.editor_action_pages else R.string.editor_action_outline) },
                    onSelect = { ui.left = it },
                )
            }
            IconAction(Icons.Outlined.Close, stringResource(R.string.editor_close_panel), { ui.left = null })
        }
        HorizontalDivider(color = Inkwell.colors.outline)
        when (tab) {
            LeftPanel.PAGES -> PagesPanelContent(controller, callbacks, navigate)
            LeftPanel.OUTLINE -> OutlinePanelContent(
                controller = controller,
                onNavigate = navigate,
                onAddCurrentPage = { ui.dialog = PaneDialog.AddOutlineEntry },
                onRename = { ui.dialog = PaneDialog.RenameOutlineEntry(it) },
                onGenerate = actions::requestAiOutline,
                aiAvailable = aiOutlineAvailable,
            )
        }
    }
}

@Composable
private fun RightPanelContent(controller: NotebookController, ui: PaneUiState, actions: PaneActions, nav: AppNavigator) {
    when (ui.right) {
        RightPanel.AI -> AiPanel(
            context = ui.aiContext ?: controller.pageAiContext(),
            onInsert = { text ->
                if (text.isNotBlank()) controller.canvas.insertText(controller.currentPage, text.trim(), null, controller.tools.state.text, origin = "ai")
            },
            onOpenSource = { controller.scrollToPage(it) },
            onClose = { ui.right = null },
            modifier = Modifier.fillMaxSize(),
        )
        RightPanel.RECORDINGS -> RecordingsPanel(
            docId = controller.docId,
            onShowNotesAt = actions::showNotesAt,
            onClose = { ui.right = null },
            modifier = Modifier.fillMaxSize(),
            onInsert = { text ->
                if (text.isNotBlank()) controller.canvas.insertText(controller.currentPage, text.trim(), null, controller.tools.state.text, origin = "ai")
            },
        )
        RightPanel.SEARCH -> Column(Modifier.fillMaxSize()) {
            PanelHeader(stringResource(R.string.editor_search_title), onClose = { ui.right = null })
            SearchPanelContent(controller, ui.searchQuery, onReveal = actions::revealHit, onSearchEverywhere = { nav.openSearch(it) })
        }
        null -> Unit
    }
}
