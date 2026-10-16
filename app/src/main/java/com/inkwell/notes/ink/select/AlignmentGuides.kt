package com.inkwell.notes.ink.select

import com.inkwell.notes.ink.geometry.Bounds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Smart guides for moving items on the infinite canvas: the moving box's edges and centre snap
 * to the edges and centres of nearby items when within a threshold, and the matching alignments
 * come back as guide lines to draw. Pure Kotlin.
 */
object AlignmentGuides {
    /** A guide line in page points: vertical when [vertical] (x = [at], from [from] to [to]). */
    data class Guide(val vertical: Boolean, val at: Float, val from: Float, val to: Float)

    class Result(val dx: Float, val dy: Float, val guides: List<Guide>)

    /**
     * Snaps [moving] (already at its tentative position) against [candidates]. Returns the extra
     * offset to apply and the guides of the alignments that hold after snapping.
     */
    fun snap(moving: Bounds, candidates: List<Bounds>, threshold: Float): Result {
        if (moving.isEmpty || candidates.isEmpty() || threshold <= 0f) return Result(0f, 0f, emptyList())
        val mx = floatArrayOf(moving.left, moving.centerX, moving.right)
        val my = floatArrayOf(moving.top, moving.centerY, moving.bottom)
        var bestDx = Float.NaN
        var bestDy = Float.NaN
        for (c in candidates) {
            if (c.isEmpty) continue
            val cx = floatArrayOf(c.left, c.centerX, c.right)
            val cy = floatArrayOf(c.top, c.centerY, c.bottom)
            for (a in mx) for (t in cx) {
                val d = t - a
                if (abs(d) <= threshold && (bestDx.isNaN() || abs(d) < abs(bestDx))) bestDx = d
            }
            for (a in my) for (t in cy) {
                val d = t - a
                if (abs(d) <= threshold && (bestDy.isNaN() || abs(d) < abs(bestDy))) bestDy = d
            }
        }
        val dx = if (bestDx.isNaN()) 0f else bestDx
        val dy = if (bestDy.isNaN()) 0f else bestDy
        val snapped = moving.copy().offset(dx, dy)
        return Result(dx, dy, guidesFor(snapped, candidates, if (bestDx.isNaN()) -1f else EPS, if (bestDy.isNaN()) -1f else EPS))
    }

    private const val EPS = 0.01f

    /** Guide lines for every exact alignment of [b] with a candidate (tolerances < 0 disable an axis). */
    fun guidesFor(b: Bounds, candidates: List<Bounds>, tolX: Float, tolY: Float): List<Guide> {
        val out = ArrayList<Guide>()
        val bx = floatArrayOf(b.left, b.centerX, b.right)
        val by = floatArrayOf(b.top, b.centerY, b.bottom)
        if (tolX >= 0f) {
            for (x in bx) {
                var from = Float.NaN; var to = Float.NaN
                for (c in candidates) {
                    if (c.isEmpty) continue
                    if (abs(c.left - x) <= tolX || abs(c.centerX - x) <= tolX || abs(c.right - x) <= tolX) {
                        from = if (from.isNaN()) min(c.top, b.top) else min(from, c.top)
                        to = if (to.isNaN()) max(c.bottom, b.bottom) else max(to, c.bottom)
                    }
                }
                if (!from.isNaN()) out += Guide(true, x, from, to)
            }
        }
        if (tolY >= 0f) {
            for (y in by) {
                var from = Float.NaN; var to = Float.NaN
                for (c in candidates) {
                    if (c.isEmpty) continue
                    if (abs(c.top - y) <= tolY || abs(c.centerY - y) <= tolY || abs(c.bottom - y) <= tolY) {
                        from = if (from.isNaN()) min(c.left, b.left) else min(from, c.left)
                        to = if (to.isNaN()) max(c.right, b.right) else max(to, c.right)
                    }
                }
                if (!from.isNaN()) out += Guide(false, y, from, to)
            }
        }
        return out
    }
}
