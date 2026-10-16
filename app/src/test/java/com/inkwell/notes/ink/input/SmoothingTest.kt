package com.inkwell.notes.ink.input

import com.inkwell.notes.core.model.FingerAction
import com.inkwell.notes.core.model.InputPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SmoothingTest {
    @Test fun zeroStabilizationPassesThrough() {
        val f = PointSmoother().apply { configure(0f) }
        assertFalse(f.enabled)
        f.push(1f, 2f, 0, 1f); f.push(5f, 7f, 4, 1f)
        assertEquals(5f, f.outX, 0f); assertEquals(7f, f.outY, 0f)
    }

    @Test fun firstSampleIsExact() {
        val f = PointSmoother().apply { configure(0.8f) }
        f.push(10f, 20f, 100, 2f)
        assertEquals(10f, f.outX, 0f); assertEquals(20f, f.outY, 0f)
    }

    @Test fun convergesToAStationaryPoint() {
        val f = PointSmoother().apply { configure(0.6f) }
        f.push(0f, 0f, 0, 1f)
        var t = 0L
        repeat(400) { t += 4; f.push(10f, 10f, t, 1f) }
        assertEquals(10f, f.outX, 0.01f); assertEquals(10f, f.outY, 0.01f)
    }

    @Test fun reducesJitterOfASlowStroke() {
        val rnd = Random(7)
        val f = PointSmoother().apply { configure(0.5f) }
        var t = 0L
        var rawErr = 0f; var filtErr = 0f
        for (i in 0 until 300) {
            t += 4
            val trueY = 50f
            val noisy = trueY + (rnd.nextFloat() - 0.5f) * 2f
            f.push(i * 0.2f, noisy, t, 1f)
            if (i > 30) { rawErr += abs(noisy - trueY); filtErr += abs(f.outY - trueY) }
        }
        assertTrue("filtered $filtErr raw $rawErr", filtErr < rawErr * 0.6f)
    }

    @Test fun lagIsSmallAtHighSpeed() {
        val f = PointSmoother().apply { configure(0.3f) }
        var t = 0L
        var x = 0f
        repeat(100) { t += 4; x += 8f; f.push(x, 0f, t, 1f) } // 2000 px/s
        assertTrue("lag ${x - f.outX}", x - f.outX < 12f)
    }

    @Test fun strongerStabilizationSmoothsMore() {
        val light = PointSmoother().apply { configure(0.1f) }
        val heavy = PointSmoother().apply { configure(0.9f) }
        assertTrue(heavy.minCutoff < light.minCutoff)
    }

    @Test fun pressureCurveIsLinearAtHalfAndMonotonic() {
        assertEquals(0.3f, PressureCurve.apply(0.3f, 0.5f), 1e-6f)
        assertTrue(PressureCurve.apply(0.3f, 0.8f) > 0.3f) // softer
        assertTrue(PressureCurve.apply(0.3f, 0.2f) < 0.3f) // firmer
        assertEquals(1f, PressureCurve.apply(1f, 0.1f), 1e-6f)
        assertEquals(0f, PressureCurve.apply(0f, 0.9f), 1e-6f)
        var prev = -1f
        for (i in 0..20) { val v = PressureCurve.apply(i / 20f, 0.7f); assertTrue(v >= prev); prev = v }
        assertEquals(1f, PressureCurve.apply(3f, 0.5f), 0f)
    }

    @Test fun touchPolicyPalmAndFingerRules() {
        val p = TouchPolicy().apply { palmMajorPx = 100f }
        assertTrue(p.isPalm(150f))
        assertFalse(p.isPalm(40f))
        assertFalse(p.isPalm(0f))
        p.prefs = InputPrefs(rejectLargeContacts = false)
        assertFalse(p.isPalm(500f))
        p.prefs = InputPrefs(zoomWhileWriting = true)
        assertEquals(FingerPermission.ALL, p.fingerPermission(stylusNear = false))
        assertEquals(FingerPermission.MULTI_ONLY, p.fingerPermission(stylusNear = true))
        p.prefs = InputPrefs(zoomWhileWriting = false)
        assertEquals(FingerPermission.NONE, p.fingerPermission(stylusNear = true))
        p.prefs = InputPrefs(stylusOnlyInk = true, fingerAction = FingerAction.DRAW)
        assertEquals(FingerRole.NAVIGATE, p.fingerRole())
        p.prefs = InputPrefs(stylusOnlyInk = false, fingerAction = FingerAction.DRAW)
        assertEquals(FingerRole.INK, p.fingerRole())
        p.prefs = InputPrefs(stylusOnlyInk = false, fingerAction = FingerAction.SELECT)
        assertEquals(FingerRole.SELECT, p.fingerRole())
        assertTrue(p.deliberateMultiTouch(100, stylusNear = true))
        assertFalse(p.deliberateMultiTouch(600, stylusNear = true))
        assertTrue(p.deliberateMultiTouch(600, stylusNear = false))
    }

    @Test fun multiFingerTaps() {
        val tap = MultiFingerTapTracker(tapTimeoutMs = 300, slopPx = 20f)
        tap.begin(0); tap.pointerCount(2); tap.moved(5f)
        assertEquals(2, tap.end(200))
        tap.begin(0); tap.pointerCount(2); tap.pointerCount(3)
        assertEquals(3, tap.end(250))
        // Too slow.
        tap.begin(0); tap.pointerCount(2)
        assertEquals(0, tap.end(500))
        // Moved (a pinch or pan).
        tap.begin(0); tap.pointerCount(2); tap.moved(40f)
        assertEquals(0, tap.end(100))
        // Single finger is not a multi-finger tap.
        tap.begin(0)
        assertEquals(0, tap.end(50))
        // Four fingers.
        tap.begin(0); tap.pointerCount(4)
        assertEquals(0, tap.end(50))
    }
}
