package com.inkwell.notes.ink.select

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.engine.InkEngine
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.render.PageRenderer
import com.inkwell.notes.ink.render.RenderOrder
import com.inkwell.notes.ink.tools.EditSession
import com.inkwell.notes.ink.tools.ToolEvent
import com.inkwell.notes.ink.viewport.PageRotation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The selection box and its direct manipulation: handles, hit testing, moving (with alignment
 * guides on the infinite canvas and page changes in paged layouts), resizing and rotating.
 *
 * While a drag runs, the selected items (and connectors attached to them) are lifted off the
 * page into the overlay and drawn through the live transform; on release the transform is baked
 * into the items and the whole manipulation becomes one undo step. Main thread only.
 */
internal class SelectionController(private val e: InkEngine) {
    val handles = SelectionHandles()
    private val painter = SelectionPainter(e)

    private class Manipulation(
        val pageIndex: Int,
        val pageId: String,
        val handle: Handle,
        val startVX: Float,
        val startVY: Float,
        /** Page -> view transform when the gesture started (the frame of all handle math). */
        val v0: Affine,
        val v0inv: Affine,
        val boundsPage: Bounds,
        val boundsView0: Bounds,
        val ids: Set<String>,
        val rotationBase: Float,
        val isFinger: Boolean,
    ) {
        var lastVX = startVX
        var lastVY = startVY
        var lifted = false
        var session: EditSession? = null
        var before: PageContent = PageContent.EMPTY
        var items: List<Any> = emptyList()
        var others: List<Any> = emptyList()
        var strokePaths: List<Pair<Stroke, Path>> = emptyList()
        var attached: List<ConnectorItem> = emptyList()
        var referencedStrokes: List<Stroke> = emptyList()
        var visual = Bounds()
        var candidates: List<Bounds> = emptyList()
        var transform = Affine.IDENTITY
        var guides: List<AlignmentGuides.Guide> = emptyList()
        var target = -1
        var angle = 0f
    }

    private var m: Manipulation? = null
    val isManipulating: Boolean get() = m != null
    /** True while selected items are lifted off their page for a drag. */
    val isLifted: Boolean get() = m?.lifted == true

    private val tmp = FloatArray(2)
    private val tmpBounds = Bounds()
    private val rect = RectF()
    private val affine = FloatArray(6)

    // Cached summary of the current selection (recomputed when content or ids change).
    private var cacheContent: PageContent? = null
    private var cacheIds: Set<String>? = null
    private val cacheBounds = Bounds()
    private var cacheCount = 0
    private var hasImage = false
    private var onlyText = false
    private var onlyUnrotatable = false
    private var singleRotation = 0f

    private fun dp(v: Float) = v * e.density

    // ------------------------------------------------------------------ Selection summary

    private fun refresh(content: PageContent, ids: Set<String>): Boolean {
        if (content === cacheContent && ids === cacheIds) return !cacheBounds.isEmpty
        cacheContent = content; cacheIds = ids
        cacheBounds.setEmpty()
        cacheCount = 0; hasImage = false; onlyText = true; onlyUnrotatable = true
        var last: Any? = null
        for (item in ContentOps.allItems(content)) {
            if (Items.id(item) !in ids) continue
            cacheBounds.union(ItemGeometry.bounds(item, tmpBounds, content))
            cacheCount++
            last = item
            if (item is ImageItem) hasImage = true
            if (item !is TextItem) onlyText = false
            if (item !is StickyItem && item !is ConnectorItem) onlyUnrotatable = false
        }
        singleRotation = if (cacheCount == 1) when (val it = last) {
            is TextItem -> it.rotation
            is ImageItem -> it.rotation
            is ShapeItem -> if (it.kind == ShapeKind.RECTANGLE || it.kind == ShapeKind.ROUNDED_RECTANGLE || it.kind == ShapeKind.ELLIPSE) it.rotation else 0f
            else -> 0f
        } else 0f
        return !cacheBounds.isEmpty
    }

