package com.inkwell.notes.ink.select

import com.inkwell.notes.ink.geometry.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionMathTest {
    private val b = Bounds(100f, 100f, 300f, 200f)

    @Test fun cornerScaleKeepsAspectAroundTheOppositeCorner() {
        // Drag the bottom-right corner outwards by (100, 10): the projection decides the scale.
        val m = SelectionMath.scaleTransform(Handle.BOTTOM_RIGHT, b, 300f, 200f, 400f, 210f, 4f)
        assertEquals(m.a, m.d, 1e-5f)
        assertEquals(100f, m.mapX(100f, 100f), 1e-3f); assertEquals(100f, m.mapY(100f, 100f), 1e-3f)
        val s = m.a
        assertTrue(s > 1.3f && s < 1.5f)
        // Top-left corner anchors the bottom-right.
        val tl = SelectionMath.scaleTransform(Handle.TOP_LEFT, b, 100f, 100f, 200f, 150f, 4f)
        assertEquals(300f, tl.mapX(300f, 200f), 1e-3f); assertEquals(200f, tl.mapY(300f, 200f), 1e-3f)
        assertEquals(0.5f, tl.a, 1e-4f)
    }

    @Test fun grabOffsetIsKeptSoTheHandleFollowsThePointer() {
        // Grabbed 6pt outside the corner: no jump at the start.
        val m0 = SelectionMath.scaleTransform(Handle.BOTTOM_RIGHT, b, 306f, 206f, 306f, 206f, 4f)
        assertEquals(1f, m0.a, 1e-5f)
    }

    @Test fun edgesScaleOneAxis() {
        val m = SelectionMath.scaleTransform(Handle.RIGHT, b, 300f, 150f, 350f, 170f, 4f)
        assertEquals(1.25f, m.a, 1e-4f); assertEquals(1f, m.d, 0f)
        assertEquals(100f, m.mapX(100f, 150f), 1e-3f)
        val top = SelectionMath.scaleTransform(Handle.TOP, b, 200f, 100f, 200f, 50f, 4f)
        assertEquals(1f, top.a, 0f); assertEquals(1.5f, top.d, 1e-4f)
        assertEquals(200f, top.mapY(0f, 200f), 1e-3f)
    }

    @Test fun scalingNeverFlipsOrCollapses() {
        val m = SelectionMath.scaleTransform(Handle.RIGHT, b, 300f, 150f, 0f, 150f, 10f)
        assertEquals(10f / 200f, m.a, 1e-5f)
        val c = SelectionMath.scaleTransform(Handle.BOTTOM_RIGHT, b, 300f, 200f, 50f, 50f, 10f)
        assertTrue(c.a > 0f)
    }

    @Test fun rotationSnapsToRightAnglesWithinFourDegrees() {
        // Pointer starts straight above the centre and moves to 87 degrees clockwise.
        fun pointAt(deg: Double) = floatArrayOf((200 + 100 * Math.sin(Math.toRadians(deg))).toFloat(), (150 - 100 * Math.cos(Math.toRadians(deg))).toFloat())
        val p87 = pointAt(87.0)
        assertEquals(90f, SelectionMath.rotationDegrees(200f, 150f, 200f, 50f, p87[0], p87[1]), 1e-3f)
        val p80 = pointAt(80.0)
        assertEquals(80f, SelectionMath.rotationDegrees(200f, 150f, 200f, 50f, p80[0], p80[1]), 1e-2f)
        val pm3 = pointAt(-3.0)
        assertEquals(0f, SelectionMath.rotationDegrees(200f, 150f, 200f, 50f, pm3[0], pm3[1]), 1e-3f)
        // With an item already at 10 degrees, the absolute angle snaps: +78 becomes +80 (to 90).
        val p78 = pointAt(78.0)
        assertEquals(80f, SelectionMath.rotationDegrees(200f, 150f, 200f, 50f, p78[0], p78[1], baseDegrees = 10f), 1e-2f)
        assertEquals(180f, SelectionMath.snapRotation(177f), 0f)
        assertEquals(175f, SelectionMath.snapRotation(175f), 0f)
    }

    @Test fun lineAngleSnapsTo15DegreeSteps() {
        val p = SelectionMath.snapLineAngle(0f, 0f, 100f, 8f)
        assertEquals(0f, p[1], 1e-3f)
        assertEquals(100.32f, p[0], 0.01f)
        val q = SelectionMath.snapLineAngle(0f, 0f, 70f, 72f)
        assertEquals(q[0], q[1], 1e-3f)
    }

    @Test fun handlesHitTesting() {
        val h = SelectionHandles().apply {
            box.set(100f, 100f, 500f, 300f)
            touchRadius = 30f
            rotateOffset = 40f
        }
        assertEquals(Handle.TOP_LEFT, h.hit(102f, 98f))
        assertEquals(Handle.BOTTOM_RIGHT, h.hit(505f, 310f))
        assertEquals(Handle.RIGHT, h.hit(498f, 200f))
        assertEquals(Handle.TOP, h.hit(300f, 105f))
        assertEquals(Handle.ROTATE, h.hit(300f, 62f))
        assertEquals(Handle.BODY, h.hit(250f, 200f))
        assertNull(h.hit(700f, 200f))
        // Without edge handles an edge point is just the body.
        h.showEdges = false
        assertEquals(Handle.BODY, h.hit(498f, 200f))
        // A tiny box: the middle moves rather than grabbing a corner.
        val small = SelectionHandles().apply { box.set(100f, 100f, 140f, 130f); touchRadius = 30f; showRotate = false }
        assertEquals(Handle.BODY, small.hit(120f, 115f))
        assertEquals(Handle.BOTTOM_RIGHT, small.hit(145f, 136f))
    }

    @Test fun alignmentGuidesSnapEdgesAndCentres() {
        val others = listOf(Bounds(0f, 0f, 100f, 50f), Bounds(400f, 300f, 500f, 400f))
        // Moving box whose left edge is 3pt right of the first box's left edge.
        val moving = Bounds(3f, 120f, 83f, 170f)
        val r = AlignmentGuides.snap(moving, others, 6f)
        assertEquals(-3f, r.dx, 1e-4f)
        assertEquals(0f, r.dy, 0f)
        assertTrue(r.guides.any { it.vertical && it.at == 0f })
        // Centre alignment on y with the second box (centre 350).
        val m2 = Bounds(200f, 322f, 260f, 382f)
        val r2 = AlignmentGuides.snap(m2, others, 6f)
        assertEquals(-2f, r2.dy, 1e-4f)
        assertTrue(r2.guides.any { !it.vertical && it.at == 350f })
        // Too far: nothing snaps.
        val r3 = AlignmentGuides.snap(Bounds(130f, 500f, 160f, 520f), others, 6f)
        assertEquals(0f, r3.dx, 0f); assertEquals(0f, r3.dy, 0f)
        assertTrue(r3.guides.isEmpty())
    }
}
