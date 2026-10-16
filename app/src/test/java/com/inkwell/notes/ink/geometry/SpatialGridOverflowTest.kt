package com.inkwell.notes.ink.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

/** Absurd coordinates (corrupt or pasted far away) must never make the grid loop over billions of cells. */
class SpatialGridOverflowTest {
    private fun q(g: SpatialGrid, b: Bounds): Set<Int> { val out = HashSet<Int>(); g.query(b) { out += it }; return out }

    @Test(timeout = 5000) fun hugeItemBoundsAreOversizedNotEnumerated() {
        val g = SpatialGrid()
        g.insert(0, Bounds(0f, 0f, 1e12f, 1e12f))
        g.insert(1, Bounds(-1e12f, -1e12f, 1e12f, 1e12f))
        g.insert(2, Bounds(10f, 10f, 20f, 20f))
        g.insert(3, Bounds(Float.MAX_VALUE / 2f, 0f, Float.MAX_VALUE, 1f))
        assertEquals(setOf(0, 1, 2), q(g, Bounds(10f, 10f, 20f, 20f)))
        assertEquals(setOf(1), q(g, Bounds(-500f, -500f, -400f, -400f)))
    }

    @Test(timeout = 5000) fun hugeQueriesScanInsteadOfWalkingCells() {
        val g = SpatialGrid()
        g.insert(0, Bounds(10f, 10f, 20f, 20f))
        g.insert(1, Bounds(5000f, 5000f, 5001f, 5001f))
        assertEquals(setOf(0, 1), q(g, Bounds(-1e12f, -1e12f, 1e12f, 1e12f)))
        assertEquals(setOf(0, 1), q(g, Bounds(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)))
    }
}
