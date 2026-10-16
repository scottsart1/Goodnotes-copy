package com.inkwell.notes.ink

import android.graphics.Bitmap
import android.graphics.RectF
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.ToolType

/** How pages are arranged in the canvas viewport. */
enum class PageLayoutMode { VERTICAL, HORIZONTAL, INFINITE }

enum class ItemType { STROKE, SHAPE, TEXT, IMAGE, STICKY, CONNECTOR }

data class ItemRef(val pageId: String, val itemId: String, val type: ItemType)

/** Supplies content to [InkCanvasView]. Implemented by the notebook editor and the whiteboard. */
interface InkDocumentSource {
    val docId: String

    /** Loads a page's vector content (called off the main thread). */
    suspend fun loadContent(page: PageMeta): PageContent

    /**
     * Called on the main thread after every committed edit (stroke end, erase, transform,
     * insert, undo/redo) with the page's full new content. Persist it (debounced).
     */
    fun onContentChanged(page: PageMeta, content: PageContent)

    /** Background of a PDF page at the requested pixel size, or null for paper pages. Off the main thread. */
    suspend fun renderBackground(page: PageMeta, widthPx: Int, heightPx: Int): Bitmap?

    /** Bitmap for an [com.inkwell.notes.core.model.ImageItem.assetId]. Off the main thread. */
    suspend fun loadImage(assetId: String): Bitmap?
}

/** A lasso/tap selection on one page. */
data class InkSelection(
    val pageId: String,
    val pageIndex: Int,
    val items: List<ItemRef>,
    /** Copies of the selected items. */
    val content: PageContent,
    /** Selection bounds in page points. */
    val boundsPage: RectF,
    /** Selection bounds in view pixels, for anchoring the context menu. */
    val boundsView: RectF,
) {
    val hasHandwriting: Boolean get() = content.strokes.any { it.pen != com.inkwell.notes.core.model.PenType.HIGHLIGHTER }
}

data class CanvasViewState(
    val pageIndex: Int = 0,
    val zoom: Float = 1f,
    /** Scroll offset of the viewport in document coordinates (points). */
    val scrollX: Float = 0f,
    val scrollY: Float = 0f,
)

/** Callbacks from the canvas to its host. All run on the main thread. Default no-ops. */
interface InkCanvasListener {
    fun onSelectionChanged(selection: InkSelection?) {}
    fun onUndoRedoChanged(canUndo: Boolean, canRedo: Boolean) {}
    fun onViewportChanged(state: CanvasViewState) {}
    fun onCurrentPageChanged(pageIndex: Int) {}

    /**
     * The S Pen side button (or a stylus eraser end) temporarily switched the tool:
     * non-null while held (e.g. ERASER), null when released and the previous tool is back.
     */
    fun onTemporaryToolChanged(tool: ToolType?) {}

    /** A persistent tool change requested from the canvas (side button in toggle mode, gestures). */
    fun onToolChangeRequested(tool: ToolType) {}

    fun onLongPress(pageIndex: Int, pageX: Float, pageY: Float, viewX: Float, viewY: Float) {}

    /** TEXT tool tap (itemId = null → new box) or tap on an existing text box. */
    fun onTextEditRequested(pageId: String, itemId: String?, pageX: Float, pageY: Float, viewRect: RectF) {}

    fun onStickyEditRequested(pageId: String, itemId: String, viewRect: RectF) {}

    /** IMAGE tool tap: host picks an image and calls [InkCanvasView.insertImage]. */
    fun onImagePlacementRequested(pageIndex: Int, pageX: Float, pageY: Float) {}

    /** Tap on an item (finger or stylus with HAND/LASSO tool) — e.g. jump to its audio anchor. */
    fun onItemTapped(ref: ItemRef) {}

    /** First stylus contact in this session; hosts can show "stylus mode on". */
    fun onStylusDetected() {}

    /** Stylus hovering (x/y in view px) or left (active = false). */
    fun onHover(viewX: Float, viewY: Float, active: Boolean) {}
}
