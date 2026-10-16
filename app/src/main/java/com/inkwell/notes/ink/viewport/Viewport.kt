package com.inkwell.notes.ink.viewport

import com.inkwell.notes.ink.PageLayoutMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Zoom and scroll of the canvas, plus page <-> view conversions. Pure Kotlin.
 *
 * Document coordinates are page points laid out by [PageLayout]. [scrollX]/[scrollY] are the
 * document coordinates at the view's top-left corner. Pixels per point = [baseScale] * [zoom].
 * For paged layouts zoom 1 means "fit width" (VERTICAL) or "fit page" (HORIZONTAL); for the
 * infinite canvas zoom 1 means one point per dp.
 *
 * Page origins are snapped to whole pixels so committed tiles, the live stroke and overlays
 * line up exactly.
 */
class Viewport {
    var viewWidth = 0f
        private set
    var viewHeight = 0f
        private set
    var density = 1f
    var baseScale = 1f
        private set
    var zoom = 1f
        private set
    var minZoom = 0.25f
        private set
    var maxZoom = 8f
        private set
    var scrollX = 0f
    var scrollY = 0f
    var mode: PageLayoutMode = PageLayoutMode.VERTICAL
        private set

    val scale: Float get() = baseScale * zoom
    val hasSize: Boolean get() = viewWidth > 0f && viewHeight > 0f

    /** Margin around pages at zoom 1 (dp) — also the gap between pages. */
    val marginDp = 24f

    fun setViewSize(w: Float, h: Float) { viewWidth = w; viewHeight = h }

    /** Recomputes the base scale and zoom limits for [layout] and returns the page gap in points. */
    fun configure(mode: PageLayoutMode, layout: PageLayout): Float {
        this.mode = mode
        val margin = marginDp * density
        baseScale = when (mode) {
            PageLayoutMode.INFINITE -> density
            PageLayoutMode.VERTICAL -> fitScale(viewWidth, margin, layout.maxDisplayWidth)
            PageLayoutMode.HORIZONTAL -> min(fitScale(viewWidth, margin, layout.maxDisplayWidth), fitScale(viewHeight, margin, layout.maxDisplayHeight))
        }.coerceAtLeast(1e-3f)
        if (mode == PageLayoutMode.INFINITE) { minZoom = 0.05f; maxZoom = 8f } else { minZoom = 0.25f; maxZoom = 8f }
        zoom = zoom.coerceIn(minZoom, maxZoom)
        return if (mode == PageLayoutMode.INFINITE) 0f else margin / baseScale
    }

    private fun fitScale(viewSize: Float, margin: Float, content: Float): Float {
        if (content <= 0f || viewSize <= 0f) return density
        val avail = max(viewSize - 2f * margin, viewSize * 0.6f)
        return avail / content
    }

    fun docToViewX(x: Float): Float = (x - scrollX) * scale
    fun docToViewY(y: Float): Float = (y - scrollY) * scale
    fun viewToDocX(vx: Float): Float = vx / scale + scrollX
    fun viewToDocY(vy: Float): Float = vy / scale + scrollY

    /** Sets the zoom keeping the document point under (vx, vy) fixed on screen. */
    fun zoomAround(newZoom: Float, vx: Float, vy: Float) {
        val dx = viewToDocX(vx); val dy = viewToDocY(vy)
        zoom = newZoom.coerceIn(minZoom, maxZoom)
        scrollX = dx - vx / scale
        scrollY = dy - vy / scale
    }

    fun setZoomUnanchored(newZoom: Float) { zoom = newZoom.coerceIn(minZoom, maxZoom) }

    /** Moves content by (dx, dy) pixels (a finger dragging right moves content right). */
    fun panByPx(dx: Float, dy: Float) {
        scrollX -= dx / scale
        scrollY -= dy / scale
    }

    /** Scroll limits [minX, maxX, minY, maxY] for [layout]; min == max means "centred". */
    fun scrollRange(layout: PageLayout, out: FloatArray) {
        if (layout.isInfinite || layout.count == 0 || layout.bounds.isEmpty) {
            out[0] = -INFINITE_LIMIT; out[1] = INFINITE_LIMIT; out[2] = -INFINITE_LIMIT; out[3] = INFINITE_LIMIT
            return
        }
        val b = layout.bounds
        val visW = viewWidth / scale; val visH = viewHeight / scale
        if (b.width <= visW) { val c = b.left - (visW - b.width) / 2f; out[0] = c; out[1] = c } else { out[0] = b.left; out[1] = b.right - visW }
        if (b.height <= visH) { val c = b.top - (visH - b.height) / 2f; out[2] = c; out[3] = c } else { out[2] = b.top; out[3] = b.bottom - visH }
    }

