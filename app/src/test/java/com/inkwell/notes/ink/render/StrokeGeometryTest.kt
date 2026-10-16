package com.inkwell.notes.ink.render

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.ink.TestInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGeometryTest {
    private val g = StrokeGeometry()

    private fun withPressure(points: FloatArray, pressure: (Int) -> Float): FloatArray {
        val p = points.copyOf()
        for (i in 0 until p.size / InkPoints.STRIDE) p[i * InkPoints.STRIDE + InkPoints.PRESSURE] = pressure(i)
        return p
    }

    @Test fun ballpointIsNearlyConstant() {
        val s = TestInk.line("s", 0f, 0f, 100f, 0f, samples = 30)
        val pts = withPressure(s.points) { it / 29f }
        g.compute(pts, 30, null, 0, PenType.BALLPOINT, 2f, 0.3f)
        val min = (0 until g.count).minOf { g.rs[it] }
        val max = (0 until g.count).maxOf { g.rs[it] }
        assertTrue("ballpoint variation ${max / min}", max / min < 1.2f)
    }

    @Test fun brushRespondsStronglyToPressure() {
        val s = TestInk.line("s", 0f, 0f, 200f, 0f, samples = 40)
        val pts = withPressure(s.points) { if (it < 20) 0.1f else 1f }
        g.compute(pts, 40, null, 0, PenType.BRUSH, 3f, 0.8f)
        assertTrue(g.rs[35] > g.rs[10] * 3f)
    }

    @Test fun fountainTapersAtTheStart() {
        val s = TestInk.line("s", 0f, 0f, 100f, 0f, samples = 50)
        g.compute(s.points, 50, null, 0, PenType.FOUNTAIN, 2f, 0.6f)
        assertTrue(g.rs[0] < g.rs[25] * 0.7f)
    }

    @Test fun highlighterHasConstantWidthAndFlatCaps() {
        val s = TestInk.line("s", 0f, 0f, 100f, 50f, samples = 20)
        g.compute(withPressure(s.points) { it / 19f }, 20, null, 0, PenType.HIGHLIGHTER, 14f, 0f)
        assertTrue(g.flatCaps)
        for (i in 0 until g.count) assertEquals(7f, g.rs[i], 1e-4f)
    }

    @Test fun sharpCornersSplitTheOutlineIntoRuns() {
        val v = TestInk.stroke("v", 0f, 0f, 10f, 20f, 20f, 40f, 30f, 20f, 40f, 0f)
        g.compute(v.points, v.pointCount, null, 0, PenType.BALLPOINT, 2f, 0.3f)
        assertEquals(2, g.runCount)
        assertEquals(2, g.runEnd[0])
        assertEquals(2, g.runStart[1])
        // Straight strokes stay one run.
        val l = TestInk.line("l", 0f, 0f, 100f, 0f)
        g.compute(l.points, l.pointCount, null, 0, PenType.BALLPOINT, 2f, 0.3f)
        assertEquals(1, g.runCount)
    }

    @Test fun offsetsLieAtTheRadius() {
        val l = TestInk.line("l", 0f, 0f, 100f, 0f, samples = 11)
        g.compute(l.points, l.pointCount, null, 0, PenType.BALLPOINT, 2f, 0.3f)
        for (i in 0 until g.count) {
            val s = g.slot(0, i)
            assertEquals(g.rs[i], g.ly[s] - g.ys[i], 1e-4f)
            assertEquals(-g.rs[i], g.ry[s] - g.ys[i], 1e-4f)
        }
    }

    @Test fun tinyStrokesBecomeDots() {
        val dot = TestInk.stroke("d", 5f, 5f, 5.1f, 5f)
        g.compute(dot.points, dot.pointCount, null, 0, PenType.FOUNTAIN, 2f, 0.6f)
        assertTrue(g.isDot)
        assertTrue(g.dotRadius > 0f)
    }

    @Test fun predictedSamplesExtendTheOutlineWithoutChangingCommittedOnes() {
        val l = TestInk.line("l", 0f, 0f, 50f, 0f, samples = 11)
        g.compute(l.points, 11, null, 0, PenType.BALLPOINT, 2f, 0.3f)
        val committedCount = g.count
        val r3 = g.rs[3]
        val extra = TestInk.line("p", 55f, 0f, 70f, 0f, samples = 4).points
        g.compute(l.points, 11, extra, 4, PenType.BALLPOINT, 2f, 0.3f)
        assertEquals(committedCount + 4, g.count)
        assertEquals(r3, g.rs[3], 1e-6f)
        assertEquals(70f, g.xs[g.count - 1], 0f)
    }

    @Test fun pencilWidensWithTilt() {
        val upright = PenModel.widthFactor(PenType.PENCIL, 0.5f, 0.7f, tilt = 0f)
        val tilted = PenModel.widthFactor(PenType.PENCIL, 0.5f, 0.7f, tilt = 1.1f)
        assertTrue(tilted > upright * 2f)
        assertTrue(tilted <= PenModel.maxFactor(PenType.PENCIL))
    }

    @Test fun widthFactorsStayWithinMaxima() {
        for (pen in PenType.entries) for (p in listOf(0f, 0.5f, 1f)) for (sens in listOf(0f, 0.5f, 1f)) {
            val f = PenModel.widthFactor(pen, p, sens, speed = 0.1f, tilt = 1.2f)
            assertTrue("$pen $p $sens $f", f <= PenModel.maxFactor(pen) + 1e-4f && f > 0f)
        }
    }
}
