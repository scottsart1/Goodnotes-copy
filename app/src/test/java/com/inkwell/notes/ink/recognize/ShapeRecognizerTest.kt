package com.inkwell.notes.ink.recognize

import com.inkwell.notes.core.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ShapeRecognizerTest {
    private val minSize = 20f

    private fun recognize(xy: FloatArray, closedOnly: Boolean = false) = ShapeRecognizer.recognize(xy, minSize, closedOnly)

    @Test fun noisyLineBecomesAStraightLine() {
        for (seed in 1..8) {
            val xy = SyntheticInk(seed).polyline(10f, 20f, 210f, 70f)
            val r = recognize(xy)
            assertNotNull("seed $seed", r)
            assertEquals(ShapeKind.LINE, r!!.kind)
            // Endpoints near the drawn ones, direction preserved.
            assertEquals(10f, r.points[0], 4f); assertEquals(20f, r.points[1], 4f)
            assertEquals(210f, r.points[2], 4f); assertEquals(70f, r.points[3], 4f)
        }
    }

    @Test fun lineWithASmallHookAtTheEndIsStillALine() {
        val xy = SyntheticInk(3).polyline(0f, 0f, 200f, 0f, 204f, 6f)
        assertEquals(ShapeKind.LINE, recognize(xy)?.kind)
    }

    @Test fun singleStrokeArrowWithHeadAtTheEnd() {
        for (seed in 1..6) {
            // Shaft to (200, 0), barb out and back, second barb.
            val xy = SyntheticInk(seed).polyline(0f, 0f, 200f, 0f, 170f, -24f, 200f, 0f, 170f, 24f)
            val r = recognize(xy)
            assertNotNull("seed $seed", r)
            assertEquals("seed $seed", ShapeKind.ARROW, r!!.kind)
            assertEquals(0f, r.points[0], 5f)
            assertEquals(200f, r.points[2], 6f)
        }
    }

    @Test fun arrowDrawnHeadFirstPointsTheRightWay() {
        // Barb, tip, barb, back to the tip, then the shaft away from it.
        val xy = SyntheticInk(5).polyline(170f, -24f, 200f, 0f, 170f, 24f, 200f, 0f, 0f, 0f)
        val r = recognize(xy)
        assertEquals(ShapeKind.ARROW, r?.kind)
        // Tip at (200, 0).
        assertEquals(200f, r!!.points[2], 6f)
        assertEquals(0f, r.points[0], 6f)
    }

    @Test fun rectangleWithOvershootIsAxisAligned() {
        for (seed in 1..8) {
            val xy = SyntheticInk(seed).polyline(20f, 20f, 220f, 22f, 218f, 140f, 18f, 138f, 20f, 20f, 60f, 21f)
            val r = recognize(xy)
            assertNotNull("seed $seed", r)
            assertEquals("seed $seed", ShapeKind.RECTANGLE, r!!.kind)
            assertEquals(0f, r.rotation, 0f)
            val w = r.points[2] - r.points[0]; val h = r.points[3] - r.points[1]
            assertEquals(200f, w, 10f); assertEquals(118f, h, 10f)
        }
    }

    @Test fun rotatedRectangleKeepsItsRotation() {
        val square = SyntheticInk.closed(0f, 0f, 160f, 0f, 160f, 90f, 0f, 90f)
        val rotated = SyntheticInk.rotate(square, 30f, 80f, 45f)
        val xy = SyntheticInk(4).polyline(*rotated)
        val r = recognize(xy)
        assertEquals(ShapeKind.RECTANGLE, r?.kind)
        assertEquals(30f, abs(r!!.rotation), 4f)
    }

    @Test fun squareGetsEqualSides() {
        val xy = SyntheticInk(6).polyline(*SyntheticInk.closed(0f, 0f, 104f, 2f, 102f, 98f, -2f, 100f))
        val r = recognize(xy)
        assertEquals(ShapeKind.RECTANGLE, r?.kind)
        assertEquals(r!!.points[2] - r.points[0], r.points[3] - r.points[1], 0.01f)
    }

    @Test fun ellipseAndCircle() {
        for (seed in 1..8) {
            val e = recognize(SyntheticInk(seed).ellipse(100f, 100f, 120f, 60f, sweepDeg = 365f))
            assertNotNull("seed $seed", e)
            assertEquals("seed $seed", ShapeKind.ELLIPSE, e!!.kind)
            assertEquals(240f, e.points[2] - e.points[0], 16f)
            assertEquals(120f, e.points[3] - e.points[1], 12f)
            assertEquals(0f, e.rotation, 0f)
        }
        // Circle that stops a little short of closing.
        val c = recognize(SyntheticInk(9).ellipse(0f, 0f, 70f, 66f, sweepDeg = 340f))
        assertEquals(ShapeKind.ELLIPSE, c?.kind)
        assertEquals(c!!.points[2] - c.points[0], c.points[3] - c.points[1], 0.01f)
    }

    @Test fun rotatedEllipse() {
        val r = recognize(SyntheticInk(2).ellipse(0f, 0f, 120f, 50f, rotationDeg = 35f, sweepDeg = 362f))
        assertEquals(ShapeKind.ELLIPSE, r?.kind)
        assertEquals(35f, r!!.rotation, 5f)
    }

    @Test fun triangle() {
        for (seed in 1..8) {
            val xy = SyntheticInk(seed).polyline(*SyntheticInk.closed(100f, 0f, 200f, 170f, 0f, 170f))
            val r = recognize(xy)
            assertNotNull("seed $seed", r)
            assertEquals("seed $seed", ShapeKind.TRIANGLE, r!!.kind)
            assertEquals(6, r.points.size)
            // Vertices land near the drawn corners.
            val corners = listOf(100f to 0f, 200f to 170f, 0f to 170f)
            for ((cx, cy) in corners) {
                val near = (0 until 3).minOf { hypot(r.points[it * 2] - cx, r.points[it * 2 + 1] - cy) }
                assertTrue("seed $seed corner ($cx,$cy) off by $near", near < 10f)
            }
        }
    }

    @Test fun pentagonAndHexagonArePolygons() {
        fun regular(n: Int, r: Float): FloatArray {
            val v = FloatArray(n * 2)
            for (i in 0 until n) {
                val a = -Math.PI / 2 + 2 * Math.PI * i / n
                v[i * 2] = 150f + r * kotlin.math.cos(a).toFloat(); v[i * 2 + 1] = 150f + r * kotlin.math.sin(a).toFloat()
            }
            return SyntheticInk.closed(*v)
        }
        for (n in 5..6) {
            val r = recognize(SyntheticInk(n).polyline(*regular(n, 110f)))
            assertNotNull("n=$n", r)
            assertEquals("n=$n", ShapeKind.POLYGON, r!!.kind)
            assertEquals("n=$n", n * 2, r.points.size)
        }
    }

    @Test fun autoRecognitionOnlyReturnsClosedShapes() {
        val line = SyntheticInk(1).polyline(0f, 0f, 200f, 0f)
        assertNull(recognize(line, closedOnly = true))
        val rect = SyntheticInk(1).polyline(*SyntheticInk.closed(0f, 0f, 200f, 0f, 200f, 120f, 0f, 120f))
        val r = recognize(rect, closedOnly = true)
        assertEquals(ShapeKind.RECTANGLE, r?.kind)
        assertTrue(r!!.error < ShapeRecognizer.CONFIDENT_ERROR)
    }

    // ------------------------------------------------------------------ Not shapes

    @Test fun handwritingIsNeverConverted() {
        val words = listOf("m", "w", "mmm", "nnn", "www", "e", "eee", "lel", "mew", "wenn")
        for (w in words) {
            for (h in listOf(14f, 40f, 80f)) {
                val xy = SyntheticInk.cursive(w, 0f, 100f, h)
                val r = recognize(xy)
                assertNull("'$w' at $h was recognised as $r", r)
                assertNull("'$w' at $h (auto)", recognize(xy, closedOnly = true))
            }
        }
    }

    @Test fun lettersWithClosedLoopsAreNotShapes() {
        // D: stem down then a bowl back to the top.
        val d = SyntheticInk(3).smooth(floatArrayOf(0f, 0f, 0f, 60f, 0f, 120f, 40f, 115f, 75f, 90f, 85f, 60f, 75f, 25f, 40f, 4f, 2f, 0f))
        assertNull("D", recognize(d))
        // Figure eight.
        val eight = SyntheticInk(4).smooth(floatArrayOf(50f, 50f, 80f, 25f, 50f, 0f, 20f, 25f, 50f, 50f, 85f, 80f, 50f, 110f, 15f, 80f, 50f, 50f))
        assertNull("8", recognize(eight))
        // Spiral (winds twice).
        val spiral = SyntheticInk(5).ellipse(0f, 0f, 80f, 80f, sweepDeg = 700f, samples = 240)
            .let { xy -> FloatArray(xy.size) { i -> xy[i] * (0.5f + 0.5f * (i / 2).toFloat() / (xy.size / 2)) } }
        assertNull("spiral", recognize(spiral))
        // Open C.
        assertNull("C", recognize(SyntheticInk(6).ellipse(0f, 0f, 60f, 60f, startDeg = 45f, sweepDeg = 270f)))
        // Z.
        assertNull("Z", recognize(SyntheticInk(7).polyline(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)))
        // Zig-zag.
        assertNull("zigzag", recognize(SyntheticInk(8).polyline(0f, 0f, 30f, 60f, 60f, 0f, 90f, 60f, 120f, 0f)))
        // Check mark.
        assertNull("check", recognize(SyntheticInk(9).polyline(0f, 40f, 30f, 80f, 120f, 0f)))
    }

    @Test fun tooSmallStrokesAreIgnored() {
        val tiny = SyntheticInk(1, jitter = 0.05f, wobble = 0.05f).polyline(0f, 0f, 15f, 0f, step = 1f)
        assertNull(recognize(tiny))
    }

    @Test fun sloppyShapesStillRecognised() {
        // Rounded corners, bowed sides and heavier jitter, as drawn quickly by hand.
        val ink = SyntheticInk(11, jitter = 1.6f, wobble = 3.5f)
        val roundRect = ink.smooth(floatArrayOf(
            30f, 10f, 120f, 6f, 200f, 12f, 214f, 26f, 216f, 70f, 212f, 120f, 198f, 134f, 110f, 138f, 26f, 132f, 12f, 120f, 8f, 70f, 12f, 24f, 32f, 9f, 60f, 8f,
        ))
        assertEquals(ShapeKind.RECTANGLE, recognize(roundRect)?.kind)
        val wobblyCircle = SyntheticInk(12, jitter = 1.5f, wobble = 4f).ellipse(0f, 0f, 90f, 84f, sweepDeg = 375f)
        assertEquals(ShapeKind.ELLIPSE, recognize(wobblyCircle)?.kind)
        val tri = SyntheticInk(13, jitter = 1.5f, wobble = 3f).polyline(*SyntheticInk.closed(110f, 0f, 205f, 160f, 5f, 158f), 108f, 4f)
        assertEquals(ShapeKind.TRIANGLE, recognize(tri)?.kind)
    }

    @Test fun arrowWithAnOpenVHead() {
        // Shaft, then one barb out, back along it, and the other barb (no return).
        val xy = SyntheticInk(14).polyline(0f, 100f, 160f, 20f, 128f, 22f, 160f, 20f, 150f, 50f)
        assertEquals(ShapeKind.ARROW, recognize(xy)?.kind)
    }
}