    private val range = FloatArray(4)

    fun clamp(layout: PageLayout) {
        scrollRange(layout, range)
        scrollX = scrollX.coerceIn(range[0], range[1])
        scrollY = scrollY.coerceIn(range[2], range[3])
    }

    // ---------------------------------------------------------------- Pages

    /** View x of page [i]'s displayed top-left, snapped to whole pixels. */
    fun pageOriginX(layout: PageLayout, i: Int): Float = snap((layout.left(i) - scrollX) * scale)
    fun pageOriginY(layout: PageLayout, i: Int): Float = snap((layout.top(i) - scrollY) * scale)

    private fun snap(v: Float): Float = if (abs(v) < 1e7f) v.roundToInt().toFloat() else v

    private val tmp = FloatArray(2)

    /** Page point of page [i] -> view pixel, written to [out]. */
    fun pageToView(layout: PageLayout, i: Int, px: Float, py: Float, out: FloatArray) {
        if (layout.isInfinite) {
            out[0] = pageOriginX(layout, i) + px * scale; out[1] = pageOriginY(layout, i) + py * scale
            return
        }
        PageRotation.toDisplay(px, py, layout.pageWidth(i), layout.pageHeight(i), layout.rotation(i), tmp)
        out[0] = pageOriginX(layout, i) + tmp[0] * scale
        out[1] = pageOriginY(layout, i) + tmp[1] * scale
    }

    /** View pixel -> page point of page [i], written to [out]. */
    fun viewToPage(layout: PageLayout, i: Int, vx: Float, vy: Float, out: FloatArray) {
        val dx = (vx - pageOriginX(layout, i)) / scale
        val dy = (vy - pageOriginY(layout, i)) / scale
        if (layout.isInfinite) { out[0] = dx; out[1] = dy; return }
        PageRotation.fromDisplay(dx, dy, layout.pageWidth(i), layout.pageHeight(i), layout.rotation(i), out)
    }

    /**
     * Page->view affine [a, b, c, d, tx, ty] for page [i] (x' = a x + c y + tx, y' = b x + d y + ty).
     */
    fun pageToViewAffine(layout: PageLayout, i: Int, out: FloatArray) {
        val s = scale
        if (layout.isInfinite) {
            out[0] = s; out[1] = 0f; out[2] = 0f; out[3] = s; out[4] = pageOriginX(layout, i); out[5] = pageOriginY(layout, i)
            return
        }
        PageRotation.affine(layout.pageWidth(i), layout.pageHeight(i), layout.rotation(i), out)
        for (k in 0 until 4) out[k] *= s
        out[4] = out[4] * s + pageOriginX(layout, i)
        out[5] = out[5] * s + pageOriginY(layout, i)
    }

    /** Page index under the view centre (current page). */
    fun currentPage(layout: PageLayout): Int {
        if (layout.count == 0) return 0
        if (layout.isInfinite) return 0
        val cx = viewToDocX(viewWidth / 2f); val cy = viewToDocY(viewHeight / 2f)
        return layout.nearestPage(cx, cy).coerceAtLeast(0)
    }

    /** Scroll position that shows page [i] at the start of the scroll axis (with the margin). */
    fun scrollForPage(layout: PageLayout, i: Int, out: FloatArray) {
        val visW = viewWidth / scale; val visH = viewHeight / scale
        when (layout.mode) {
            PageLayoutMode.VERTICAL -> { out[0] = scrollX; out[1] = layout.top(i) - layout.gap }
            PageLayoutMode.HORIZONTAL -> {
                out[0] = (layout.left(i) + layout.right(i)) / 2f - visW / 2f
                out[1] = (layout.top(i) + layout.bottom(i)) / 2f - visH / 2f
            }
            PageLayoutMode.INFINITE -> { out[0] = scrollX; out[1] = scrollY }
        }
    }

    companion object {
        /** Coordinate bound of the infinite canvas (keeps float precision well under a point). */
        const val INFINITE_LIMIT = 2_000_000f
    }
}
