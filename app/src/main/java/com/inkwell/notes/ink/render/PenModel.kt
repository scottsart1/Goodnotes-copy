package com.inkwell.notes.ink.render

import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.ink.geometry.Geo
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pure width model of every pen. Shared by the live (wet) stroke and the committed renderer so a
 * stroke never changes shape on pen-up. Factors are relative to the stroke's nominal width.
 */
object PenModel {
    /** Unit vector of the fountain nib (45 degrees, up-right in y-down page space). */
    private const val NIB_X = 0.70710677f
    private const val NIB_Y = -0.70710677f

    /**
     * Width multiplier at one sample.
     * @param pressure 0..1 (already mapped through the pressure curve)
     * @param sensitivity 0..1 how strongly pressure changes width
     * @param speed page points per millisecond
     * @param tilt radians, 0 = perpendicular
     * @param dirX unit direction of travel (0,0 if unknown)
     * @param distFromStart arc length from the stroke start in page points (for start tapers)
     * @param width nominal width in page points (scales the taper length)
     */
    fun widthFactor(
        pen: PenType,
        pressure: Float,
        sensitivity: Float,
        speed: Float = 0f,
        tilt: Float = 0f,
        dirX: Float = 0f,
        dirY: Float = 0f,
        distFromStart: Float = Float.MAX_VALUE,
        width: Float = 1f,
    ): Float {
        val p = pressure.coerceIn(0f, 1f)
        val s = sensitivity.coerceIn(0f, 1f)
        val f = when (pen) {
            PenType.BALLPOINT -> 1f - 0.3f * s + 0.45f * s * p
            PenType.FOUNTAIN -> {
                var w = s * (0.35f + 0.95f * p.pow(0.8f)) + (1f - s) * 0.95f
                if (dirX != 0f || dirY != 0f) {
                    // Thinnest when travelling along the nib, full width across it.
                    val across = abs(dirX * NIB_Y - dirY * NIB_X)
                    w *= 0.8f + 0.2f * across
                }
                w *= 1f - 0.22f * Geo.smoothstep((speed - 0.15f) / 1.2f)
                w *= 0.45f + 0.55f * Geo.smoothstep(distFromStart / (2.2f * width.coerceAtLeast(0.2f)))
                w
            }
            PenType.BRUSH -> {
                var w = s * (0.12f + 1.78f * p.pow(1.35f)) + (1f - s) * 0.9f
                w *= 1f - 0.3f * Geo.smoothstep(speed / 1.5f)
                w *= 0.3f + 0.7f * Geo.smoothstep(distFromStart / (3f * width.coerceAtLeast(0.2f)))
                w
            }
            PenType.PENCIL -> (0.75f + 0.45f * s * p) * (1f + 1.8f * Geo.smoothstep((tilt - 0.35f) / 0.7f))
            PenType.HIGHLIGHTER -> 1f
        }
        return f.coerceAtLeast(0.05f)
    }

    /** Upper bound of [widthFactor] for a pen (used for conservative bounds). */
    fun maxFactor(pen: PenType): Float = when (pen) {
        PenType.BALLPOINT -> 1.15f
        PenType.FOUNTAIN -> 1.3f
        PenType.BRUSH -> 1.9f
        PenType.PENCIL -> 3.4f
        PenType.HIGHLIGHTER -> 1f
    }

    /** Smallest drawn radius in page points so hairlines never vanish. */
    const val MIN_RADIUS = 0.08f
}
