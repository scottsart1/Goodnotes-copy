package com.inkwell.notes.settings.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * The pressure response curve behind [com.inkwell.notes.core.model.InputPrefs.pressureCurve].
 *
 * The bias is a 0..1 value where 0.5 is linear. It maps to a gamma exponent of
 * `4^(1 - 2 * bias)`: higher values make light touches heavier (a softer feel), lower
 * values need more force for a bold line. This mirrors the ink engine's mapping so the
 * preview in settings shows exactly what the canvas will do.
 */
object PressureCurveMath {
    const val LINEAR = 0.5f

    /** Slider values this close to [LINEAR] snap to it, so "linear" is easy to hit by hand. */
    private const val SNAP_DISTANCE = 0.02f

    /** Treated as exactly linear (avoids pow() noise around 1.0). */
    private const val LINEAR_TOLERANCE = 0.005f

    enum class Feel { FIRM, LINEAR, SOFT }

    /** The gamma exponent for [bias]: 4 at 0 (firm), 1 at 0.5 (linear), 0.25 at 1 (soft). */
    fun exponent(bias: Float): Float = 4f.pow(1f - 2f * sanitizeBias(bias))

    /** Maps a raw 0..1 stylus pressure through the curve. */
    fun apply(pressure: Float, bias: Float): Float {
        if (pressure.isNaN()) return 0f
        val p = pressure.coerceIn(0f, 1f)
        val b = sanitizeBias(bias)
        if (abs(b - LINEAR) <= LINEAR_TOLERANCE) return p
        return p.pow(exponent(b)).coerceIn(0f, 1f)
    }

    /** Clamps to 0..1 and snaps near-linear values to [LINEAR]. */
    fun snap(bias: Float): Float {
        val b = sanitizeBias(bias)
        return if (abs(b - LINEAR) < SNAP_DISTANCE) LINEAR else b
    }

    fun feel(bias: Float): Feel {
        val b = sanitizeBias(bias)
        return when {
            abs(b - LINEAR) <= LINEAR_TOLERANCE -> Feel.LINEAR
            b > LINEAR -> Feel.SOFT
            else -> Feel.FIRM
        }
    }

    fun describe(bias: Float): String = when (feel(bias)) {
        Feel.LINEAR -> "Linear: line weight follows your pressure one to one."
        Feel.SOFT -> "Soft: light touches already give full, dark lines."
        Feel.FIRM -> "Firm: press harder for bold lines; light touches stay thin."
    }

    /**
     * [count] evenly spaced points of the curve as interleaved x, y pairs (both 0..1),
     * for drawing the preview.
     */
    fun samples(bias: Float, count: Int = 48): FloatArray {
        require(count >= 2) { "count must be at least 2" }
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            val x = i / (count - 1f)
            out[i * 2] = x
            out[i * 2 + 1] = apply(x, bias)
        }
        return out
    }

    /** Simulated pen pressure along a sample stroke at [t] in 0..1: rises, peaks mid-way, falls. */
    fun samplePressure(t: Float): Float = sin(PI * t.coerceIn(0f, 1f)).toFloat().coerceIn(0f, 1f)

    private fun sanitizeBias(bias: Float): Float = if (bias.isNaN()) LINEAR else bias.coerceIn(0f, 1f)
}
