package com.inkwell.notes.ink.viewport

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.ink.PageLayoutMode
import com.inkwell.notes.ink.geometry.Bounds

/** Clockwise page rotation (0/90/180/270) between unrotated page space and displayed space. */
object PageRotation {
    fun normalize(rotation: Int): Int {
        val r = ((rotation % 360) + 360) % 360
        return ((r + 45) / 90 * 90) % 360
    }

    fun displayWidth(w: Float, h: Float, rotation: Int): Float = if (rotation == 90 || rotation == 270) h else w
    fun displayHeight(w: Float, h: Float, rotation: Int): Float = if (rotation == 90 || rotation == 270) w else h

    /** Page point -> displayed point (relative to the displayed page's top-left). */
    fun toDisplay(px: Float, py: Float, w: Float, h: Float, rotation: Int, out: FloatArray) {
        when (rotation) {
            90 -> { out[0] = h - py; out[1] = px }
            180 -> { out[0] = w - px; out[1] = h - py }
            270 -> { out[0] = py; out[1] = w - px }
            else -> { out[0] = px; out[1] = py }
        }
    }

    /** Displayed point -> page point. */
    fun fromDisplay(dx: Float, dy: Float, w: Float, h: Float, rotation: Int, out: FloatArray) {
        when (rotation) {
            90 -> { out[0] = dy; out[1] = h - dx }
            180 -> { out[0] = w - dx; out[1] = h - dy }
            270 -> { out[0] = w - dy; out[1] = dx }
            else -> { out[0] = dx; out[1] = dy }
        }
    }

    /**
     * Affine page->display transform as [a, b, c, d, tx, ty] (x' = a x + c y + tx, y' = b x + d y + ty).
     */
    fun affine(w: Float, h: Float, rotation: Int, out: FloatArray) {
        when (rotation) {
            90 -> { out[0] = 0f; out[1] = 1f; out[2] = -1f; out[3] = 0f; out[4] = h; out[5] = 0f }
            180 -> { out[0] = -1f; out[1] = 0f; out[2] = 0f; out[3] = -1f; out[4] = w; out[5] = h }
            270 -> { out[0] = 0f; out[1] = -1f; out[2] = 1f; out[3] = 0f; out[4] = 0f; out[5] = w }
            else -> { out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 1f; out[4] = 0f; out[5] = 0f }
        }
    }
}

/**
 * Positions of pages in document space (points, displayed orientation). VERTICAL stacks pages
 * centred on a common axis; HORIZONTAL places them side by side; INFINITE is a single unbounded
 * page whose page coordinates equal document coordinates. Pure Kotlin.
 */
class PageLayout {
    var mode: PageLayoutMode = PageLayoutMode.VERTICAL
        private set
    var gap: Float = 16f
        private set
    var count: Int = 0
        private set
    private var rects = FloatArray(0)
    private var widths = FloatArray(0)
    private var heights = FloatArray(0)
    private var rotations = IntArray(0)

    /** Document bounds including a [gap] margin around all pages (empty for INFINITE). */
    val bounds = Bounds()
    var maxDisplayWidth = 0f
        private set
    var maxDisplayHeight = 0f
        private set

    val isInfinite: Boolean get() = mode == PageLayoutMode.INFINITE

