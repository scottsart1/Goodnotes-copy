package com.inkwell.notes.ink.tools

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Matrix
import android.graphics.RectF
import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InputPrefs
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.ink.InkCanvasListener
import com.inkwell.notes.ink.engine.InkCommand
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.input.TapTracker
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.LoadState
import com.inkwell.notes.ink.model.PageHolder
import com.inkwell.notes.ink.render.CanvasTheme
import com.inkwell.notes.ink.render.FrontBufferLayer
import com.inkwell.notes.ink.render.WetInkHost
import com.inkwell.notes.ink.render.WetInkPresenter

/**
 * A pure-JVM [ToolHost] with one loaded page and an unrotated, unscrolled viewport at [scale]
 * pixels per point. Records the commands tools commit so pen/eraser behaviour can be asserted
 * without an Android device. Android classes are stubbed (return defaults) on the test JVM.
 */
internal class FakeToolHost(
    override var scale: Float = 2f,
    override val density: Float = 2f,
    content: PageContent = PageContent.EMPTY,
) : ToolHost, WetInkHost {
    @Suppress("DEPRECATION")
    override val context: Context = ContextWrapper(null)
    override var toolState: ToolState = ToolState()
    override var inputPrefs: InputPrefs = InputPrefs()
    override var listener: InkCanvasListener? = null
    override val docId: String = "doc"
    override val pageCount: Int get() = 1
    override val theme: CanvasTheme = CanvasTheme(dark = false)
    override val wetInk: WetInkPresenter = WetInkPresenter(this)
    override val taps: TapTracker = TapTracker()
    override val selectionPageIndex: Int get() = -1
    override var selectedIds: Set<String> = emptySet()
    override val frontLayer: FrontBufferLayer? get() = null
    override val lowLatencyEnabled: Boolean get() = false

    val holder = PageHolder(PageMeta(id = "page", documentId = "doc", index = 0, widthPt = 600f, heightPt = 800f)).also {
        it.setContentForTest(content)
    }
    var anchor: AudioAnchor? = null
    var nextId = 0
    /** Labels of the undo steps committed, in order. */
    val commits = ArrayList<String>()
    val scheduled = LinkedHashMap<Runnable, Long>()
    var haptics = 0

    val content: PageContent get() = holder.content

    private fun PageHolder.setContentForTest(c: PageContent) {
        setContent(c)
        state = LoadState.LOADED
    }

    private fun install(c: PageContent) = holder.setContentForTest(c)

    override fun page(index: Int): PageHolder? = if (index == 0) holder else null
    override fun pageIndexOf(pageId: String): Int = if (pageId == holder.id) 0 else -1
    override fun isEditable(pageIndex: Int, notify: Boolean): Boolean = pageIndex == 0

    override fun pageToView(pageIndex: Int, px: Float, py: Float, out: FloatArray) { out[0] = px * scale; out[1] = py * scale }
    override fun viewToPage(pageIndex: Int, vx: Float, vy: Float, out: FloatArray) { out[0] = vx / scale; out[1] = vy / scale }
    override fun pageMatrix(pageIndex: Int, out: Matrix) {}
    override fun pageClip(pageIndex: Int, out: RectF): Boolean = false
    override fun pageBoundsToView(pageIndex: Int, b: Bounds, out: RectF) {}
    override fun invalidateOverlay() {}
    override fun invalidateOverlay(rect: RectF) {}
    override fun afterNextFrameCommitted(action: () -> Unit) = action()

    override fun newId(): String = "id-${nextId++}"
    override fun nextZ(pageIndex: Int): Int = content.maxZ() + 1
    override fun audioAnchor(): AudioAnchor? = anchor

    override fun commitNewItems(pageIndex: Int, items: List<Any>, label: String, select: Boolean) {
        install(ContentOps.add(content, items))
        commits += label
    }

    override fun applyEdit(pageIndex: Int, label: String, transform: (PageContent) -> PageContent): Boolean {
        val before = content
        val after = transform(before)
        if (after === before) return false
        install(after)
        commits += label
        return true
    }

    override fun beginEdit(label: String): EditSession = object : EditSession {
        private var before: PageContent? = null
        private var done = false
        override fun content(pageIndex: Int): PageContent? = if (done) null else this@FakeToolHost.content
        override fun update(pageIndex: Int, content: PageContent, changed: Bounds?) {
            if (done) return
            if (before == null) before = this@FakeToolHost.content
            install(content)
        }
        override fun commit(): Boolean {
            if (done) return false
            done = true
            val b = before ?: return false
            if (b === content) return false
            commits += label
            return true
        }
        override fun cancel() {
            if (done) return
            done = true
            before?.let { install(it) }
        }
    }

    override fun pushCommand(command: InkCommand) { commits += command.label }
    override fun setSelection(pageIndex: Int, ids: Set<String>) { selectedIds = ids }
    override fun clearSelection() { selectedIds = emptySet() }
    override fun panBy(dxPx: Float, dyPx: Float) {}
    override fun fling(vxPx: Float, vyPx: Float) {}
    override fun stopViewportMotion() {}
    override fun addOverlay(drawer: OverlayDrawer) {}
    override fun removeOverlay(drawer: OverlayDrawer) {}
    override fun schedule(delayMs: Long, action: Runnable) { scheduled[action] = delayMs }
    override fun unschedule(action: Runnable) { scheduled.remove(action) }
    override fun haptic() { haptics++ }
    override fun requestEdit(pageIndex: Int, item: Any, pageX: Float, pageY: Float) {}
    override fun beginItemDrag(pageIndex: Int, itemId: String, viewX: Float, viewY: Float, isFinger: Boolean): InkTool? = null

    // ---- Event helpers (view pixels in, page coordinates derived through [scale]).

    fun down(vx: Float, vy: Float, t: Long, pressure: Float = 0.5f, stylus: Boolean = true): ToolEvent =
        event(0, listOf(Sample(vx, vy, t, pressure)), stylus)

    fun move(samples: List<Sample>, stylus: Boolean = true, willPredict: Boolean = false): ToolEvent =
        event(0, samples, stylus).also { it.willPredict = willPredict }

    fun move(vx: Float, vy: Float, t: Long, pressure: Float = 0.5f, stylus: Boolean = true): ToolEvent =
        event(0, listOf(Sample(vx, vy, t, pressure)), stylus)

    class Sample(val vx: Float, val vy: Float, val t: Long, val pressure: Float = 0.5f, val tilt: Float = 0f)

    fun event(page: Int, samples: List<Sample>, stylus: Boolean): ToolEvent {
        val e = ToolEvent()
        e.reset(page)
        e.isStylus = stylus
        e.isFinger = !stylus
        for (s in samples) e.add(s.vx / scale, s.vy / scale, s.vx, s.vy, s.pressure, s.tilt, s.t)
        return e
    }
}
