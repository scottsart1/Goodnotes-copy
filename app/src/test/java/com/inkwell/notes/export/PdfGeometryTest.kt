package com.inkwell.notes.export

import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.export.format.Affine
import com.inkwell.notes.export.format.PaperGeometry
import com.inkwell.notes.export.format.PathOp
import com.inkwell.notes.export.format.PdfGeometry
import com.inkwell.notes.export.format.PolygonSplitter
import com.inkwell.notes.export.format.RasterSizing
import com.inkwell.notes.export.format.ShapeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfGeometryTest {
    private fun assertMaps(t: Affine, x: Float, y: Float, ex: Float, ey: Float) {
        assertEquals("x of ($x,$y)", ex, t.mapX(x, y), 1e-3f)
        assertEquals("y of ($x,$y)", ey, t.mapY(x, y), 1e-3f)
    }

    @Test fun newPageFlipsYAroundTheHeight() {
        val t = PdfGeometry.pageToUser(595f, 842f)
        assertMaps(t, 0f, 0f, 0f, 842f)       // top-left -> top of user space
        assertMaps(t, 595f, 842f, 595f, 0f)   // bottom-right -> origin row
        assertMaps(t, 100f, 42f, 100f, 800f)
        assertTrue(PdfGeometry.isMirrored(t))
    }

    @Test fun unrotatedBoxWithOffsetOrigin() {
        // Crop box from (10, 20) to (610, 820): a 600 x 800 page.
        val t = PdfGeometry.displayToUser(10f, 20f, 610f, 820f, 0, 600f, 800f)
        assertMaps(t, 0f, 0f, 10f, 820f)
        assertMaps(t, 600f, 800f, 610f, 20f)
    }

    @Test fun rotated90PageMapsDisplayTopLeftToBoxOrigin() {
        // Box 600 wide x 800 tall, /Rotate 90: the viewer shows it 800 wide x 600 tall.
        val t = PdfGeometry.displayToUser(0f, 0f, 600f, 800f, 90, 800f, 600f)
        assertMaps(t, 0f, 0f, 0f, 0f)        // display top-left = user bottom-left
        assertMaps(t, 800f, 0f, 0f, 800f)    // display top-right = user top-left
        assertMaps(t, 0f, 600f, 600f, 0f)    // display bottom-left = user bottom-right
        assertMaps(t, 800f, 600f, 600f, 800f)
    }

    @Test fun rotated180And270() {
        val t180 = PdfGeometry.displayToUser(0f, 0f, 600f, 800f, 180, 600f, 800f)
        assertMaps(t180, 0f, 0f, 600f, 0f)
        assertMaps(t180, 600f, 800f, 0f, 800f)
        val t270 = PdfGeometry.displayToUser(0f, 0f, 600f, 800f, 270, 800f, 600f)
        assertMaps(t270, 0f, 0f, 600f, 800f) // display top-left = user top-right
        assertMaps(t270, 0f, 600f, 0f, 800f) // display bottom-left = user top-left
        assertMaps(t270, 800f, 600f, 0f, 0f)
        // Every display mapping mirrors (y down to y up), so glyphs need a counter-flip.
        listOf(0, 90, 180, 270).forEach {
            assertTrue(PdfGeometry.isMirrored(PdfGeometry.displayToUser(0f, 0f, 600f, 800f, it, 600f, 800f)))
        }
    }

    @Test fun differingPageSizeIsScaledToTheBox() {
        // Inkwell thinks the page is 300 x 400; the PDF box is 600 x 800.
        val t = PdfGeometry.displayToUser(0f, 0f, 600f, 800f, 0, 300f, 400f)
        assertMaps(t, 300f, 400f, 600f, 0f)
        assertMaps(t, 150f, 0f, 300f, 800f)
    }

    @Test fun rotationIsNormalised() {
        assertEquals(90, PdfGeometry.normalizeRotation(-270))
        assertEquals(0, PdfGeometry.normalizeRotation(360))
        assertEquals(180, PdfGeometry.normalizeRotation(540))
        assertEquals(270, PdfGeometry.combinedRotation(180, 90))
        assertEquals(0, PdfGeometry.combinedRotation(270, 90))
    }

    @Test fun boardPageFitsContentAndCapsSize() {
        val (w, h, t) = PdfGeometry.boardPage(-100f, 50f, 300f, 250f, 10f)
        assertEquals(420f, w, 1e-3f)
        assertEquals(220f, h, 1e-3f)
        assertMaps(t, -100f, 50f, 10f, 210f) // content top-left sits at the margin
        val (bw, bh, bt) = PdfGeometry.boardPage(0f, 0f, 30_000f, 1_000f, 0f)
        assertEquals(PdfGeometry.MAX_PAGE_SIDE, bw, 1e-2f)
        assertEquals(480f, bh, 1e-2f)
        assertMaps(bt, 30_000f, 1_000f, PdfGeometry.MAX_PAGE_SIDE, 0f)
    }

    @Test fun affineCompositionAppliesInnerFirst() {
        val t = Affine.translate(10f, 0f).after(Affine.scale(2f))
        assertMaps(t, 1f, 1f, 12f, 2f)
        val r = Affine.rotation(90f, 0f, 0f) // clockwise on a y-down page
        assertMaps(r, 1f, 0f, 0f, 1f)
        val rc = Affine.rotation(180f, 5f, 5f)
        assertMaps(rc, 0f, 0f, 10f, 10f)
        val rect = PdfGeometry.pageToUser(100f, 100f).mapRect(10f, 20f, 30f, 60f)
        assertEquals(listOf(10f, 40f, 30f, 80f), rect.toList())
    }

    @Test fun shapesUseBezierEllipsesAndRotation() {
        val ellipse = ShapeItem("e", ShapeKind.ELLIPSE, floatArrayOf(0f, 0f, 20f, 10f), 0xFF000000.toInt())
        val ops = ShapeGeometry.path(ellipse)
        assertEquals(PathOp.MoveTo(20f, 5f), ops.first())
        assertEquals(4, ops.count { it is PathOp.CubicTo })
        assertEquals(PathOp.Close, ops.last())

        val rect = ShapeItem("r", ShapeKind.RECTANGLE, floatArrayOf(10f, 10f, 0f, 0f), 0, rotation = 90f)
        val first = ShapeGeometry.path(rect).first() as PathOp.MoveTo
        // (0,0) rotated 90 degrees clockwise around (5,5) lands on (10,0).
        assertEquals(10f, first.x, 1e-4f)
        assertEquals(0f, first.y, 1e-4f)

        val rounded = ShapeGeometry.roundRect(0f, 0f, 10f, 4f, 50f)
        assertEquals(PathOp.MoveTo(2f, 0f), rounded.first()) // radius clamped to half the height
        assertTrue(ShapeGeometry.path(ShapeItem("l", ShapeKind.LINE, floatArrayOf(1f, 2f), 0)).isEmpty())
    }

    @Test fun paperPlansMatchTheRenderer() {
        val ruled = PaperGeometry.plan(PaperSpec(PaperStyle.RULED), 595f, 842f)
        // Lines every 24pt from 72pt while above height - 12, plus the margin line.
        val expected = generateSequence(72f) { it + 24f }.takeWhile { it < 842f - 12f }.count()
        assertEquals(expected + 1, ruled.lines.size)
        assertEquals(PaperGeometry.MARGIN_COLOR, ruled.lines.last().color)

        val graph = PaperGeometry.plan(PaperSpec(PaperStyle.GRAPH), 100f, 50f)
        assertEquals(0.9f, graph.lines.first().width)
        assertEquals(0.45f, graph.lines[1].width)

        val dots = PaperGeometry.plan(PaperSpec(PaperStyle.DOT_GRID), 100f, 50f)
        assertEquals(4 * 2 * 2, dots.dots.size) // x = 20..80, y = 20..40
        assertTrue(PaperGeometry.plan(PaperSpec(PaperStyle.BLANK), 100f, 100f).lines.isEmpty())
    }

    @Test fun approximationsSplitIntoContours() {
        val approx = floatArrayOf(
            0f, 0f, 0f, 0.25f, 10f, 0f, 0.25f, 10f, 0f /* zero-length */, 0.5f, 10f, 10f,
            0.5f, 50f, 50f /* move */, 0.75f, 60f, 50f, 1f, 60f, 60f,
        )
        val polys = PolygonSplitter.split(approx)
        assertEquals(2, polys.size)
        assertEquals(listOf(0f, 0f, 10f, 0f, 10f, 10f), polys[0].toList())
        assertEquals(listOf(50f, 50f, 60f, 50f, 60f, 60f), polys[1].toList())
    }

    @Test fun rasterSizesStayBounded() {
        assertEquals(1653, RasterSizing.widthForDpi(595f, 200))
        val a4 = RasterSizing.fit(595f, 842f, 1653)
        assertEquals(1653, a4.width)
        assertEquals(2339, a4.height)
        val tall = RasterSizing.fit(100f, 100_000f, 2000)
        assertTrue(tall.height <= RasterSizing.MAX_SIDE)
        val huge = RasterSizing.fit(1000f, 1000f, 20_000)
        assertTrue(huge.width.toLong() * huge.height <= RasterSizing.MAX_PIXELS)
        assertEquals(4, RasterSizing.sampleSize(4000, 3000, 900, 600))
        assertEquals(1, RasterSizing.sampleSize(100, 100, 400, 400))
    }
}