    fun compute(pages: List<PageMeta>, mode: PageLayoutMode, gap: Float) {
        this.mode = mode
        this.gap = gap
        val n = if (mode == PageLayoutMode.INFINITE) minOf(1, pages.size) else pages.size
        count = n
        rects = FloatArray(n * 4)
        widths = FloatArray(n); heights = FloatArray(n); rotations = IntArray(n)
        maxDisplayWidth = 0f; maxDisplayHeight = 0f
        for (i in 0 until n) {
            val p = pages[i]
            val w = if (p.widthPt > 0f) p.widthPt else PageSizePreset.A4.widthPt
            val h = if (p.heightPt > 0f) p.heightPt else PageSizePreset.A4.heightPt
            widths[i] = w; heights[i] = h
            rotations[i] = PageRotation.normalize(p.rotation)
            maxDisplayWidth = maxOf(maxDisplayWidth, PageRotation.displayWidth(w, h, rotations[i]))
            maxDisplayHeight = maxOf(maxDisplayHeight, PageRotation.displayHeight(w, h, rotations[i]))
        }
        bounds.setEmpty()
        if (mode == PageLayoutMode.INFINITE) return
        var cursor = 0f
        for (i in 0 until n) {
            val dw = displayWidth(i); val dh = displayHeight(i)
            val l: Float; val t: Float
            if (mode == PageLayoutMode.VERTICAL) {
                l = (maxDisplayWidth - dw) / 2f; t = cursor; cursor += dh + gap
            } else {
                l = cursor; t = (maxDisplayHeight - dh) / 2f; cursor += dw + gap
            }
            rects[i * 4] = l; rects[i * 4 + 1] = t; rects[i * 4 + 2] = l + dw; rects[i * 4 + 3] = t + dh
            bounds.union(l, t, l + dw, t + dh)
        }
        if (n > 0) bounds.outset(gap)
    }

    fun left(i: Int): Float = if (isInfinite) 0f else rects[i * 4]
    fun top(i: Int): Float = if (isInfinite) 0f else rects[i * 4 + 1]
    fun right(i: Int): Float = rects[i * 4 + 2]
    fun bottom(i: Int): Float = rects[i * 4 + 3]

    /** Unrotated page width/height in points. */
    fun pageWidth(i: Int): Float = widths[i]
    fun pageHeight(i: Int): Float = heights[i]
    fun rotation(i: Int): Int = if (isInfinite) 0 else rotations[i]
    fun displayWidth(i: Int): Float = PageRotation.displayWidth(widths[i], heights[i], rotation(i))
    fun displayHeight(i: Int): Float = PageRotation.displayHeight(widths[i], heights[i], rotation(i))

    /** Page point -> document point. */
    fun pageToDoc(i: Int, px: Float, py: Float, out: FloatArray) {
        if (isInfinite) { out[0] = px; out[1] = py; return }
        PageRotation.toDisplay(px, py, widths[i], heights[i], rotations[i], out)
        out[0] += rects[i * 4]; out[1] += rects[i * 4 + 1]
    }

    /** Document point -> page point of page [i] (may lie outside the page). */
    fun docToPage(i: Int, dx: Float, dy: Float, out: FloatArray) {
        if (isInfinite) { out[0] = dx; out[1] = dy; return }
        PageRotation.fromDisplay(dx - rects[i * 4], dy - rects[i * 4 + 1], widths[i], heights[i], rotations[i], out)
    }

    /** The page containing the document point, or the nearest one along the scroll axis. */
    fun nearestPage(dx: Float, dy: Float): Int {
        if (count == 0) return -1
        if (isInfinite) return 0
        var best = 0
        var bestD = Float.POSITIVE_INFINITY
        for (i in 0 until count) {
            val l = rects[i * 4]; val t = rects[i * 4 + 1]; val r = rects[i * 4 + 2]; val b = rects[i * 4 + 3]
            val ddx = maxOf(l - dx, 0f, dx - r)
            val ddy = maxOf(t - dy, 0f, dy - b)
            val d = ddx * ddx + ddy * ddy
            if (d < bestD) { bestD = d; best = i; if (d == 0f) break }
        }
        return best
    }

    /** Indices of pages intersecting the document rectangle, written into [out]; returns count. */
    fun pagesIn(l: Float, t: Float, r: Float, b: Float, out: IntArray): Int {
        if (isInfinite) { if (count > 0 && out.isNotEmpty()) { out[0] = 0; return 1 }; return 0 }
        var k = 0
        for (i in 0 until count) {
            if (rects[i * 4] <= r && rects[i * 4 + 2] >= l && rects[i * 4 + 1] <= b && rects[i * 4 + 3] >= t) {
                if (k < out.size) out[k] = i
                k++
            }
        }
        return minOf(k, out.size)
    }
}
