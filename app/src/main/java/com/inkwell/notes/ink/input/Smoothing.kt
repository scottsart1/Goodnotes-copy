package com.inkwell.notes.ink.input

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One-euro filter for 2D pen positions: heavy smoothing at low speed (kills jitter), little lag
 * at high speed. Positions are filtered in page units while the speed that adapts the cutoff is
 * measured in screen pixels (pass [pxPerUnit]), so behaviour does not depend on zoom.
 * Pure Kotlin, allocation free.
 */
class PointSmoother {
    var minCutoff = 4f
        private set
    var beta = 0.01f
        private set
    private val dCutoff = 1.2f
    var enabled = true
        private set

    private var has = false
    private var x = 0f
    private var y = 0f
    private var dx = 0f
    private var dy = 0f
    private var lastT = 0L

    var outX = 0f
        private set
    var outY = 0f
        private set

    /** Maps PenConfig.stabilization (0..1) to filter parameters; 0 disables smoothing. */
    fun configure(stabilization: Float) {
        val s = stabilization.coerceIn(0f, 1f)
        enabled = s > 0.01f
        val inv = 1f - s
        minCutoff = 12f * inv * inv + 0.6f
        beta = 0.004f + 0.02f * inv
    }

    fun reset() { has = false }

    /** Filters one sample at time [tMs]; read the result from [outX]/[outY]. */
    fun push(px: Float, py: Float, tMs: Long, pxPerUnit: Float) {
        if (!enabled || !has) {
            has = true
            x = px; y = py; dx = 0f; dy = 0f; lastT = tMs
            outX = px; outY = py
            return
        }
        val dtMs = (tMs - lastT).coerceAtLeast(1L)
        lastT = tMs
        val dt = dtMs / 1000f
        val rawDx = (px - x) / dt
        val rawDy = (py - y) / dt
        val ad = alpha(dCutoff, dt)
        dx += ad * (rawDx - dx)
        dy += ad * (rawDy - dy)
        val speedPx = sqrt(dx * dx + dy * dy) * pxPerUnit
        val cutoff = minCutoff + beta * speedPx
        val a = alpha(cutoff, dt)
        x += a * (px - x)
        y += a * (py - y)
        outX = x; outY = y
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

/** Pressure response curve from InputPrefs.pressureCurve. */
object PressureCurve {
    /**
     * Maps raw pressure through a gamma curve. [bias] 0.5 is linear; higher values make light
     * touches heavier (softer feel), lower values need more force.
     */
    fun apply(pressure: Float, bias: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        val b = bias.coerceIn(0f, 1f)
        if (b in 0.495f..0.505f) return p
        val exponent = 4f.pow(1f - 2f * b)
        return p.pow(exponent).coerceIn(0f, 1f)
    }
}
