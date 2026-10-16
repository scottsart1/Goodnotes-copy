package com.inkwell.notes.ink.render

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Degenerate strokes through the outline geometry: NaN samples, single points, duplicates, extremes. */
class OutlineEdgeCasesTest {
    private fun pts(vararg xy: Float, pressure: Float = 0.5f): FloatArray {
        val n = xy.size / 2
        val p = FloatArray(n * InkPoints.STRIDE)
        for (i in 0 until n) {
            p[i * InkPoints.STRIDE] = xy[i * 2]; p[i * InkPoints.STRIDE + 1] = xy[i * 2 + 1]
            p[i * InkPoints.STRIDE + 2] = pressure; p[i * InkPoints.STRIDE + 4] = i * 10f
        }
        return p
    }

    private fun compute(points: FloatArray, pen: PenType = PenType.BALLPOINT, width: Float = 2f): StrokeGeometry =
        StrokeGeometry().also { it.compute(points, points.size / InkPoints.STRIDE, null, 0, pen, width, 0.6f) }

    private fun assertFiniteOutline(g: StrokeGeometry) {
        if (g.isDot) { assertTrue(g.dotRadius.isFinite() && g.dotRadius > 0f); return }
        for (k in 0 until g.runCount) {
            for (i in g.runStart[k]..g.runEnd[k]) {
                val s = g.slot(k, i)
                assertTrue("sample $i of run $k has a non-finite offset", g.lx[s].isFinite() && g.ly[s].isFinite() && g.rx[s].isFinite() && g.ry[s].isFinite())
                assertTrue(g.rs[i].isFinite() && g.rs[i] >= PenModel.MIN_RADIUS)
            }
        }
    }

    @Test fun emptyStrokeProducesNoGeometry() {
        val g = compute(FloatArray(0))
        assertEquals(0, g.count)
        assertEquals(0, g.runCount)
        assertFalse(g.isDot)
    }

    @Test fun singlePointIsADotWithTheNominalRadius() {
        val g = compute(pts(10f, 10f))
        assertTrue(g.isDot)
        assertTrue(g.dotRadius > 0f && g.dotRadius.isFinite())
        assertEquals(1, g.count)
    }

    @Test fun duplicatePointsCollapseToADot() {
        val g = compute(pts(10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f))
        assertTrue(g.isDot)
        assertEquals(1, g.count)
        // Nearly duplicate points keep only one sample and the newest pressure.
        val g2 = compute(pts(10f, 10f, 10.001f, 10f, 10.002f, 10f, 10.003f, 10f))
        assertTrue(g2.isDot)
    }

    @Test fun nonFiniteSamplesAreDroppedNotPropagated() {
        val p = pts(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f, 40f, 0f)
        p[2 * InkPoints.STRIDE] = Float.NaN
        p[3 * InkPoints.STRIDE + 1] = Float.POSITIVE_INFINITY
        val g = compute(p)
        assertFiniteOutline(g)
        assertEquals(3, g.count)
        // A stroke of nothing but NaN draws nothing at all instead of a NaN path.
        val bad = pts(Float.NaN, Float.NaN, Float.NaN, 1f)
        val g2 = compute(bad)
        assertEquals(0, g2.count)
        assertFalse(g2.isDot)
    }

    @Test fun nonFinitePressureFallsBackToAFiniteRadius() {
        val p = pts(0f, 0f, 10f, 0f, 20f, 0f)
        p[InkPoints.STRIDE + InkPoints.PRESSURE] = Float.NaN
        val g = compute(p, PenType.BRUSH, 4f)
        assertFiniteOutline(g)
    }

    @Test fun backAndForthOnOneLineKeepsFiniteOffsets() {
        // The tangent at the turning point is zero: the fallback direction must apply.
        val g = compute(pts(0f, 0f, 10f, 0f, 20f, 0f, 10f, 0f, 0f, 0f))
        assertFiniteOutline(g)
        assertTrue(g.runCount >= 1)
    }

    @Test fun extremeWidthsStayFiniteAndBounded() {
        val wide = compute(pts(0f, 0f, 500f, 0f), PenType.BRUSH, 200f)
        assertFiniteOutline(wide)
        val hair = compute(pts(0f, 0f, 50f, 0f), PenType.FOUNTAIN, 0.1f)
        assertFiniteOutline(hair)
        assertTrue(hair.maxRadius() >= PenModel.MIN_RADIUS)
    }

    @Test fun strokeBoundsContainTheOutlineForEveryPen() {
        for (pen in PenType.entries) {
            val p = pts(0f, 0f, 20f, 5f, 40f, 0f, 60f, 5f, pressure = 1f)
            for (i in 0 until 4) p[i * InkPoints.STRIDE + InkPoints.TILT] = 1.5f
            val s = Stroke("s", pen, 0xFF000000.toInt(), 6f, p, 0L, pressureSensitivity = 1f)
            val g = compute(p, pen, 6f)
            val b = ItemGeometry.strokeBounds(s, Bounds())
            val outline = Bounds()
            for (k in 0 until g.runCount) for (i in g.runStart[k]..g.runEnd[k]) {
                val sl = g.slot(k, i)
                outline.union(g.lx[sl], g.ly[sl]); outline.union(g.rx[sl], g.ry[sl])
                outline.union(g.xs[i] - g.rs[i], g.ys[i] - g.rs[i]); outline.union(g.xs[i] + g.rs[i], g.ys[i] + g.rs[i])
            }
            assertTrue("$pen outline $outline escapes bounds $b", b.contains(outline))
        }
    }

    @Test fun contentBoundsIgnoreItemsWithoutGeometry() {
        val empty = Stroke("e", PenType.BALLPOINT, 0, 2f, FloatArray(0), 0L)
        assertNull(PageRenderer.contentBoundsOf(PageContent(strokes = listOf(empty))))
        val one = TestInk.stroke("dot", 10f, 10f, width = 4f)
        val b = PageRenderer.contentBoundsOf(PageContent(strokes = listOf(empty, one)))
        assertNotNull(b)
        assertTrue(b!!.contains(10f, 10f))
        assertTrue(b.width > 4f && b.width < 20f)
    }
}
