package com.inkwell.notes.ink.viewport

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.ink.PageLayoutMode
import com.inkwell.notes.ink.select.Affine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The affine, point and inverse conversions of the viewport must agree for every rotation and layout. */
class PageTransformConsistencyTest {
    private fun page(id: String, w: Float, h: Float, rotation: Int = 0) =
        PageMeta(id = id, documentId = "d", index = 0, widthPt = w, heightPt = h, paper = PaperSpec(), rotation = rotation)

    private fun setup(mode: PageLayoutMode, pages: List<PageMeta>, zoom: Float = 1.7f, scroll: Pair<Float, Float> = 123.4f to -56.7f): Pair<Viewport, PageLayout> {
        val vp = Viewport().also { it.density = 2.5f; it.setViewSize(1600f, 2400f) }
        val layout = PageLayout()
        layout.compute(pages, mode, 0f)
        val gap = vp.configure(mode, layout)
        layout.compute(pages, mode, gap)
        vp.setZoomUnanchored(zoom)
        vp.scrollX = scroll.first; vp.scrollY = scroll.second
        return vp to layout
    }

    @Test fun affineMatchesPointConversionForEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270, -90, 450)) {
            val pages = listOf(page("a", 612f, 792f, rotation), page("b", 400f, 300f, (rotation + 90) % 360))
            for (mode in listOf(PageLayoutMode.VERTICAL, PageLayoutMode.HORIZONTAL)) {
                val (vp, layout) = setup(mode, pages)
                val out = FloatArray(2)
                val aff = FloatArray(6)
                for (i in 0 until 2) {
                    vp.pageToViewAffine(layout, i, aff)
                    val m = Affine.of(aff)
                    val inv = m.inverse()!!
                    for ((px, py) in listOf(0f to 0f, 612f to 0f, 0f to 792f, 100.5f to 200.25f, -20f to 900f)) {
                        vp.pageToView(layout, i, px, py, out)
                        assertEquals("rot $rotation mode $mode page $i x", out[0], m.mapX(px, py), 1e-2f)
                        assertEquals("rot $rotation mode $mode page $i y", out[1], m.mapY(px, py), 1e-2f)
                        val back = FloatArray(2)
                        vp.viewToPage(layout, i, out[0], out[1], back)
                        assertEquals(px, back[0], 1e-2f); assertEquals(py, back[1], 1e-2f)
                        assertEquals(px, inv.mapX(out[0], out[1]), 1e-2f); assertEquals(py, inv.mapY(out[0], out[1]), 1e-2f)
                    }
                    // Rotation never scales: the affine's determinant is scale^2.
                    assertEquals(vp.scale * vp.scale, kotlin.math.abs(m.det), 1e-2f * vp.scale * vp.scale)
                }
            }
        }
    }

    @Test fun rotatedPageMapsIntoItsDisplayedRectangle() {
        val pages = listOf(page("a", 612f, 792f, 90))
        val (vp, layout) = setup(PageLayoutMode.VERTICAL, pages, zoom = 1f, scroll = 0f to 0f)
        assertEquals(792f, layout.displayWidth(0), 0f); assertEquals(612f, layout.displayHeight(0), 0f)
        val out = FloatArray(2)
        val ox = vp.pageOriginX(layout, 0); val oy = vp.pageOriginY(layout, 0)
        val corners = listOf(0f to 0f, 612f to 0f, 612f to 792f, 0f to 792f)
        for ((px, py) in corners) {
            vp.pageToView(layout, 0, px, py, out)
            assertTrue(out[0] >= ox - 0.01f && out[0] <= ox + 792f * vp.scale + 0.01f)
            assertTrue(out[1] >= oy - 0.01f && out[1] <= oy + 612f * vp.scale + 0.01f)
        }
        // Clockwise: the page's top-left corner lands at the displayed top-right.
        vp.pageToView(layout, 0, 0f, 0f, out)
        assertEquals(ox + 792f * vp.scale, out[0], 0.01f); assertEquals(oy, out[1], 0.01f)
    }

    @Test fun docAndPageConversionsInvertAndNearestPageFindsPages() {
        val pages = listOf(page("a", 612f, 792f, 0), page("b", 612f, 792f, 270), page("c", 300f, 300f))
        for (mode in listOf(PageLayoutMode.VERTICAL, PageLayoutMode.HORIZONTAL)) {
            val (_, layout) = setup(mode, pages)
            val d = FloatArray(2); val p = FloatArray(2)
            for (i in 0 until 3) {
                layout.pageToDoc(i, 10f, 20f, d)
                assertEquals(i, layout.nearestPage(d[0], d[1]))
                layout.docToPage(i, d[0], d[1], p)
                assertEquals(10f, p[0], 1e-3f); assertEquals(20f, p[1], 1e-3f)
                // Page centre lies inside the layout rectangle of that page.
                layout.pageToDoc(i, layout.pageWidth(i) / 2f, layout.pageHeight(i) / 2f, d)
                assertTrue(d[0] > layout.left(i) && d[0] < layout.right(i) && d[1] > layout.top(i) && d[1] < layout.bottom(i))
            }
            // Pages never overlap and the gap between them is the configured gap.
            for (i in 0 until 2) {
                if (mode == PageLayoutMode.VERTICAL) assertEquals(layout.gap, layout.top(i + 1) - layout.bottom(i), 1e-3f)
                else assertEquals(layout.gap, layout.left(i + 1) - layout.right(i), 1e-3f)
            }
            val out = IntArray(8)
            assertEquals(3, layout.pagesIn(layout.bounds.left, layout.bounds.top, layout.bounds.right, layout.bounds.bottom, out))
            // A point in the gap between pages picks the nearest.
            if (mode == PageLayoutMode.VERTICAL) assertEquals(0, layout.nearestPage(300f, layout.bottom(0) + layout.gap * 0.25f))
        }
    }

    @Test fun infiniteLayoutIsIdentityAndIgnoresRotation() {
        val (vp, layout) = setup(PageLayoutMode.INFINITE, listOf(page("a", 0f, 0f, 90), page("b", 10f, 10f)), zoom = 2f, scroll = -500f to -250f)
        assertEquals(1, layout.count)
        assertEquals(0, layout.rotation(0))
        val out = FloatArray(2)
        vp.pageToView(layout, 0, 100f, 200f, out)
        assertEquals((100f + 500f) * vp.scale, out[0], 1e-2f)
        assertEquals((200f + 250f) * vp.scale, out[1], 1e-2f)
        assertEquals(2f * 2.5f, vp.scale, 1e-6f)
        val aff = FloatArray(6)
        vp.pageToViewAffine(layout, 0, aff)
        assertEquals(out[0], Affine.of(aff).mapX(100f, 200f), 1e-2f)
    }

    @Test fun pagesWithoutASizeFallBackToA4() {
        val (_, layout) = setup(PageLayoutMode.VERTICAL, listOf(page("a", 0f, 0f), page("b", -5f, 100f)))
        assertTrue(layout.pageWidth(0) > 500f && layout.pageHeight(0) > 700f)
        assertTrue(layout.pageWidth(1) > 500f); assertEquals(100f, layout.pageHeight(1), 0f)
    }
}
