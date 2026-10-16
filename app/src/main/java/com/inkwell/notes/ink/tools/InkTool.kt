package com.inkwell.notes.ink.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InputPrefs
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.InkCanvasListener
import com.inkwell.notes.ink.engine.InkCommand
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.model.PageHolder
import com.inkwell.notes.ink.render.CanvasTheme
import com.inkwell.notes.ink.render.WetInkPresenter

/**
 * Input samples of one pointer event, in page coordinates of the gesture's page plus view
 * pixels. A move carries every historical sample of the MotionEvent batch. Reused between
 * events: tools must copy what they keep.
 */
class ToolEvent {
    var pageIndex = -1
    var count = 0
        private set
    var px = FloatArray(32); private set
    var py = FloatArray(32); private set
    var vx = FloatArray(32); private set
    var vy = FloatArray(32); private set
    var pressure = FloatArray(32); private set
    var tilt = FloatArray(32); private set
    var time = LongArray(32); private set
    var isStylus = false
    var isFinger = false
    /** Set on moves that will be followed by [InkTool.onPredicted] (refresh once, there). */
    var willPredict = false

    val x: Float get() = px[count - 1]
    val y: Float get() = py[count - 1]
    val viewX: Float get() = vx[count - 1]
    val viewY: Float get() = vy[count - 1]
    val eventTime: Long get() = time[count - 1]

    fun reset(pageIndex: Int) { this.pageIndex = pageIndex; count = 0 }

    fun add(pageX: Float, pageY: Float, viewX: Float, viewY: Float, p: Float, t: Float, timeMs: Long) {
        if (count == px.size) grow()
        px[count] = pageX; py[count] = pageY; vx[count] = viewX; vy[count] = viewY
        pressure[count] = p; tilt[count] = t; time[count] = timeMs
        count++
    }

    private fun grow() {
        val n = px.size * 2
        px = px.copyOf(n); py = py.copyOf(n); vx = vx.copyOf(n); vy = vy.copyOf(n)
        pressure = pressure.copyOf(n); tilt = tilt.copyOf(n); time = time.copyOf(n)
    }
}

/**
 * A canvas tool. Gestures arrive as down / move* / (up | cancel) in page coordinates of the page
 * under the initial contact. Tools draw transient feedback in [drawOverlay] (view pixels).
 * Register replacements or new tools through ToolRegistry.
 */
interface InkTool {
    val type: ToolType

    /** Inking tools hold back long-press detection and use motion prediction. */
    val isInking: Boolean get() = false

    fun onDown(host: ToolHost, e: ToolEvent)
    fun onMove(host: ToolHost, e: ToolEvent)
    fun onUp(host: ToolHost, e: ToolEvent)

    /** The gesture was cancelled (palm, system cancel): leave no marks. */
    fun onCancel(host: ToolHost)

    /** Predicted future samples (view pixels + page coords), never to be committed. */
    fun onPredicted(host: ToolHost, e: ToolEvent) {}

    /** Stylus hovering over the canvas (e = null when it left). */
    fun onHover(host: ToolHost, e: ToolEvent?) {}

    /** The viewport moved while a gesture is active. */
    fun onViewportChanged(host: ToolHost) {}

    /** Commits whatever is in progress (called by flush()). */
    fun flush(host: ToolHost) {}

    /** True while a gesture is in progress. */
    val isActive: Boolean get() = false

    fun drawOverlay(host: ToolHost, canvas: Canvas) {}
}

/** Draws extra overlay content (e.g. transform handles); registered on the engine. */
fun interface OverlayDrawer {
    fun draw(host: ToolHost, canvas: Canvas)
}

/** A gesture-long edit (erasing, transforming) applied live and recorded as one undo step. */
interface EditSession {
    /** Current content of a page (including this session's live changes), or null if not editable. */
    fun content(pageIndex: Int): PageContent?

    /** Replaces a page's content live; [changed] (page points) limits re-rendering. */
    fun update(pageIndex: Int, content: PageContent, changed: Bounds?)

    /** Records one undo command and notifies the document source. Returns true if anything changed. */
    fun commit(): Boolean

    /** Restores every page touched by this session. */
    fun cancel()
}

/** Everything a tool may use. Implemented by the ink engine; all calls on the main thread. */
interface ToolHost {
    val context: Context
    val density: Float
    /** Pixels per page point at the current zoom. */
    val scale: Float
    val toolState: ToolState
    val inputPrefs: InputPrefs
    val listener: InkCanvasListener?
    val docId: String
    val pageCount: Int
    val theme: CanvasTheme

    fun page(index: Int): PageHolder?
    fun pageIndexOf(pageId: String): Int
    /** True when edits on the page can be saved (shows a message otherwise when [notify]). */
    fun isEditable(pageIndex: Int, notify: Boolean = true): Boolean

    fun pageToView(pageIndex: Int, px: Float, py: Float, out: FloatArray)
    fun viewToPage(pageIndex: Int, vx: Float, vy: Float, out: FloatArray)
    /** Page -> view transform. */
    fun pageMatrix(pageIndex: Int, out: Matrix)
    /** View rectangle of a page; false for the unbounded canvas (no clip). */
    fun pageClip(pageIndex: Int, out: RectF): Boolean
    /** Page-point bounds -> view rectangle. */
    fun pageBoundsToView(pageIndex: Int, b: Bounds, out: RectF)

    fun invalidateOverlay()

    // ---- Content
    fun newId(): String
    /** z for a new topmost item on the page. */
    fun nextZ(pageIndex: Int): Int
    /** Audio anchor for an item created now (null when not recording). */
    fun audioAnchor(): AudioAnchor?
    /** Adds new topmost items as one undoable step (drawn incrementally). */
    fun commitNewItems(pageIndex: Int, items: List<Any>, label: String, select: Boolean = false)
    /** One-shot undoable edit of a page's content. Returns false when nothing changed. */
    fun applyEdit(pageIndex: Int, label: String, transform: (PageContent) -> PageContent): Boolean
    fun beginEdit(label: String): EditSession
    fun pushCommand(command: InkCommand)

    // ---- Selection
    val selectionPageIndex: Int
    val selectedIds: Set<String>
    fun setSelection(pageIndex: Int, ids: Set<String>)
    fun clearSelection()

    // ---- Viewport
    fun panBy(dxPx: Float, dyPx: Float)
    fun fling(vxPx: Float, vyPx: Float)
    fun stopViewportMotion()

    // ---- Live ink
    val wetInk: WetInkPresenter

    /** Extension overlays drawn above the active tool's overlay. */
    fun addOverlay(drawer: OverlayDrawer)
    fun removeOverlay(drawer: OverlayDrawer)
}
