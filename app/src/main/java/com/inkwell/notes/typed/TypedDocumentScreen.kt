package com.inkwell.notes.typed

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.inkwell.notes.R
import com.inkwell.notes.ai.ui.AiPanel
import com.inkwell.notes.ai.ui.AiPanelContext
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.export.ui.ExportDialog
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.typed.model.InlineStyle
import com.inkwell.notes.typed.model.OutlineItem
import com.inkwell.notes.typed.ui.FormatBar
import com.inkwell.notes.typed.ui.LinkDialog
import com.inkwell.notes.typed.ui.OutlinePanel
import com.inkwell.notes.typed.ui.ReorderState
import com.inkwell.notes.typed.ui.BlockRow
import com.inkwell.notes.typed.ui.MenuItem
import com.inkwell.notes.typed.ui.dragOutline
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.kit.CenteredState
import com.inkwell.notes.whiteboard.kit.CollectMessages
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.KitMessageHost
import com.inkwell.notes.whiteboard.kit.RenameDialog
import com.inkwell.notes.whiteboard.kit.SlimTopBar
import com.inkwell.notes.whiteboard.kit.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

private enum class TypedDialog { RENAME, LINK, EXPORT }

/**
 * Structured block editor for TYPED documents: paragraphs, headings, lists with
 * indentation, checklists, quotes, code, tables, images and dividers, with inline
 * formatting, Markdown shortcuts, drag reordering, an outline and the assistant panel.
 */
@Composable
fun TypedDocumentScreen(docId: String, nav: AppNavigator) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val controller = remember(docId) { TypedDocController(graph, docId) }
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
    val fallbackTitle = stringResource(R.string.td_title_fallback)
    Box(Modifier.fillMaxSize().background(c.surface)) {
        when (controller.loadState) {
            TypedDocController.LoadState.Ready -> TypedEditor(controller, nav)
            TypedDocController.LoadState.Loading -> TopFrame { SlimTopBar("", nav::back) }
            TypedDocController.LoadState.Missing -> Column(Modifier.fillMaxSize()) {
                TopFrame { SlimTopBar(fallbackTitle, nav::back) }
                CenteredState(stringResource(R.string.cd_doc_missing_title), stringResource(R.string.cd_doc_missing_body), Modifier.fillMaxSize(), stringResource(R.string.cd_back), nav::back)
            }
            TypedDocController.LoadState.Unreadable -> Column(Modifier.fillMaxSize()) {
                TopFrame { SlimTopBar(controller.doc?.title ?: fallbackTitle, nav::back) }
                CenteredState(
                    stringResource(R.string.td_load_failed_title), stringResource(R.string.td_load_failed_body), Modifier.fillMaxSize(),
                    stringResource(R.string.cd_retry), { scope.launch { controller.load() } },
                )
            }
            TypedDocController.LoadState.Failed -> Column(Modifier.fillMaxSize()) {
                TopFrame { SlimTopBar(fallbackTitle, nav::back) }
                CenteredState(
                    stringResource(R.string.cd_open_failed_title), stringResource(R.string.cd_open_failed_body), Modifier.fillMaxSize(),
                    stringResource(R.string.cd_retry), { scope.launch { controller.load() } },
                )
            }
        }
    }
}

@Composable
private fun TopFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Inkwell.colors.surface).statusBarsPadding()) { content() }
}

