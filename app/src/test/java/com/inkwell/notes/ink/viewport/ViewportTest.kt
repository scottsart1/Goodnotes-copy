package com.inkwell.notes.ink.viewport

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ink.PageLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private fun page(i: Int, w: Float = 600f, h: Float = 800f, rotation: Int = 0) =
        PageMeta(id = "p$i", documentId = "d", index = i, widthPt = w, heightPt = h, rotation = rotation)

    private fun setup(mode: PageLayoutMode, pages: List<PageMeta>, w: Float = 1000f, h: Float = 800f, density: Float = 2f): Pair<Viewport, PageLayout> {
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

    private fun assertRoundTrip(vp: Viewport, layout: PageLayout, i: Int, px: Float, py: Float) {
        vp.pageToView(layout, i, px, py, out)
        val vx = out[0]; val vy = out[1]
        vp.viewToPage(layout, i, vx, vy, out)
        assertEquals(px, out[0], 1e-2f)
        assertEquals(py, out[1], 1e-2f)
    }

    @Test fun verticalFitWidthAndStacking() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0), page(1), page(2, w = 400f)))
        // Fit width: (1000 - 2*24dp*2) / 600
        assertEquals((1000f - 96f) / 600f, vp.baseScale, 1e-4f)
        val gap = 48f / vp.baseScale
        assertEquals(gap, layout.gap, 1e-4f)
        assertEquals(0f, layout.top(0), 0f)
        assertEquals(800f + gap, layout.top(1), 1e-3f)
        // The narrower page is centred.
        assertEquals(100f, layout.left(2), 1e-3f)
        assertEquals(layout.left(2) + 400f, layout.right(2), 1e-3f)
    }

    @Test fun verticalTransformsAtSeveralZoomsAndScrolls() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0), page(1)))
        for (zoom in listOf(0.25f, 1f, 2.5f, 8f)) for (sy in listOf(-30f, 0f, 400f, 1234.5f)) {
            vp.zoomAround(zoom, 0f, 0f)
            vp.scrollX = -10f; vp.scrollY = sy
            assertRoundTrip(vp, layout, 1, 123f, 456f)
            vp.pageToView(layout, 1, 0f, 0f, out)
            // Page origin is the document position minus scroll, scaled, snapped to a pixel.
            assertEquals(Math.round((layout.top(1) - sy) * vp.scale).toFloat(), out[1], 0f)
            assertEquals(Math.round((layout.left(1) + 10f) * vp.scale).toFloat(), out[0], 0f)
        }
    }

    @Test fun horizontalLayoutPlacesPagesSideBySide() {
        val (vp, layout) = setup(PageLayoutMode.HORIZONTAL, listOf(page(0), page(1, h = 600f)))
        // Fit page: min(fit width, fit height).
        val fw = (1000f - 96f) / 600f; val fh = (800f - 96f) / 800f
        assertEquals(minOf(fw, fh), vp.baseScale, 1e-4f)
        assertEquals(0f, layout.left(0), 0f)
        assertEquals(600f + layout.gap, layout.left(1), 1e-3f)
        assertEquals(100f, layout.top(1), 1e-3f) // centred vertically against the 800pt page
        vp.zoomAround(1.7f, 300f, 300f)
        vp.scrollX = 250f; vp.scrollY = 40f
        assertRoundTrip(vp, layout, 1, 10f, 590f)
        vp.pageToView(layout, 1, 0f, 0f, out)
        assertEquals(Math.round((layout.left(1) - 250f) * vp.scale).toFloat(), out[0], 0f)
    }

    @Test fun infiniteCanvasUsesDocumentCoordinates() {
        val (vp, layout) = setup(PageLayoutMode.INFINITE, listOf(page(0, w = 0f, h = 0f)))
        assertEquals(2f, vp.baseScale, 0f) // 1 pt per dp
        assertEquals(0.05f, vp.minZoom, 0f)
        for (zoom in listOf(0.05f, 0.5f, 1f, 8f)) {
            vp.zoomAround(zoom, 500f, 400f)
            vp.scrollX = -20000f; vp.scrollY = 35000f
            assertRoundTrip(vp, layout, 0, -19000f, 35100f)
            vp.viewToPage(layout, 0, 0f, 0f, out)
            assertEquals(-20000f, out[0], 1f / vp.scale + 1e-2f)
            assertEquals(35000f, out[1], 1f / vp.scale + 1e-2f)
        }
    }

    @Test fun rotatedPagesSwapDisplayedSize() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0, rotation = 90)))
        assertEquals(800f, layout.displayWidth(0), 0f)
        assertEquals(600f, layout.displayHeight(0), 0f)
        for (rot in listOf(0, 90, 180, 270)) {
            val (v2, l2) = setup(PageLayoutMode.VERTICAL, listOf(page(0, rotation = rot)))
            v2.zoomAround(1.3f, 0f, 0f)
            assertRoundTrip(v2, l2, 0, 50f, 700f)
        }
        // Page top-left of a 90 degree page ends up at the displayed top-right.
        vp.scrollX = 0f; vp.scrollY = 0f
        vp.pageToView(layout, 0, 0f, 0f, out)
        val originX = vp.pageOriginX(layout, 0)
        assertEquals(originX + 800f * vp.scale, out[0], 1e-2f)
    }

    @Test fun rotationHelpersInvert() {
        val o = FloatArray(2)
        for (rot in listOf(0, 90, 180, 270)) {
            PageRotation.toDisplay(30f, 70f, 100f, 200f, rot, o)
            PageRotation.fromDisplay(o[0], o[1], 100f, 200f, rot, o)
            assertEquals(30f, o[0], 1e-4f); assertEquals(70f, o[1], 1e-4f)
            // The affine form agrees with toDisplay.
            val a = FloatArray(6)
            PageRotation.affine(100f, 200f, rot, a)
            PageRotation.toDisplay(30f, 70f, 100f, 200f, rot, o)
            assertEquals(o[0], a[0] * 30f + a[2] * 70f + a[4], 1e-4f)
            assertEquals(o[1], a[1] * 30f + a[3] * 70f + a[5], 1e-4f)
        }
        assertEquals(90, PageRotation.normalize(-270))
        assertEquals(0, PageRotation.normalize(360))
    }

    @Test fun zoomAroundKeepsFocalPointFixed() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0), page(1)))
        vp.scrollX = 12f; vp.scrollY = 300f
        val fx = 640f; val fy = 210f
        val docX = vp.viewToDocX(fx); val docY = vp.viewToDocY(fy)
        vp.zoomAround(3.3f, fx, fy)
        assertEquals(docX, vp.viewToDocX(fx), 1e-3f)
        assertEquals(docY, vp.viewToDocY(fy), 1e-3f)
        // Limits.
        vp.zoomAround(100f, fx, fy)
        assertEquals(8f, vp.zoom, 0f)
        vp.zoomAround(0.01f, fx, fy)
        assertEquals(0.25f, vp.zoom, 0f)
        layout.count
    }

    @Test fun clampCentresSmallContentAndBoundsLargeContent() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0), page(1), page(2)))
        // At zoom 1 the width fits exactly with margins: horizontally centred.
        vp.scrollX = 999f; vp.scrollY = -999f
        vp.clamp(layout)
        val visW = vp.viewWidth / vp.scale
        assertEquals(layout.bounds.left - (visW - layout.bounds.width) / 2f, vp.scrollX, 1e-3f)
        assertEquals(layout.bounds.top, vp.scrollY, 1e-3f)
        vp.scrollY = 1e9f
        vp.clamp(layout)
        assertEquals(layout.bounds.bottom - vp.viewHeight / vp.scale, vp.scrollY, 1e-2f)
        // Zoomed in: horizontal scrolling is bounded by the content.
        vp.zoomAround(4f, 0f, 0f)
        vp.scrollX = -1e6f
        vp.clamp(layout)
        assertEquals(layout.bounds.left, vp.scrollX, 1e-3f)
    }

    @Test fun currentPageAndScrollForPage() {
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, listOf(page(0), page(1), page(2)))
        val target = FloatArray(2)
        vp.scrollForPage(layout, 2, target)
        vp.scrollX = target[0]; vp.scrollY = target[1]
        assertEquals(2, vp.currentPage(layout))
        assertEquals(1, layout.nearestPage(300f, layout.top(1) + 10f))
        val idx = IntArray(4)
        val n = layout.pagesIn(0f, layout.top(1) - 5f, 600f, layout.top(1) + 5f, idx)
        assertTrue(n >= 1)
        assertEquals(1, idx[n - 1])
    }
}
