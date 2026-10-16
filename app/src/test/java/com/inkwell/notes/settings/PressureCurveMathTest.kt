package com.inkwell.notes.settings

import com.inkwell.notes.settings.logic.PressureCurveMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureCurveMathTest {
    private val eps = 1e-5f

    @Test
    fun linearAtHalf() {
        for (p in listOf(0f, 0.1f, 0.25f, 0.5f, 0.8f, 1f)) assertEquals(p, PressureCurveMath.apply(p, 0.5f), eps)
        assertEquals(1f, PressureCurveMath.exponent(0.5f), eps)
    }

    @Test
    fun endpointsAreFixedForAnyBias() {
        for (b in listOf(0f, 0.2f, 0.5f, 0.7f, 1f)) {
            assertEquals(0f, PressureCurveMath.apply(0f, b), eps)
            assertEquals(1f, PressureCurveMath.apply(1f, b), eps)
        }
    }

    @Test
    fun softBiasMakesLightTouchesHeavier() {
        val p = 0.3f
        assertTrue(PressureCurveMath.apply(p, 0.8f) > p)
        assertTrue(PressureCurveMath.apply(p, 1f) > PressureCurveMath.apply(p, 0.8f))
    }

    @Test
    fun firmBiasNeedsMoreForce() {
        val p = 0.6f
        assertTrue(PressureCurveMath.apply(p, 0.2f) < p)
        assertTrue(PressureCurveMath.apply(p, 0f) < PressureCurveMath.apply(p, 0.2f))
    }

    @Test
    fun exponentRangeMatchesInkEngine() {
        // 4^(1 - 2b): 4 at 0, 0.25 at 1 - the same mapping the canvas uses.
        assertEquals(4f, PressureCurveMath.exponent(0f), eps)
        assertEquals(0.25f, PressureCurveMath.exponent(1f), eps)
        assertEquals(Math.pow(0.5, 4.0).toFloat(), PressureCurveMath.apply(0.5f, 0f), eps)
        assertEquals(Math.pow(0.5, 0.25).toFloat(), PressureCurveMath.apply(0.5f, 1f), eps)
    }

    @Test
    fun monotonicForEveryBias() {
        for (b in listOf(0f, 0.1f, 0.3f, 0.5f, 0.66f, 0.9f, 1f)) {
            var prev = -1f
            for (i in 0..100) {
                val v = PressureCurveMath.apply(i / 100f, b)
                assertTrue("bias $b at $i", v >= prev)
                assertTrue(v in 0f..1f)
                prev = v
            }
        }
    }

    @Test
    fun clampsAndHandlesNaN() {
        assertEquals(0f, PressureCurveMath.apply(-0.5f, 0.7f), eps)
        assertEquals(1f, PressureCurveMath.apply(1.7f, 0.2f), eps)
        assertEquals(0f, PressureCurveMath.apply(Float.NaN, 0.5f), eps)
        assertEquals(0.4f, PressureCurveMath.apply(0.4f, Float.NaN), eps)
        assertEquals(PressureCurveMath.apply(0.4f, 1f), PressureCurveMath.apply(0.4f, 3f), eps)
    }

    @Test
    fun snapsNearLinear() {
        assertEquals(0.5f, PressureCurveMath.snap(0.51f), eps)
        assertEquals(0.5f, PressureCurveMath.snap(0.485f), eps)
        assertEquals(0.6f, PressureCurveMath.snap(0.6f), eps)
        assertEquals(1f, PressureCurveMath.snap(1.4f), eps)
    }

    @Test
    fun feelLabels() {
        assertEquals(PressureCurveMath.Feel.LINEAR, PressureCurveMath.feel(0.5f))
        assertEquals(PressureCurveMath.Feel.SOFT, PressureCurveMath.feel(0.8f))
        assertEquals(PressureCurveMath.Feel.FIRM, PressureCurveMath.feel(0.2f))
        assertTrue(PressureCurveMath.describe(0.8f).startsWith("Soft"))
    }

    @Test
    fun samplesCoverTheUnitSquare() {
        val s = PressureCurveMath.samples(0.7f, 11)
        assertEquals(22, s.size)
        assertEquals(0f, s[0], eps)
        assertEquals(0f, s[1], eps)
        assertEquals(1f, s[20], eps)
        assertEquals(1f, s[21], eps)
        assertEquals(PressureCurveMath.apply(0.5f, 0.7f), s[11], eps)
    }

    @Test
    fun samplePressureRisesAndFalls() {
        assertEquals(0f, PressureCurveMath.samplePressure(0f), eps)
        assertEquals(1f, PressureCurveMath.samplePressure(0.5f), eps)
        assertEquals(0f, PressureCurveMath.samplePressure(1f), 1e-4f)
    }
}
