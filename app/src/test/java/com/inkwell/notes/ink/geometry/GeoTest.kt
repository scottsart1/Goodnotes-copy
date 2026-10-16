package com.inkwell.notes.ink.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val out = FloatArray(2)

    @Test fun discIntervalThroughCentre() {
        // Segment (0,0)-(10,0) through a circle at (5,0) radius 2 -> t in [0.3, 0.7].
        assertTrue(Geo.discInterval(0f, 0f, 10f, 0f, 5f, 0f, 2f, out))
        assertEquals(0.3f, out[0], 1e-4f)
        assertEquals(0.7f, out[1], 1e-4f)
    }

    @Test fun discIntervalMiss() {
        assertFalse(Geo.discInterval(0f, 0f, 10f, 0f, 5f, 5f, 2f, out))
    }

    @Test fun discIntervalSegmentInside() {
        assertTrue(Geo.discInterval(4f, 0f, 6f, 0f, 5f, 0f, 3f, out))
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
    }

    @Test fun capsuleWithZeroLengthEqualsDisc() {
        assertTrue(Geo.capsuleInterval(0f, 0f, 10f, 0f, 5f, 0f, 5f, 0f, 2f, out))
        assertEquals(0.3f, out[0], 1e-4f)
        assertEquals(0.7f, out[1], 1e-4f)
    }

    @Test fun capsuleCrossingPerpendicular() {
        // Eraser swept vertically from (5,-10) to (5,10) with radius 1 cuts x in [4, 6].
        assertTrue(Geo.capsuleInterval(0f, 0f, 10f, 0f, 5f, -10f, 5f, 10f, 1f, out))
        assertEquals(0.4f, out[0], 1e-4f)
        assertEquals(0.6f, out[1], 1e-4f)
    }

    @Test fun capsuleParallelOverlap() {
        // Eraser sweeps along the segment from x=2 to x=4 at distance 0.5, radius 1: x in [1, 5].
        assertTrue(Geo.capsuleInterval(0f, 0f, 10f, 0f, 2f, 0.5f, 4f, 0.5f, 1f, out))
        assertEquals(0.1134f, out[0], 2e-3f)
        assertEquals(0.4866f, out[1], 2e-3f)
    }

    @Test fun capsuleMiss() {
        assertFalse(Geo.capsuleInterval(0f, 0f, 10f, 0f, 0f, 5f, 10f, 5f, 1f, out))
    }

    @Test fun segmentIntersectionParameter() {
        assertEquals(0.5f, Geo.segmentIntersection(0f, 0f, 10f, 0f, 5f, -5f, 5f, 5f), 1e-5f)
        assertTrue(Geo.segmentIntersection(0f, 0f, 10f, 0f, 15f, -5f, 15f, 5f).isNaN())
        assertTrue(Geo.segmentIntersection(0f, 0f, 10f, 0f, 0f, 1f, 10f, 1f).isNaN())
    }

    @Test fun segmentDistance() {
        assertEquals(0f, Geo.segmentDistanceSq(0f, 0f, 10f, 0f, 5f, -5f, 5f, 5f), 0f)
        assertEquals(9f, Geo.segmentDistanceSq(0f, 0f, 10f, 0f, 0f, 3f, 10f, 3f), 1e-4f)
        assertEquals(4f, Geo.distSqPointSegment(12f, 0f, 0f, 0f, 10f, 0f), 1e-5f)
    }

    @Test fun pointInPolygonConcave() {
        // U shape.
        val u = floatArrayOf(0f, 0f, 3f, 0f, 3f, 3f, 2f, 3f, 2f, 1f, 1f, 1f, 1f, 3f, 0f, 3f)
        assertTrue(Geo.pointInPolygon(0.5f, 2f, u))
        assertFalse(Geo.pointInPolygon(1.5f, 2f, u))
        assertTrue(Geo.pointInPolygon(1.5f, 0.5f, u))
        assertFalse(Geo.pointInPolygon(5f, 1f, u))
    }

    @Test fun turnAngles() {
        assertEquals(0f, Geo.turnAngle(0f, 0f, 1f, 0f, 2f, 0f), 1e-5f)
        assertEquals(Math.PI.toFloat() / 2, Geo.turnAngle(0f, 0f, 1f, 0f, 1f, 1f), 1e-5f)
    }

    @Test fun boundsUnionAndIntersect() {
        val b = Bounds()
        assertTrue(b.isEmpty)
        b.union(1f, 2f).union(5f, -1f)
        assertEquals(Bounds(1f, -1f, 5f, 2f), b)
        assertTrue(b.intersects(Bounds(4f, 0f, 9f, 9f)))
        assertFalse(b.intersects(Bounds(6f, 0f, 9f, 9f)))
        assertTrue(Bounds().union(b).contains(3f, 0f))
    }

    @Test fun segmentRectIntersection() {
        val r = Bounds(0f, 0f, 10f, 10f)
        assertTrue(Geo.segmentIntersectsRect(-5f, 5f, 15f, 5f, r))
        assertTrue(Geo.segmentIntersectsRect(2f, 2f, 3f, 3f, r))
        assertFalse(Geo.segmentIntersectsRect(-5f, -5f, -1f, 20f, r))
    }

    @Test fun spatialGridFindsOverlappingEntries() {
        val g = SpatialGrid(cellSize = 10f)
        g.insert(0, Bounds(0f, 0f, 5f, 5f))
        g.insert(1, Bounds(100f, 100f, 120f, 110f))
        g.insert(2, Bounds(-5000f, -5000f, 5000f, 5000f)) // oversized
        val found = mutableListOf<Int>()
        g.query(Bounds(101f, 101f, 102f, 102f)) { found += it }
        assertEquals(setOf(1, 2), found.toSet())
        found.clear()
        g.query(Bounds(1f, 1f, 2f, 2f)) { found += it }
        assertEquals(setOf(0, 2), found.toSet())
    }
}
