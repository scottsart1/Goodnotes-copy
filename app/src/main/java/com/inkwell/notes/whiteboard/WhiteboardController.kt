package com.inkwell.notes.whiteboard

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.CanvasViewState
import com.inkwell.notes.ink.InkCanvasListener
import com.inkwell.notes.ink.InkCanvasView
import com.inkwell.notes.ink.InkSelection
import com.inkwell.notes.ink.ItemRef
import com.inkwell.notes.ink.ItemType
import com.inkwell.notes.ink.PageLayoutMode
import com.inkwell.notes.whiteboard.kit.UiMessage
import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.MinimapMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Request to edit (or create) a text box over the canvas. Rect in canvas-view pixels. */
data class TextEditRequest(
    val pageId: String,
    val itemId: String?,
    val pageX: Float,
    val pageY: Float,
    val viewRect: RectF,
    val initialText: String,
    val config: TextConfig,
    /** View pixels per board point at the current zoom. */
    val pxPerPt: Float,
)

data class StickyEditRequest(val pageId: String, val itemId: String, val viewRect: RectF, val initialText: String, val color: Int)

data class LongPressRequest(val pageX: Float, val pageY: Float, val viewX: Float, val viewY: Float)

/** A rendered overview of the board for the minimap: [image] covers [bounds] (board points). */
class MinimapImage(val image: ImageBitmap, val bounds: Box)

/**
 * State and canvas wiring of one open whiteboard. Plain class with Compose state, owned by
 * [WhiteboardScreen]; long operations live in [BoardOperations].
 */
class WhiteboardController(val graph: AppGraph, val docId: String) : InkCanvasListener {

    sealed interface LoadState {
        data object Loading : LoadState
        data object Missing : LoadState
        data object Failed : LoadState
        data object Ready : LoadState
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val boardSaver = BoardSaver.forDocument(graph, docId)
    val source = BoardSource(
        graph, docId, boardSaver,
        onLoaded = { _, content -> scope.launch { onContent(content, delayMs = 0) } },
        onChanged = { _, content -> onContent(content, delayMs = MINIMAP_DELAY_MS) },
    )
    val ops = BoardOperations(this)

    var loadState by mutableStateOf<LoadState>(LoadState.Loading); private set
    var doc by mutableStateOf<DocumentMeta?>(null); private set
    var page by mutableStateOf<PageMeta?>(null); private set
    var tools by mutableStateOf(ToolState()); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var selection by mutableStateOf<InkSelection?>(null); private set
    var viewState by mutableStateOf(CanvasViewState()); private set
    /** Visible board region (points); null before the first layout. */
    var visible by mutableStateOf<Box?>(null); private set
    var temporaryTool by mutableStateOf<ToolType?>(null); private set
    var textEdit by mutableStateOf<TextEditRequest?>(null)
    var stickyEdit by mutableStateOf<StickyEditRequest?>(null)
    var longPress by mutableStateOf<LongPressRequest?>(null)
    /** Board point where a picked image should go; non-null while the picker is wanted. */
    var imageTarget by mutableStateOf<PointF?>(null)
    var imagePickRequest by mutableStateOf(0); private set
    var minimap by mutableStateOf<MinimapImage?>(null); private set
    /** Bounds of all content (board points), refreshed with the minimap. */
    var contentBox by mutableStateOf<Box?>(null); private set
    /** String resource of a running long operation, or null. */
    var busy by mutableStateOf<Int?>(null)

    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)

    var canvas: InkCanvasView? = null; private set
    /** Newest content of the board page (main thread). */
    var latestContent: PageContent = PageContent.EMPTY; private set
    /** Minimap bitmap size in pixels (set by the minimap from its layout). */
    var minimapPx: Pair<Int, Int> = 400 to 280

