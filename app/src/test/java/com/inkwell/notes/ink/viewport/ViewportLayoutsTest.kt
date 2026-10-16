package com.inkwell.notes.ink.viewport

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ink.PageLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coordinate round trips and limits across layouts, rotations and zoom extremes. */
class ViewportLayoutsTest {
    private fun page(i: Int, w: Float = 600f, h: Float = 800f, rotation: Int = 0) =
        PageMeta(id = "p$i", documentId = "d", index = i, widthPt = w, heightPt = h, rotation = rotation)

    private fun setup(mode: PageLayoutMode, pages: List<PageMeta>, w: Float = 1200f, h: Float = 1800f, density: Float = 2.5f): Pair<Viewport, PageLayout> {
        val vp = Viewport()
        vp.density = density
        vp.setViewSize(w, h)
        val layout = PageLayout()
        layout.compute(pages, mode, 0f)
        val gap = vp.configure(mode, layout)
        layout.compute(pages, mode, gap)
        return vp to layout
    }

    private val out = FloatArray(2)
    private val affine = FloatArray(6)

    private fun check(vp: Viewport, layout: PageLayout, i: Int, px: Float, py: Float) {
        vp.pageToView(layout, i, px, py, out)
        val vx = out[0]; val vy = out[1]
        // The affine form must agree with the point form (tiles and overlays share it).
        vp.pageToViewAffine(layout, i, affine)
        assertEquals(vx, affine[0] * px + affine[2] * py + affine[4], 1e-2f)
        assertEquals(vy, affine[1] * px + affine[3] * py + affine[5], 1e-2f)
        vp.viewToPage(layout, i, vx, vy, out)
        assertEquals(px, out[0], 1e-2f)
        assertEquals(py, out[1], 1e-2f)
        // And the document mapping agrees with the view mapping.
        layout.pageToDoc(i, px, py, out)
        assertEquals(vx, vp.docToViewX(out[0]), 1.01f) // page origins snap to whole pixels
        assertEquals(vy, vp.docToViewY(out[1]), 1.01f)
        layout.docToPage(i, out[0], out[1], out)
        assertEquals(px, out[0], 1e-3f); assertEquals(py, out[1], 1e-3f)
    }

    @Test fun mixedRotationsInBothPagedLayouts() {
        val pages = listOf(page(0), page(1, rotation = 90), page(2, w = 400f, h = 300f, rotation = 180), page(3, rotation = 270))
        for (mode in listOf(PageLayoutMode.VERTICAL, PageLayoutMode.HORIZONTAL)) {
            val (vp, layout) = setup(mode, pages)
            for (zoom in listOf(vp.minZoom, 0.7f, 1f, 3.3f, vp.maxZoom)) {
                vp.zoomAround(zoom, 300f, 900f)
                vp.scrollX += 17.3f; vp.scrollY -= 211.9f
                for (i in pages.indices) {
                    check(vp, layout, i, 0f, 0f)
                    check(vp, layout, i, layout.pageWidth(i), layout.pageHeight(i))
                    check(vp, layout, i, 123.4f, 567.8f)
                    check(vp, layout, i, -20f, -20f) // margins map too (strokes may start off-page)
                }
            }
            // Rotated pages take their displayed size in the layout.
            assertEquals(800f, layout.displayWidth(1), 0f); assertEquals(600f, layout.displayHeight(1), 0f)
            assertEquals(400f, layout.displayWidth(2), 0f); assertEquals(300f, layout.displayHeight(2), 0f)
            assertEquals(800f, layout.displayWidth(3), 0f)
        }
    }

