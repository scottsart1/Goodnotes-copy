package com.inkwell.notes.whiteboard

import android.content.ActivityNotFoundException
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkwell.notes.R
import com.inkwell.notes.ai.ui.AiPanel
import com.inkwell.notes.ai.ui.AiPanelContext
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.export.ui.ExportDialog
import com.inkwell.notes.ink.InkCanvasView
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.kit.CenteredState
import com.inkwell.notes.whiteboard.kit.CollectMessages
import com.inkwell.notes.whiteboard.kit.DarkToast
import com.inkwell.notes.whiteboard.kit.FileSharing
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.KitMessageHost
import com.inkwell.notes.whiteboard.kit.PillSurface
import com.inkwell.notes.whiteboard.kit.RenameDialog
import com.inkwell.notes.whiteboard.kit.SlimTopBar
import com.inkwell.notes.whiteboard.kit.UiMessage
import com.inkwell.notes.whiteboard.ui.AddNotebookPageDialog
import com.inkwell.notes.whiteboard.ui.BoardToolPill
import com.inkwell.notes.whiteboard.ui.ExportAction
import com.inkwell.notes.whiteboard.ui.ExportAreaOverlay
import com.inkwell.notes.whiteboard.ui.LongPressMenu
import com.inkwell.notes.whiteboard.ui.MenuRow
import com.inkwell.notes.whiteboard.ui.Minimap
import com.inkwell.notes.whiteboard.ui.PdfSnippetDialog
import com.inkwell.notes.whiteboard.ui.SelectionActions
import com.inkwell.notes.whiteboard.ui.SelectionBar
import com.inkwell.notes.whiteboard.ui.SendToNotebookDialog
import com.inkwell.notes.whiteboard.ui.StickyEditor
import com.inkwell.notes.whiteboard.ui.TextBoxEditor
import com.inkwell.notes.whiteboard.ui.ZoomControls
import com.inkwell.notes.whiteboard.ui.toolLabel
import kotlinx.coroutines.launch
import java.io.File

private enum class BoardDialog { PDF_SNIPPET, SEND_TO_NOTEBOOK, ADD_PAGE, RENAME, EXPORT_BOARD }

/**
 * Infinite whiteboard: one unbounded page on the ink canvas with its own tool pill
 * (connectors, sticky notes, PDF snippets, area export), a minimap, zoom controls and the
 * assistant panel. Content autosaves (debounced) and is flushed when the app pauses.
 */
@Composable
fun WhiteboardScreen(docId: String, nav: AppNavigator) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val controller = remember(docId) { WhiteboardController(graph, docId) }
    val scope = rememberCoroutineScope()
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(controller) { controller.load() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) controller.flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val c = Inkwell.colors
    Box(Modifier.fillMaxSize().background(c.desk)) {
        when (controller.loadState) {
            WhiteboardController.LoadState.Ready -> WhiteboardContent(controller, nav)
            WhiteboardController.LoadState.Loading -> Column(Modifier.fillMaxSize()) {
                TopBarFrame { SlimTopBar(title = "", onBack = nav::back) }
            }
            WhiteboardController.LoadState.Missing -> Column(Modifier.fillMaxSize()) {
                TopBarFrame { SlimTopBar(title = stringResource(R.string.wb_title_fallback), onBack = nav::back) }
                CenteredState(
                    stringResource(R.string.cd_doc_missing_title), stringResource(R.string.cd_doc_missing_body),
                    Modifier.fillMaxSize(), action = stringResource(R.string.cd_back), onAction = nav::back,
                )
            }
            WhiteboardController.LoadState.Failed -> Column(Modifier.fillMaxSize()) {
                TopBarFrame { SlimTopBar(title = stringResource(R.string.wb_title_fallback), onBack = nav::back) }
                CenteredState(
                    stringResource(R.string.cd_open_failed_title), stringResource(R.string.cd_open_failed_body),
                    Modifier.fillMaxSize(), action = stringResource(R.string.cd_retry), onAction = { scope.launch { controller.load() } },
                )
            }
        }
    }
}

@Composable
private fun TopBarFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Inkwell.colors.surface).statusBarsPadding()) { content() }
}