    private var bound = false
    private var released = false
    private var minimapJob: Job? = null
    private var viewSaveJob: Job? = null
    private var toolSaveJob: Job? = null
    private var lastSavedView: CanvasViewState? = null
    private val saveErrorListener: (Throwable, Boolean) -> Unit = { _, retry ->
        scope.launch { messages.tryEmit(UiMessage(if (retry) R.string.cd_save_retrying else R.string.cd_save_failed, long = !retry)) }
    }

    init {
        boardSaver.addErrorListener(saveErrorListener)
    }

    // ------------------------------------------------------------------ Loading & binding

    suspend fun load() {
        loadState = LoadState.Loading
        try {
            val d = graph.library.getDocument(docId)
            if (d == null || d.trashedAt != null) { loadState = LoadState.Missing; return }
            doc = d
            page = ensureBoardPage()
            tools = graph.settings.current.lastTools
            lastSavedView = CanvasViewState(0, d.lastZoom, d.lastScrollX, d.lastScrollY)
            loadState = LoadState.Ready
            maybeBind()
            scope.launch { runCatching { graph.library.touch(docId) } }
            scope.launch {
                graph.library.observeDocument(docId).collect { if (it != null) doc = it }
            }
            scope.launch {
                graph.settings.settings.map { it.input }.distinctUntilChanged().collect { canvas?.setInputPrefs(it) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Loading whiteboard failed", t)
            loadState = LoadState.Failed
        }
    }

    /** The board's single infinite page, created (or made infinite) when needed. */
    private suspend fun ensureBoardPage(): PageMeta {
        val existing = graph.library.getPages(docId).minByOrNull { it.index }
        if (existing != null) {
            if (existing.isInfinite) return existing
            runCatching { graph.library.setPageSize(existing.id, 0f, 0f) }.onFailure { Log.w(TAG, "Could not make the page infinite", it) }
            return existing.copy(widthPt = 0f, heightPt = 0f)
        }
        val created = graph.library.addPage(docId)
        graph.library.setPageSize(created.id, 0f, 0f)
        runCatching { graph.library.setPaper(created.id, DEFAULT_PAPER) }
        return graph.library.getPage(created.id)?.takeIf { it.isInfinite }
            ?: created.copy(widthPt = 0f, heightPt = 0f, paper = DEFAULT_PAPER)
    }

    fun attachCanvas(view: InkCanvasView) {
        if (canvas === view) return
        canvas = view
        released = false
        view.listener = this
        view.setToolState(tools)
        view.setInputPrefs(graph.settings.current.input)
        view.setAudioClock(null)
        maybeBind()
    }

    private fun maybeBind() {
        val c = canvas ?: return
        val p = page ?: return
        if (bound) return
        bound = true
        val restore = lastSavedView?.takeIf {
            graph.settings.current.view.reopenAtLastPosition && (it.zoom != 1f || it.scrollX != 0f || it.scrollY != 0f)
        }
        c.bind(source, listOf(p), PageLayoutMode.INFINITE, restore)
        c.setToolState(tools)
    }

    /** Commits in-progress strokes and writes everything pending (lifecycle pause). */
    fun flush() {
        runCatching { canvas?.flush() }
        boardSaver.saver.flushAsync()
        saveViewStateNow()
    }

    /** Leaves the screen: flush, release the canvas, stop observing. */
    fun dispose() {
        flush()
        persistToolsNow()
        boardSaver.removeErrorListener(saveErrorListener)
        releaseCanvas()
        graph.appScope.launch {
            boardSaver.saver.flush()
            runCatching { graph.thumbnails.refresh(docId) }
        }
        scope.cancel()
    }

    fun releaseCanvas() {
        if (released) return
        released = true
        canvas?.let { c ->
            runCatching { c.flush() }
            c.listener = null
            runCatching { c.release() }
        }
        canvas = null
    }

    // ------------------------------------------------------------------ Content tracking

    private fun onContent(content: PageContent, delayMs: Long) {
        latestContent = content
        minimapJob?.cancel()
        minimapJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val (w, h) = minimapPx
            val rendered = withContext(Dispatchers.Default) { ops.renderOverview(content, w, h) }
            minimap = rendered
            contentBox = rendered?.bounds
        }
    }

    /** Re-renders the overview now (e.g. after the minimap changed size). */
    fun refreshMinimap() = onContent(latestContent, 0)

    /** Applies a changed page (e.g. new background) to the screen and the canvas. */
    fun updatePage(p: PageMeta) {
        page = p
        canvas?.updatePages(listOf(p))
    }

    // ------------------------------------------------------------------ Tools

    fun selectTool(tool: ToolType) = updateTools(tools.copy(tool = tool))

    fun updateTools(state: ToolState) {
        if (state == tools) return
        val toolChanged = state.tool != tools.tool
        tools = state
        canvas?.setToolState(state)
        if (toolChanged && selection != null) canvas?.clearSelection()
        toolSaveJob?.cancel()
        toolSaveJob = scope.launch { delay(1_000); persistToolsNow() }
    }

    private fun persistToolsNow() {
        toolSaveJob?.cancel()
        val t = tools
        graph.appScope.launch {
            runCatching {
                graph.settings.update { s ->
                    // Sticky/connector are whiteboard-only; notebooks keep their last real tool.
                    val tool = if (t.tool in BOARD_ONLY_TOOLS) s.lastTools.tool else t.tool
                    if (s.lastTools == t.copy(tool = tool)) s else s.copy(lastTools = t.copy(tool = tool))
                }
            }.onFailure { Log.w(TAG, "Saving tool settings failed", it) }
        }
    }

    // ------------------------------------------------------------------ Viewport

    fun zoomBy(factor: Float) { canvas?.zoomBy(factor) }
    fun setZoom(zoom: Float) { canvas?.setZoom(zoom) }

    fun centerOn(x: Float, y: Float) { canvas?.centerOn(0, x, y) }

    fun fitContent() {
        val c = canvas ?: return
        val content = c.contentBounds(0)?.toBox() ?: contentBox
        val vis = c.visibleRect(0)?.toBox() ?: visible
        if (content == null) {
            messages.tryEmit(UiMessage(R.string.wb_board_empty))
            return
        }
        if (vis != null) c.setZoom(MinimapMath.fitZoom(c.viewState().zoom, vis, content))
        c.centerOn(0, content.centerX, content.centerY)
    }

    private fun scheduleViewSave() {
        viewSaveJob?.cancel()
        viewSaveJob = scope.launch { delay(800); saveViewStateNow() }
    }

    private fun saveViewStateNow() {
        viewSaveJob?.cancel()
        val s = runCatching { canvas?.viewState() }.getOrNull() ?: viewState
        if (s == lastSavedView || page == null) return
        lastSavedView = s
        graph.appScope.launch {
            runCatching { graph.library.updateViewState(docId, 0, s.zoom, s.scrollX, s.scrollY) }
                .onFailure { Log.w(TAG, "Saving the view position failed", it) }
        }
    }

    // ------------------------------------------------------------------ History

    fun undo() { canvas?.undo() }
    fun redo() { canvas?.redo() }

    // ------------------------------------------------------------------ Text & stickies

    fun commitText(req: TextEditRequest, text: String, config: TextConfig) {
        textEdit = null
        val c = canvas ?: return
        if (req.itemId == null) {
            if (text.isNotBlank()) c.insertText(0, text, PointF(req.pageX, req.pageY), config)
        } else {
            c.updateText(ItemRef(req.pageId, req.itemId, ItemType.TEXT), text, config)
        }
        if (config != tools.text) updateTools(tools.copy(text = config))
    }

    fun commitSticky(req: StickyEditRequest, text: String, color: Int) {
        stickyEdit = null
        canvas?.updateSticky(ItemRef(req.pageId, req.itemId, ItemType.STICKY), text, color)
        if (color != tools.stickyColor) updateTools(tools.copy(stickyColor = color))
    }

    fun addStickyAt(x: Float?, y: Float?) {
        val c = canvas ?: return
        val ref = c.addSticky(0, if (x != null && y != null) PointF(x, y) else null, tools.stickyColor) ?: return
        val sticky = c.pageContent(0)?.stickies?.firstOrNull { it.id == ref.itemId } ?: return
        val vis = c.visibleRect(0)?.toBox() ?: return
        val k = c.width / vis.width
        val rect = RectF((sticky.x - vis.left) * k, (sticky.y - vis.top) * k, (sticky.x + sticky.width - vis.left) * k, (sticky.y + sticky.height - vis.top) * k)
        stickyEdit = StickyEditRequest(ref.pageId, ref.itemId, rect, sticky.text, sticky.color)
    }

    fun startTextAt(x: Float, y: Float) {
        val p = page ?: return
        val vis = canvas?.visibleRect(0)?.toBox() ?: return
        val k = (canvas?.width ?: 0) / vis.width
        val vx = (x - vis.left) * k
        val vy = (y - vis.top) * k
        textEdit = TextEditRequest(p.id, null, x, y, RectF(vx, vy, vx, vy), "", tools.text, k)
    }

    fun requestImage(at: PointF?) {
        imageTarget = at
        imagePickRequest++
    }

    // ------------------------------------------------------------------ InkCanvasListener

    override fun onSelectionChanged(selection: InkSelection?) {
        this.selection = selection
    }

    override fun onUndoRedoChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo
        this.canRedo = canRedo
    }