@Composable
private fun TypedEditor(controller: TypedDocController, nav: AppNavigator) {
    val c = Inkwell.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(controller.messages, snackbar)

    var outlineOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var aiOpen by rememberSaveable { mutableStateOf(false) }
    var aiContext by remember { mutableStateOf<AiPanelContext?>(null) }
    var dialog by remember { mutableStateOf<TypedDialog?>(null) }
    var linkTarget by remember { mutableStateOf<Triple<String, IntRange, String?>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var replaceImageId by remember { mutableStateOf<String?>(null) }
    var wordCount by remember { mutableIntStateOf(0) }
    var charCount by remember { mutableIntStateOf(0) }
    // Shortcuts only apply while the caret is in the document (not in the assistant's input).
    var editorFocused by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val reorder = remember(listState) {
        ReorderState(listState, scope, edgePx = with(density) { 64.dp.toPx() }, onMove = controller::move, onEnd = controller::endDrag)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val replace = replaceImageId
        replaceImageId = null
        if (uri != null) controller.insertImage(context, uri, replace)
    }
    fun launchPicker(replaceId: String?) {
        replaceImageId = replaceId
        try {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            replaceImageId = null
            controller.messages.tryEmit(UiMessage(R.string.cd_image_import_failed))
        }
    }
    fun openLinkEditor() {
        val target = controller.linkTarget()
        if (target == null) {
            controller.messages.tryEmit(UiMessage(R.string.td_link_select_text))
            return
        }
        linkTarget = target
        dialog = TypedDialog.LINK
    }
    fun openLink(url: String) {
        try {
            uriHandler.openUri(TypedDocController.normalizeUrl(url))
        } catch (t: Throwable) {
            controller.messages.tryEmit(UiMessage(R.string.td_link_open_failed))
        }
    }
    fun toggleAi() {
        if (aiOpen) { aiOpen = false; return }
        val (text, _) = controller.aiText()
        aiContext = AiPanelContext(docId = controller.docId, selectionText = text)
        aiOpen = true
    }

    // Word count, recomputed shortly after edits settle.
    LaunchedEffect(controller.revision) {
        delay(250)
        val blocks = controller.blocks
        val (w, ch) = withContext(Dispatchers.Default) { BlockOps.wordCount(blocks) to BlockOps.characterCount(blocks) }
        wordCount = w
        charCount = ch
    }
    // Bring the block that should receive focus into view first.
    LaunchedEffect(controller.focusRequest) {
        val req = controller.focusRequest ?: return@LaunchedEffect
        val index = controller.indexOf(req.blockId)
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == req.blockId }) {
            listState.scrollToItem(index)
        }
    }
    BackHandler(enabled = aiOpen) { aiOpen = false }

    // Reopen where the reader left off, and remember the position when leaving.
    LaunchedEffect(controller) {
        controller.initialScroll?.let { (index, offset) -> runCatching { listState.scrollToItem(index, offset) } }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, listState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }

    val blocks = controller.blocks
    val numbers = remember(blocks) { BlockOps.numbering(blocks) }
    val outline = remember(blocks) { BlockOps.outline(blocks) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !editorFocused) return@onPreviewKeyEvent false
                val mod = e.isCtrlPressed || e.isMetaPressed
                when {
                    mod && e.key == Key.B -> { controller.toggleStyle(InlineStyle.BOLD); true }
                    mod && e.key == Key.I -> { controller.toggleStyle(InlineStyle.ITALIC); true }
                    mod && e.key == Key.U -> { controller.toggleStyle(InlineStyle.UNDERLINE); true }
                    mod && e.key == Key.K -> { openLinkEditor(); true }
                    mod && e.key == Key.Z -> { if (e.isShiftPressed) controller.redo() else controller.undo(); true }
                    mod && e.key == Key.Y -> { controller.redo(); true }
                    e.key == Key.Tab -> {
                        val cell = controller.focusedCell
                        if (cell != null) {
                            if (!e.isShiftPressed) controller.nextCell(cell.blockId, cell.row, cell.column, downwards = false)
                            true
                        } else if (controller.focusedBlock != null) {
                            controller.indent(if (e.isShiftPressed) -1 else 1)
                            true
                        } else false
                    }
                    else -> false
                }
            },
    ) {
        val wide = maxWidth >= 1000.dp
        val showOutline = outlineOpen ?: (maxWidth >= 1200.dp)
        val sideOutline = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().imePadding()) {
            TopFrame {
                SlimTopBar(
                    title = controller.doc?.title ?: stringResource(R.string.td_title_fallback),
                    onBack = nav::back,
                    onTitleClick = { dialog = TypedDialog.RENAME },
                ) {
                    KitIconButton(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.td_undo), controller::undo, enabled = controller.canUndo)
                    KitIconButton(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.td_redo), controller::redo, enabled = controller.canRedo)
                    KitIconButton(Icons.AutoMirrored.Outlined.Toc, stringResource(R.string.td_toggle_outline), { outlineOpen = !showOutline }, selected = showOutline)
                    KitIconButton(Icons.Outlined.AutoAwesome, stringResource(R.string.cd_assistant), { toggleAi() }, selected = aiOpen)
                    Box {
                        KitIconButton(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.surface) {
                            MenuItem(Icons.Outlined.FileDownload, stringResource(R.string.cd_export)) {
                                menuOpen = false
                                scope.launch {
                                    // Make sure the file on disk has every edit before exporting it.
                                    runCatching { controller.flushAndWait() }
                                    dialog = TypedDialog.EXPORT
                                }
                            }
                        }
                    }
                }
            }
            FormatBar(
                controller = controller,
                onLink = { openLinkEditor() },
                onOpenLink = { openLink(it) },
                onPickImage = { launchPicker(null) },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (showOutline && sideOutline) {
                    OutlinePanel(outline, onJump = { item -> scope.launch { scrollToBlock(listState, controller, item) } }, modifier = Modifier.width(248.dp).fillMaxHeight())
                    VerticalDivider(thickness = 1.dp, color = c.outline)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().onFocusChanged { editorFocused = it.hasFocus },
                        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        itemsIndexed(blocks, key = { _, b -> b.id }) { i, block ->
                            val dragging = reorder.draggingKey == block.id
                            BlockRow(
                                block = block,
                                index = i,
                                count = blocks.size,
                                number = numbers.getOrElse(i) { 0 },
                                focused = controller.focusedBlockId == block.id,
                                onlyBlock = blocks.size == 1,
                                controller = controller,
                                reorder = reorder,
                                onReplaceImage = { launchPicker(it) },
                                modifier = Modifier
                                    .widthIn(max = 780.dp)
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 24.dp)
                                    .then(
                                        if (dragging) Modifier
                                            .zIndex(1f)
                                            .graphicsLayer { translationY = reorder.offsetY }
                                            .shadow(2.dp, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                            .background(c.surface)
                                            .dragOutline(true, c.outline)
                                        else Modifier.animateItem(),
                                    ),
                            )
                        }
                        item(key = END_KEY) {
                            // Tapping below the last block continues writing at the end.
                            Box(
                                Modifier
                                    .widthIn(max = 780.dp)
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        val last = controller.blocks.lastOrNull()
                                        if (last != null && BlockOps.isTextual(last.type) && last.text.isEmpty()) {
                                            controller.requestFocus(last.id, TextRange(0))
                                        } else {
                                            controller.focusLastOrAppend()
                                        }
                                    },
                            )
                        }
                    }
                    if (showOutline && !sideOutline) {
                        Box(Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.18f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { outlineOpen = false })
                        OutlinePanel(
                            outline,
                            onJump = { item -> outlineOpen = false; scope.launch { scrollToBlock(listState, controller, item) } },
                            modifier = Modifier.width(280.dp).fillMaxHeight().align(Alignment.CenterStart),
                        )
                    }
                    if (aiOpen && !wide) {
                        aiContext?.let { ctx ->
                            AiPanel(ctx, onInsert = controller::insertAiText, onOpenSource = {}, onClose = { aiOpen = false }, modifier = Modifier.fillMaxSize())
                        }
                    }
                    KitMessageHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
                }
                if (aiOpen && wide) {
                    aiContext?.let { ctx ->
                        AiPanel(ctx, onInsert = controller::insertAiText, onOpenSource = {}, onClose = { aiOpen = false }, modifier = Modifier.width(Dimens.panelWidth).fillMaxHeight())
                    }
                }
            }
            Footer(wordCount, charCount, controller.saveState)
        }
    }

    when (dialog) {
        TypedDialog.RENAME -> RenameDialog(
            initial = controller.doc?.title.orEmpty(),
            onDismiss = { dialog = null },
            onRename = { title -> runCatching { controller.graph.library.renameDocument(controller.docId, title) }.isSuccess },
        )
        TypedDialog.LINK -> linkTarget?.let { (blockId, range, url) ->
            LinkDialog(
                initialUrl = url,
                onApply = { newUrl -> dialog = null; controller.setLink(blockId, range, newUrl) },
                onRemove = if (url != null) ({ dialog = null; controller.setLink(blockId, range, null) }) else null,
                onDismiss = { dialog = null },
            )
        }
        TypedDialog.EXPORT -> ExportDialog(controller.docId, null) { dialog = null }
        null -> Unit
    }
}

/** Key of the trailing tap-to-continue area; not a String so drag reordering ignores it. */
private const val END_KEY = -1

private suspend fun scrollToBlock(listState: androidx.compose.foundation.lazy.LazyListState, controller: TypedDocController, item: OutlineItem) {
    val index = controller.blocks.indexOfFirst { it.id == item.blockId }.takeIf { it >= 0 } ?: return
    listState.animateScrollToItem(index)
}

@Composable
private fun Footer(words: Int, chars: Int, save: SaveState) {
    val c = Inkwell.colors
    val format = remember { NumberFormat.getIntegerInstance() }
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
        HorizontalDivider(thickness = 1.dp, color = c.outline)
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.td_word_count, words, format.format(words)) + "  ·  " +
                    pluralStringResource(R.plurals.td_char_count, chars, format.format(chars)),
                style = MaterialTheme.typography.labelSmall,
                color = c.inkMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(
                    when (save) {
                        SaveState.SAVED -> R.string.td_saved
                        SaveState.SAVING -> R.string.td_saving
                        SaveState.ERROR -> R.string.td_not_saved
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (save == SaveState.ERROR) c.danger else c.inkMuted,
            )
        }
    }
}