@Composable
private fun WhiteboardContent(controller: WhiteboardController, nav: AppNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = controller.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(controller.messages, snackbar)

    var aiOpen by rememberSaveable { mutableStateOf(false) }
    var aiContext by remember { mutableStateOf<AiPanelContext?>(null) }
    var exportMode by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BoardDialog?>(null) }
    var minimapCollapsed by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val at = controller.imageTarget
        controller.imageTarget = null
        if (uri != null) scope.launch { controller.ops.insertPickedImage(context, uri, at) }
    }
    LaunchedEffect(controller) {
        controller.imageRequests.collect {
            try {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: ActivityNotFoundException) {
                controller.messages.tryEmit(UiMessage(R.string.cd_image_import_failed))
            }
        }
    }
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri != null && file != null) {
            scope.launch {
                runCatching { FileSharing.copyTo(context, file, uri) }
                    .onSuccess { controller.messages.tryEmit(UiMessage(R.string.cd_file_saved)) }
                    .onFailure { controller.messages.tryEmit(UiMessage(R.string.cd_file_save_failed)) }
            }
        }
    }

    fun openAi() {
        if (controller.busy != null) return
        scope.launch {
            controller.busy = R.string.wb_reading_board
            try {
                aiContext = controller.ops.aiContext()
                aiOpen = true
            } finally {
                controller.busy = null
            }
        }
    }

    BackHandler(enabled = aiOpen && !exportMode) { aiOpen = false }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val wide = maxWidth >= 1000.dp
        val narrow = maxWidth < 600.dp
        val collapsed = minimapCollapsed ?: narrow
        Column(Modifier.fillMaxSize()) {
            TopBarFrame {
                SlimTopBar(
                    title = controller.doc?.title ?: stringResource(R.string.wb_title_fallback),
                    onBack = nav::back,
                    onTitleClick = { dialog = BoardDialog.RENAME },
                ) {
                    Box {
                        KitIconButton(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), { menuOpen = true })
                        BoardMenu(
                            expanded = menuOpen,
                            hasSelection = controller.selection != null,
                            paper = controller.page?.paper,
                            onDismiss = { menuOpen = false },
                            onAddPage = { dialog = BoardDialog.ADD_PAGE },
                            onSend = { dialog = BoardDialog.SEND_TO_NOTEBOOK },
                            onBackground = { controller.ops.setBackground(it) },
                            onExportBoard = { controller.flush(); dialog = BoardDialog.EXPORT_BOARD },
                        )
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    AndroidView(
                        factory = { ctx -> InkCanvasView(ctx).also { controller.attachCanvas(it) } },
                        modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it },
                        update = { it.keepScreenOn = settings.view.keepScreenOn },
                        onRelease = { controller.releaseCanvas() },
                    )

                    if (!exportMode) {
                        BoardToolPill(
                            tools = controller.tools,
                            temporaryTool = controller.temporaryTool,
                            presets = settings.penPresets,
                            canUndo = controller.canUndo,
                            canRedo = controller.canRedo,
                            aiOpen = aiOpen,
                            exportActive = exportMode,
                            onToolState = controller::updateTools,
                            onImage = { controller.requestImage(null) },
                            onUndo = controller::undo,
                            onRedo = controller::redo,
                            onPdfSnippet = { dialog = BoardDialog.PDF_SNIPPET },
                            onAi = { if (aiOpen) aiOpen = false else openAi() },
                            onExportArea = { controller.canvas?.clearSelection(); exportMode = true },
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 8.dp),
                        )

                        controller.temporaryTool?.let { t ->
                            DarkToast(
                                stringResource(R.string.wb_side_button_held, stringResource(toolLabel(t))),
                                Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                            )
                        }

                        val sel = controller.selection
                        if (sel != null && controller.textEdit == null && controller.stickyEdit == null) {
                            SelectionBar(
                                sel,
                                SelectionActions(
                                    onCopy = { controller.ops.copySelection(cut = false) },
                                    onCut = { controller.ops.copySelection(cut = true) },
                                    onDuplicate = { controller.canvas?.duplicateSelection() },
                                    onDelete = { controller.canvas?.deleteSelection() },
                                    onRecolor = { controller.canvas?.recolorSelection(it) },
                                    onFront = { controller.canvas?.bringSelectionToFront() },
                                    onBack = { controller.canvas?.sendSelectionToBack() },
                                    onGroup = { controller.canvas?.groupSelection() },
                                    onUngroup = { controller.canvas?.ungroupSelection() },
                                    onSendToNotebook = { dialog = BoardDialog.SEND_TO_NOTEBOOK },
                                    onAskAi = { openAi() },
                                ),
                            )
                        }

                        ZoomControls(
                            zoom = controller.viewState.zoom,
                            onZoomOut = { controller.zoomBy(1f / 1.25f) },
                            onZoomIn = { controller.zoomBy(1.25f) },
                            onReset = { controller.setZoom(1f) },
                            onFit = controller::fitContent,
                            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(12.dp),
                        )
                        Minimap(
                            image = controller.minimap,
                            contentBox = controller.contentBox,
                            visible = controller.visible,
                            collapsed = collapsed,
                            onCollapsedChange = { minimapCollapsed = it },
                            onCenterOn = controller::centerOn,
                            onSizePx = { s ->
                                val px = s.width.coerceAtLeast(1) to s.height.coerceAtLeast(1)
                                if (px != controller.minimapPx) {
                                    controller.minimapPx = px
                                    controller.refreshMinimap()
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp),
                        )
                    }

                    controller.textEdit?.let { req ->
                        TextBoxEditor(req, controller.page?.paper?.color ?: 0xFFFFFFFF.toInt()) { text, cfg -> controller.commitText(req, text, cfg) }
                    }
                    controller.stickyEdit?.let { req ->
                        StickyEditor(req) { text, color -> controller.commitSticky(req, text, color) }
                    }
                    controller.longPress?.let { req ->
                        LongPressMenu(
                            request = req,
                            canPaste = BoardClipboard.content != null,
                            onPaste = { scope.launch { controller.ops.paste(PointF(req.pageX, req.pageY)) } },
                            onSticky = { controller.addStickyAt(req.pageX, req.pageY) },
                            onText = { controller.startTextAt(req.pageX, req.pageY) },
                            onImage = { controller.requestImage(PointF(req.pageX, req.pageY)) },
                            onSelectAll = { controller.canvas?.selectAll(0) },
                            onDismiss = { controller.longPress = null },
                        )
                    }
                    if (exportMode) {
                        ExportAreaOverlay(
                            busy = controller.busy != null,
                            onExport = { rect, action ->
                                scope.launch {
                                    val file = controller.ops.exportArea(rect, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                                    if (file != null) {
                                        exportMode = false
                                        when (action) {
                                            ExportAction.SHARE -> if (!FileSharing.share(context, file, "image/png", context.getString(R.string.wb_export_chooser))) {
                                                controller.messages.tryEmit(UiMessage(R.string.cd_share_unavailable))
                                            }
                                            ExportAction.SAVE -> {
                                                pendingExport = file
                                                try {
                                                    saveExport.launch(file.name)
                                                } catch (e: ActivityNotFoundException) {
                                                    pendingExport = null
                                                    controller.messages.tryEmit(UiMessage(R.string.cd_file_save_failed))
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onCancel = { exportMode = false },
                        )
                    }

                    controller.busy?.let { res -> BusyPill(stringResource(res), Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp)) }

                    if (aiOpen && !wide) {
                        aiContext?.let { ctx ->
                            AiPanel(
                                context = ctx,
                                onInsert = { controller.ops.insertAiText(it) },
                                onOpenSource = { controller.fitContent() },
                                onClose = { aiOpen = false },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    KitMessageHost(
                        snackbar,
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
                    )
                }
                if (aiOpen && wide) {
                    aiContext?.let { ctx ->
                        AiPanel(
                            context = ctx,
                            onInsert = { controller.ops.insertAiText(it) },
                            onOpenSource = { controller.fitContent() },
                            onClose = { aiOpen = false },
                            modifier = Modifier.width(Dimens.panelWidth).fillMaxHeight().navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        BoardDialog.PDF_SNIPPET -> PdfSnippetDialog(
            ops = controller.ops,
            pdfPageSizes = { graph.pdf.pageSizes(it) },
            renderPage = { id, i, w, h -> graph.pdf.renderPage(id, i, w, h) },
            onDismiss = { dialog = null },
        )
        BoardDialog.SEND_TO_NOTEBOOK -> SendToNotebookDialog(
            ops = controller.ops,
            onDismiss = { dialog = null },
            onSent = { doc, page ->
                dialog = null
                controller.messages.tryEmit(
                    UiMessage(R.string.wb_sent, listOf(doc.title), action = R.string.cd_open, onAction = { nav.openDocument(doc, page.index) }),
                )
            },
        )
        BoardDialog.ADD_PAGE -> AddNotebookPageDialog(
            ops = controller.ops,
            loadPages = { graph.library.getPages(it) },
            onDismiss = { dialog = null },
        )
        BoardDialog.RENAME -> RenameDialog(
            initial = controller.doc?.title.orEmpty(),
            onDismiss = { dialog = null },
            onRename = { title -> runCatching { graph.library.renameDocument(controller.docId, title) }.isSuccess },
        )
        BoardDialog.EXPORT_BOARD -> ExportDialog(controller.docId, null) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun BoardMenu(
    expanded: Boolean,
    hasSelection: Boolean,
    paper: PaperSpec?,
    onDismiss: () -> Unit,
    onAddPage: () -> Unit,
    onSend: () -> Unit,
    onBackground: (PaperSpec) -> Unit,
    onExportBoard: () -> Unit,
) {
    val c = Inkwell.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = c.surface) {
        MenuRow(Icons.Outlined.PostAdd, R.string.wb_add_page_objects) { onDismiss(); onAddPage() }
        MenuRow(Icons.AutoMirrored.Outlined.NoteAdd, R.string.wb_send_to_notebook, enabled = hasSelection) { onDismiss(); onSend() }
        HorizontalDivider(color = c.outline, thickness = 1.dp)
        Text(
            stringResource(R.string.wb_background),
            style = MaterialTheme.typography.labelMedium,
            color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val base = paper ?: WhiteboardController.DEFAULT_PAPER
        listOf(
            PaperStyle.BLANK to R.string.wb_bg_blank,
            PaperStyle.DOT_GRID to R.string.wb_bg_dots,
            PaperStyle.GRID to R.string.wb_bg_grid,
        ).forEach { (style, label) ->
            MenuRow(if (base.style == style) Icons.Outlined.Check else null, label) {
                onDismiss()
                if (base.style != style) onBackground(base.copy(style = style, spacingPt = 0f))
            }
        }
        HorizontalDivider(color = c.outline, thickness = 1.dp)
        MenuRow(Icons.Outlined.FileDownload, R.string.wb_export_board) { onDismiss(); onExportBoard() }
    }
}

@Composable
private fun BusyPill(text: String, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    PillSurface(modifier, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = c.ink)
        }
    }
}