    override fun onViewportChanged(state: CanvasViewState) {
        viewState = state
        visible = canvas?.visibleRect(0)?.toBox()
        scheduleViewSave()
    }

    override fun onTemporaryToolChanged(tool: ToolType?) {
        temporaryTool = tool
    }

    override fun onToolChangeRequested(tool: ToolType) = selectTool(tool)

    override fun onLongPress(pageIndex: Int, pageX: Float, pageY: Float, viewX: Float, viewY: Float) {
        longPress = LongPressRequest(pageX, pageY, viewX, viewY)
    }

    override fun onTextEditRequested(pageId: String, itemId: String?, pageX: Float, pageY: Float, viewRect: RectF) {
        val existing = itemId?.let { id -> (canvas?.pageContent(0) ?: latestContent).texts.firstOrNull { it.id == id } }
        val config = existing?.let { TextConfig(it.fontSizePt, it.color, it.bold, it.italic) } ?: tools.text
        val vis = canvas?.visibleRect(0)?.toBox()
        val k = if (vis != null && vis.width > 0f) (canvas?.width ?: 0) / vis.width else viewState.zoom
        val rect = if (viewRect.width() > 1f) RectF(viewRect) else {
            val vx = vis?.let { (pageX - it.left) * k } ?: viewRect.left
            val vy = vis?.let { (pageY - it.top) * k } ?: viewRect.top
            RectF(vx, vy, vx, vy)
        }
        textEdit = TextEditRequest(pageId, itemId, existing?.x ?: pageX, existing?.y ?: pageY, rect, existing?.text.orEmpty(), config, k)
    }

    override fun onStickyEditRequested(pageId: String, itemId: String, viewRect: RectF) {
        val sticky = (canvas?.pageContent(0) ?: latestContent).stickies.firstOrNull { it.id == itemId } ?: return
        stickyEdit = StickyEditRequest(pageId, itemId, RectF(viewRect), sticky.text, sticky.color)
    }

    override fun onImagePlacementRequested(pageIndex: Int, pageX: Float, pageY: Float) {
        requestImage(PointF(pageX, pageY))
    }

    companion object {
        private const val TAG = "Whiteboard"
        const val MINIMAP_DELAY_MS = 500L
        val BOARD_ONLY_TOOLS = setOf(ToolType.STICKY, ToolType.CONNECTOR)
        val DEFAULT_PAPER = PaperSpec(style = PaperStyle.DOT_GRID, color = 0xFFFFFFFF.toInt(), lineColor = 0xFFD9D5CC.toInt())
    }
}

fun RectF.toBox(): Box = Box(left, top, right, bottom)