    @Test fun rotatedPageCornersLandWhereTheyShould() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0, rotation = 270)))
        vp.scrollX = layout.left(0); vp.scrollY = layout.top(0)
        val s = vp.scale
        // 270 degrees clockwise: the page's top-left corner is displayed bottom-left.
        vp.pageToView(layout, 0, 0f, 0f, out)
        assertEquals(0f, out[0], 1f); assertEquals(600f * s, out[1], 1f)
        // The page's top-right corner is displayed top-left.
        vp.pageToView(layout, 0, 600f, 0f, out)
        assertEquals(0f, out[0], 1f); assertEquals(0f, out[1], 1f)
    }

    @Test fun infiniteCanvasLimitsAndZoomRange() {
        val (vp, layout) = setup(PageLayoutMode.INFINITE, listOf(page(0, w = 0f, h = 0f)))
        assertEquals(0, layout.rotation(0))
        assertEquals(1, layout.count)
        val range = FloatArray(4)
        vp.scrollRange(layout, range)
        assertEquals(-Viewport.INFINITE_LIMIT, range[0], 0f); assertEquals(Viewport.INFINITE_LIMIT, range[3], 0f)
        vp.zoomAround(0.001f, 0f, 0f)
        assertEquals(0.05f, vp.zoom, 0f)
        vp.zoomAround(1000f, 0f, 0f)
        assertEquals(8f, vp.zoom, 0f)
        vp.scrollX = -1e9f; vp.scrollY = 1e9f
        vp.clamp(layout)
        assertEquals(-Viewport.INFINITE_LIMIT, vp.scrollX, 0f); assertEquals(Viewport.INFINITE_LIMIT, vp.scrollY, 0f)
        // Far from the origin the round trip is still accurate to a fraction of a point.
        vp.zoomAround(0.3f, 100f, 100f)
        vp.scrollX = 1_500_000f; vp.scrollY = -1_500_000f
        for (p in listOf(1_500_100f to -1_499_900f, 1_501_234.5f to -1_498_765.25f)) {
            vp.pageToView(layout, 0, p.first, p.second, out)
            vp.viewToPage(layout, 0, out[0], out[1], out)
            assertEquals(p.first, out[0], 0.5f); assertEquals(p.second, out[1], 0.5f)
        }
    }

    @Test fun horizontalLayoutScrollsAlongXAndCentresShortPages() {
        val (vp, layout) = setup(PageLayoutMode.HORIZONTAL, listOf(page(0), page(1, h = 400f), page(2)))
        assertEquals(0f, layout.left(0), 0f)
        assertEquals(600f + layout.gap, layout.left(1), 1e-3f)
        assertEquals(200f, layout.top(1), 1e-3f)
        assertEquals(0f, layout.top(0), 0f)
        // Pages in a horizontal strip: querying a band over the gap between 1 and 2 hits both neighbours.
        val idx = IntArray(8)
        val n = layout.pagesIn(layout.right(1) - 1f, 0f, layout.left(2) + 1f, 800f, idx)
        assertEquals(2, n); assertEquals(1, idx[0]); assertEquals(2, idx[1])
        // Scroll for page 2 centres it; the current page follows.
        vp.scrollForPage(layout, 2, out)
        vp.scrollX = out[0]; vp.scrollY = out[1]
        vp.clamp(layout)
        assertEquals(2, vp.currentPage(layout))
        assertEquals(0, layout.nearestPage(-1000f, 400f))
        assertEquals(2, layout.nearestPage(1e6f, 400f))
    }

    @Test fun zoomKeepsTheFocalPointAcrossRepeatedPinchSteps() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0), page(1)))
        vp.scrollY = 500f
        val fx = 333f; val fy = 777f
        val dx = vp.viewToDocX(fx); val dy = vp.viewToDocY(fy)
        var z = 1f
        repeat(40) { z *= 1.04f; vp.zoomAround(z, fx, fy) }
        repeat(60) { z /= 1.03f; vp.zoomAround(z, fx, fy) }
        assertEquals(dx, vp.viewToDocX(fx), 1e-2f)
        assertEquals(dy, vp.viewToDocY(fy), 1e-2f)
        assertTrue(vp.zoom in vp.minZoom..vp.maxZoom)
    }

    @Test fun layoutModeChangeKeepsZoomWithinTheNewLimits() {
        val vp = Viewport(); vp.density = 2f; vp.setViewSize(1000f, 800f)
        val pages = listOf(page(0, w = 0f, h = 0f))
        val layout = PageLayout()
        layout.compute(pages, PageLayoutMode.INFINITE, 0f)
        vp.configure(PageLayoutMode.INFINITE, layout)
        vp.zoomAround(0.1f, 0f, 0f)
        assertEquals(0.1f, vp.zoom, 0f)
        layout.compute(pages, PageLayoutMode.VERTICAL, 0f)
        vp.configure(PageLayoutMode.VERTICAL, layout)
        assertEquals(0.25f, vp.zoom, 0f)
        // Pages with no size fall back to A4 for layout.
        assertTrue(layout.pageWidth(0) > 0f && layout.pageHeight(0) > 0f)
    }
}