    /** Lays the handles out around the selection on page [i] (call after [refresh]). */
    private fun layoutHandles(i: Int) {
        e.pageBoundsToView(i, cacheBounds, rect)
        val pad = dp(PAD_DP)
        handles.box.set(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
        handles.touchRadius = dp(22f)
        handles.rotateOffset = dp(30f)
        handles.showRotate = !onlyUnrotatable
        // Images keep their aspect ratio: corners only.
        handles.showEdges = !hasImage
        handles.showHorizontalEdges = handles.box.height >= dp(MIN_EDGE_SPAN_DP)
        // Text boxes re-wrap on width changes; their height follows the text.
        handles.showVerticalEdges = !onlyText && handles.box.width >= dp(MIN_EDGE_SPAN_DP)
    }

    /** The current selection's page, with its summary refreshed; -1 when nothing is selected or visible. */
    private fun currentPage(): Int {
        val i = e.selectionPageIndex
        if (i < 0 || e.selectedIds.isEmpty() || !e.layoutIndexValid(i)) return -1
        val h = e.page(i) ?: return -1
        if (!h.isLoaded) return -1
        if (!refresh(h.content, e.selectedIds)) return -1
        return i
    }

    /** Handle or body of the selection under view point ([vx], [vy]); null when outside. */
    fun hit(vx: Float, vy: Float): Handle? {
        if (m != null) return null
        val i = currentPage()
        if (i < 0) return null
        layoutHandles(i)
        return handles.hit(vx, vy)
    }

    /**
     * True when the selection box covers most of the view: a single finger then scrolls instead
     * of dragging it (the stylus still drags), so a large selection never traps navigation.
     */
    fun coversView(): Boolean {
        if (m != null) return false
        val i = currentPage()
        if (i < 0) return false
        layoutHandles(i)
        val vp = e.viewport
        val b = handles.box
        val w = (minOf(b.right, vp.viewWidth) - maxOf(b.left, 0f)).coerceAtLeast(0f)
        val h = (minOf(b.bottom, vp.viewHeight) - maxOf(b.top, 0f)).coerceAtLeast(0f)
        return w * h >= 0.8f * vp.viewWidth * vp.viewHeight
    }

    // ------------------------------------------------------------------ Gestures

    /** Starts a manipulation at view point ([vx], [vy]) grabbing [handle] (hit-tested when null). */
    fun begin(vx: Float, vy: Float, handle: Handle? = null, isFinger: Boolean = false): Boolean {
        commit()
        val grabbed = handle ?: hit(vx, vy) ?: return false
        val i = currentPage()
        if (i < 0) return false
        if (!e.isEditable(i)) return false
        val h = e.page(i) ?: return false
        e.viewport.pageToViewAffine(e.layout, i, affine)
        val v0 = Affine.of(affine)
        val inv = v0.inverse() ?: return false
        val bp = cacheBounds.copy()
        m = Manipulation(
            pageIndex = i, pageId = h.id, handle = grabbed, startVX = vx, startVY = vy, v0 = v0, v0inv = inv,
            boundsPage = bp, boundsView0 = SelectionMath.transformedBounds(bp, v0), ids = LinkedHashSet(e.selectedIds),
            rotationBase = singleRotation, isFinger = isFinger,
        )
        e.invalidateOverlay()
        return true
    }

    fun onDown(ev: ToolEvent) { begin(ev.viewX, ev.viewY, null, ev.isFinger) }

    fun onMove(ev: ToolEvent) {
        val mm = m ?: return
        mm.lastVX = ev.viewX; mm.lastVY = ev.viewY
        if (!mm.lifted) {
            val slop = dp(if (mm.isFinger) FINGER_SLOP_DP else STYLUS_SLOP_DP)
            if (hypot(mm.lastVX - mm.startVX, mm.lastVY - mm.startVY) < slop) return
            if (!lift(mm)) { m = null; e.invalidateOverlay(); return }
        }
        update(mm)
    }

    fun onUp(ev: ToolEvent) {
        val mm = m ?: return
        if (!mm.lifted) {
            m = null
            e.invalidateOverlay()
            tap(mm, ev)
            return
        }
        mm.lastVX = ev.viewX; mm.lastVY = ev.viewY
        update(mm)
        commit()
    }

    fun onViewportChanged() {
        val mm = m ?: return
        if (mm.lifted) update(mm) else e.invalidateOverlay()
    }

    /** Abandons the manipulation, restoring the page. */
    fun cancel() {
        val mm = m ?: return
        m = null
        stopAutoScroll()
        mm.session?.cancel()
        e.invalidateOverlay()
    }

    private fun tap(mm: Manipulation, ev: ToolEvent) {
        val h = e.page(mm.pageIndex) ?: return
        if (!h.isLoaded) return
        e.viewToPage(mm.pageIndex, ev.viewX, ev.viewY, tmp)
        val hit = h.index().hitTest(tmp[0], tmp[1], 6f * e.density / e.scale)
        val id = hit?.let { Items.id(it) }
        val double = e.taps.tap(ev.eventTime, ev.viewX, ev.viewY, id)
        when {
            hit is StickyItem && double -> e.requestEdit(mm.pageIndex, hit, tmp[0], tmp[1])
            hit is TextItem && (double || e.toolState.tool == com.inkwell.notes.core.model.ToolType.TEXT) -> e.requestEdit(mm.pageIndex, hit, tmp[0], tmp[1])
            hit != null -> e.listener?.onItemTapped(Items.ref(h.id, hit))
        }
    }

    private fun lift(mm: Manipulation): Boolean {
        val h = e.page(mm.pageIndex) ?: return false
        if (!h.isLoaded || h.id != mm.pageId) return false
        val content = h.content
        val ids = mm.ids
        val fragment = ContentOps.fragment(content, ids)
        mm.items = RenderOrder.order(fragment)
        if (mm.items.isEmpty()) return false
        mm.attached = content.connectors.filter { it.id !in ids && (it.fromItemId in ids || it.toItemId in ids) }
        val liftIds = HashSet(ids).also { s -> mm.attached.forEach { s += it.id } }
        val changed = mm.boundsPage.copy()
        for (c in mm.attached) changed.union(ItemGeometry.bounds(c, tmpBounds, content))
        mm.before = content
        mm.strokePaths = mm.items.filterIsInstance<Stroke>().map { s -> s to Path().also { PageRenderer.buildStrokeOutline(s, it) } }
        mm.others = mm.items.filter { it !is Stroke }
        val referenced = HashSet<String>()
        for (c in mm.attached + mm.others.filterIsInstance<ConnectorItem>()) { c.fromItemId?.let { referenced += it }; c.toItemId?.let { referenced += it } }
        mm.referencedStrokes = mm.items.filterIsInstance<Stroke>().filter { it.id in referenced }
        val visual = Bounds()
        for (item in mm.items) visual.union(visualBounds(item, content, tmpBounds))
        mm.visual = if (visual.isEmpty) mm.boundsPage.copy() else visual
        val label = when {
            mm.handle == Handle.BODY -> "Move"
            mm.handle == Handle.ROTATE -> "Rotate"
            else -> "Resize"
        }
        val session = e.beginEdit(label)
        mm.session = session
        mm.lifted = true
        session.update(mm.pageIndex, ContentOps.remove(content, liftIds), changed)
        if (mm.handle == Handle.BODY && e.layout.isInfinite) mm.candidates = alignmentCandidates(mm)
        return true
    }

    /** Bounds of nearby items (on screen) to align with: boxes, shapes and larger drawings. */
    private fun alignmentCandidates(mm: Manipulation): List<Bounds> {
        val h = e.page(mm.pageIndex) ?: return emptyList()
        val vp = e.viewport
        val area = Bounds()
        e.viewToPage(mm.pageIndex, 0f, 0f, tmp); area.union(tmp[0], tmp[1])
        e.viewToPage(mm.pageIndex, vp.viewWidth, vp.viewHeight, tmp); area.union(tmp[0], tmp[1])
        area.outset(area.width * 0.25f)
        val out = ArrayList<Bounds>()
        val content = h.content
        h.index().query(area) { entry ->
            if (out.size >= MAX_CANDIDATES) return@query
            val item = entry.item
            if (item is ConnectorItem) return@query
            val b = visualBounds(item, content, Bounds())
            if (b.isEmpty) return@query
            if (item is Stroke && b.width < 40f && b.height < 40f) return@query
            out += b
        }
        return out
    }

    /** Bounds of what the eye sees: ink extents without width padding, shape outlines, boxes. */
    private fun visualBounds(item: Any, content: PageContent, out: Bounds): Bounds = when (item) {
        is Stroke -> com.inkwell.notes.ink.geometry.Geo.polylineBounds(item.points, com.inkwell.notes.core.model.InkPoints.STRIDE, out)
        is ShapeItem -> com.inkwell.notes.ink.geometry.Geo.polylineBounds(ItemGeometry.shapeOutline(item), 2, out)
        is StickyItem -> out.set(item.x, item.y, item.x + item.width, item.y + item.height)
        is ConnectorItem -> out.setEmpty()
        else -> ItemGeometry.bounds(item, out, content)
    }

    private fun update(mm: Manipulation) {
        e.viewToPage(mm.pageIndex, mm.lastVX, mm.lastVY, tmp)
        // Pointer in the gesture's start frame (the viewport may have moved since).
        val qx = mm.v0.mapX(tmp[0], tmp[1]); val qy = mm.v0.mapY(tmp[0], tmp[1])
        val bv = mm.boundsView0
        val tv = when (mm.handle) {
            Handle.BODY -> Affine.translate(qx - mm.startVX, qy - mm.startVY)
            Handle.ROTATE -> {
                val deg = SelectionMath.rotationDegrees(bv.centerX, bv.centerY, mm.startVX, mm.startVY, qx, qy, mm.rotationBase)
                mm.angle = deg
                Affine.rotate(deg * PI.toFloat() / 180f, bv.centerX, bv.centerY)
            }
            else -> SelectionMath.scaleTransform(mm.handle, bv, mm.startVX, mm.startVY, qx, qy, dp(MIN_SIZE_DP))
        }
        var t = mm.v0.then(tv).then(mm.v0inv)
        mm.guides = emptyList()
        if (mm.handle == Handle.BODY && e.layout.isInfinite && mm.candidates.isNotEmpty()) {
            val moved = SelectionMath.transformedBounds(mm.visual, t)
            val r = AlignmentGuides.snap(moved, mm.candidates, dp(SNAP_DP) / e.scale)
            if (r.dx != 0f || r.dy != 0f) t = t.then(Affine.translate(r.dx, r.dy))
            mm.guides = r.guides
        }
        mm.target = if (mm.handle == Handle.BODY && !e.layout.isInfinite) targetPage(mm, t) else -1
        if (mm.handle == Handle.BODY && !e.layout.isInfinite && mm.target < 0) {
            // Off every page: keep the selection's centre on its own page so nothing gets lost.
            val cx = t.mapX(mm.visual.centerX, mm.visual.centerY); val cy = t.mapY(mm.visual.centerX, mm.visual.centerY)
            val ccx = cx.coerceIn(0f, e.layout.pageWidth(mm.pageIndex)); val ccy = cy.coerceIn(0f, e.layout.pageHeight(mm.pageIndex))
            if (ccx != cx || ccy != cy) t = t.then(Affine.translate(ccx - cx, ccy - cy))
        }
        mm.transform = t
        e.invalidateOverlay()
        if (mm.handle == Handle.BODY) autoScroll()
    }

    /** Page the moved selection's centre now lies on, when it is another loaded page. */
    private fun targetPage(mm: Manipulation, t: Affine): Int {
        val cx = mm.visual.centerX; val cy = mm.visual.centerY
        val layout = e.layout
        layout.pageToDoc(mm.pageIndex, t.mapX(cx, cy), t.mapY(cx, cy), tmp)
        val j = layout.nearestPage(tmp[0], tmp[1])
        if (j < 0 || j == mm.pageIndex) return -1
        if (tmp[0] < layout.left(j) || tmp[0] > layout.right(j) || tmp[1] < layout.top(j) || tmp[1] > layout.bottom(j)) return -1
        val h = e.page(j) ?: return -1
        return if (h.isLoaded && e.isEditable(j, notify = false)) j else -1
    }

    /** Page -> document transform of page [i]. */
    private fun docAffine(i: Int): Affine {
        val l = e.layout
        PageRotation.affine(l.pageWidth(i), l.pageHeight(i), l.rotation(i), affine)
        return Affine.of(affine).then(Affine.translate(l.left(i), l.top(i)))
    }

    /** Bakes the current transform into the items and records one undo step. */
    fun commit() {
        val mm = m ?: return
        m = null
        stopAutoScroll()
        e.invalidateOverlay()
        if (!mm.lifted) return
        val session = mm.session ?: return
        val h = e.page(mm.pageIndex)
        if (h == null || !h.isLoaded || h.id != mm.pageId) { session.cancel(); return }
        val t = mm.transform
        if (isNoOp(t)) {
            session.cancel()
            e.reportSelection()
            return
        }
        val baked = mm.items.map { item ->
            val b = TransformBaker.bake(item, t)
            if (b is TextItem && item is TextItem) e.editor.refitText(b, item) else b
        }
        val target = mm.target
        if (target >= 0 && target != mm.pageIndex && e.page(target)?.isLoaded == true) {
            moveToPage(mm, session, baked, target)
            return
        }
        val final = ContentOps.replace(mm.before, baked.associateBy { Items.id(it) })
        val changed = mm.boundsPage.copy()
        for (item in baked) changed.union(ItemGeometry.bounds(item, tmpBounds, final))
        for (c in mm.attached) changed.union(ItemGeometry.bounds(c, tmpBounds, final)).union(ItemGeometry.bounds(c, tmpBounds, mm.before))
        session.update(mm.pageIndex, final, changed)
        e.render.addPending(h, baked + mm.attached)
        session.commit()
        e.reportSelection()
    }

    private fun moveToPage(mm: Manipulation, session: EditSession, baked: List<Any>, target: Int) {
        val tgt = e.page(target) ?: run { session.cancel(); return }
        val toTarget = docAffine(mm.pageIndex).then(docAffine(target).inverse() ?: run { session.cancel(); return })
        val r = CrossPageMove.compute(mm.before, tgt.content, mm.items, baked, mm.attached, mm.transform, toTarget, e.nextZ(target))
        val moved = r.moved
        val srcFinal = r.source
        val tgtFinal = r.target
        val srcChanged = mm.boundsPage.copy()
        for (c in mm.attached) srcChanged.union(ItemGeometry.bounds(c, tmpBounds, mm.before))
        val tgtChanged = Bounds()
        for (item in moved) tgtChanged.union(ItemGeometry.bounds(item, tmpBounds, tgtFinal))
        // Target first and the selection with it, so the selection never points at missing items.
        session.update(target, tgtFinal, tgtChanged)
        e.setSelection(target, moved.mapTo(LinkedHashSet()) { Items.id(it) })
        session.update(mm.pageIndex, srcFinal, srcChanged)
        e.render.addPending(tgt, moved)
        session.commit()
    }

    private fun isNoOp(t: Affine): Boolean =
        abs(t.a - 1f) < 1e-5f && abs(t.d - 1f) < 1e-5f && abs(t.b) < 1e-5f && abs(t.c) < 1e-5f && abs(t.tx) < 1e-3f && abs(t.ty) < 1e-3f

    // ------------------------------------------------------------------ Auto-scroll near the edges

    private var scrolling = false
    private val scrollStep = object : Runnable {
        override fun run() {
            val mm = m
            if (mm == null || !mm.lifted || mm.handle != Handle.BODY) { scrolling = false; return }
            val v = edgeVelocity(mm)
            if (v[0] == 0f && v[1] == 0f) { scrolling = false; return }
            e.viewportCtl.panBy(v[0], v[1])
            e.view.postOnAnimation(this)
        }
    }

    private fun edgeVelocity(mm: Manipulation): FloatArray {
        val vp = e.viewport
        val edge = dp(EDGE_DP); val speed = dp(EDGE_SPEED_DP)
        fun axis(p: Float, size: Float): Float = when {
            size <= edge * 3f -> 0f
            p < edge -> speed * (1f - (p / edge).coerceIn(0f, 1f))
            p > size - edge -> -speed * (1f - ((size - p) / edge).coerceIn(0f, 1f))
            else -> 0f
        }
        tmp[0] = axis(mm.lastVX, vp.viewWidth); tmp[1] = axis(mm.lastVY, vp.viewHeight)
        return floatArrayOf(tmp[0], tmp[1])
    }

    private fun autoScroll() {
        val mm = m ?: return
        if (scrolling) return
        val v = edgeVelocity(mm)
        if (v[0] == 0f && v[1] == 0f) return
        scrolling = true
        e.view.postOnAnimation(scrollStep)
    }

    private fun stopAutoScroll() {
        scrolling = false
        e.view.removeCallbacks(scrollStep)
    }

    // ------------------------------------------------------------------ Drawing

    fun draw(canvas: Canvas) {
        val mm = m
        if (mm != null && mm.lifted) { drawLifted(canvas, mm); return }
        val i = currentPage()
        if (i < 0) return
        layoutHandles(i)
        painter.drawBox(canvas, handles, active = mm?.handle)
    }

    private fun drawLifted(canvas: Canvas, mm: Manipulation) {
        if (!e.layoutIndexValid(mm.pageIndex)) return
        val t = mm.transform
        val current = mm.session?.content(mm.pageIndex) ?: PageContent.EMPTY
        val bakedOthers = mm.others.map { TransformBaker.bake(it, t) }
        val needsContext = mm.attached.isNotEmpty() || bakedOthers.any { it is ConnectorItem }
        val context = if (needsContext) ContentOps.add(current, bakedOthers + mm.referencedStrokes.map { TransformBaker.bake(it, t) }) else current
        painter.drawLifted(canvas, mm.pageIndex, t, mm.strokePaths, bakedOthers, mm.attached, context)
        painter.drawTransformedBox(canvas, mm.pageIndex, mm.boundsPage, t)
        if (mm.guides.isNotEmpty()) painter.drawGuides(canvas, mm.pageIndex, mm.guides)
        if (mm.target >= 0) painter.drawTargetPage(canvas, mm.target)
        if (mm.handle == Handle.ROTATE) {
            val tb = SelectionMath.transformedBounds(mm.boundsPage, t)
            e.pageBoundsToView(mm.pageIndex, tb, rect)
            painter.drawAngle(canvas, mm.rotationBase + mm.angle, rect.centerX(), rect.top - dp(28f))
        }
    }

    companion object {
        const val PAD_DP = 6f
        const val MIN_EDGE_SPAN_DP = 64f
        const val STYLUS_SLOP_DP = 3f
        const val FINGER_SLOP_DP = 8f
        const val MIN_SIZE_DP = 12f
        const val SNAP_DP = 6f
        const val EDGE_DP = 40f
        const val EDGE_SPEED_DP = 14f
        const val MAX_CANDIDATES = 300
    }
}
