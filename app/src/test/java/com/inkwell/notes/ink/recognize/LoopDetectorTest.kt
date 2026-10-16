package com.inkwell.notes.ink.recognize

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopDetectorTest {
    @Test fun circleAroundSomethingIsALoop() {
        val loop = LoopDetector.closedLoop(SyntheticInk(1).ellipse(100f, 100f, 80f, 50f, sweepDeg = 350f), 20f)
        assertNotNull(loop)
        assertTrue(PathOps.polygonArea(loop!!) > 0.6f * Math.PI.toFloat() * 80f * 50f)
    }

    @Test fun overshootingLoopIsTrimmedAtTheCrossing() {
        val loop = LoopDetector.closedLoop(SyntheticInk(2).ellipse(0f, 0f, 60f, 60f, sweepDeg = 420f), 20f)
        assertNotNull(loop)
        // The trimmed loop goes around about once: its length is close to the circumference.
        val len = PathOps.length(loop!!, closed = true)
        assertTrue("length $len", len < 2f * Math.PI.toFloat() * 60f * 1.15f)
    }

    @Test fun openCurvesAndFiguresAreNotLoops() {
        assertNull("C", LoopDetector.closedLoop(SyntheticInk(3).ellipse(0f, 0f, 60f, 60f, startDeg = 45f, sweepDeg = 260f), 20f))
        assertNull("line", LoopDetector.closedLoop(SyntheticInk(4).polyline(0f, 0f, 200f, 10f), 20f))
        val eight = SyntheticInk(5).smooth(floatArrayOf(50f, 50f, 80f, 25f, 50f, 0f, 20f, 25f, 50f, 50f, 85f, 80f, 50f, 110f, 15f, 80f, 50f, 50f))
        assertNull("8", LoopDetector.closedLoop(eight, 20f))
        assertNull("zigzag", LoopDetector.closedLoop(SyntheticInk(6).polyline(0f, 0f, 40f, 60f, 80f, 0f, 120f, 60f, 160f, 0f), 20f))
    }

    @Test fun smallLoopsAreIgnored() {
        assertNull(LoopDetector.closedLoop(SyntheticInk(7, jitter = 0.05f, wobble = 0.05f).ellipse(0f, 0f, 6f, 6f), 20f))
    }
}
