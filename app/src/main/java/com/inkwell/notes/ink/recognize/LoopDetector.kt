package com.inkwell.notes.ink.recognize

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Circle-to-select: decides whether a stroke is a closed loop (drawn around something) and
 * returns the enclosed polygon. A loop must close (its end returns near its start or crosses
 * its beginning), wind about once around (not a figure eight or a zig-zag) and enclose a real
 * area. Pure Kotlin.
 */
object LoopDetector {
    /** Closing gap allowed, relative to the loop's bounding diagonal. */
    const val CLOSE_GAP = 0.3f
    /** Enclosed area relative to the bounding box area. */
    const val MIN_FILL = 0.25f

    /**
     * The loop polygon (x,y pairs) when [xy] is a closed loop at least [minSize] across,
     * otherwise null.
     */
    fun closedLoop(xy: FloatArray, minSize: Float): FloatArray? {
        val n = PathOps.count(xy)
        if (n < 8) return null
        val b = PathOps.bounds(xy)
        if (max(b.width, b.height) < minSize) return null
        val diag = hypot(b.width, b.height)
        val len = PathOps.length(xy)
        if (len < diag * 1.8f) return null
        val r = PathOps.resample(xy, 128)
        val m = PathOps.count(r)
        // Where the loop closes: the end crossing its beginning, or the point nearest the start.
        val cross = crossing(r)
        var start = 0
        var cut = -1
        if (cross != null) {
            start = cross.first + 1; cut = cross.second
        } else {
            var best = Float.POSITIVE_INFINITY
            for (i in (m * 6) / 10 until m) {
                val d = PathOps.dist(r, 0, i)
                if (d < best) { best = d; cut = i }
            }
            if (best > CLOSE_GAP * diag) return null
        }
        if (cut - start < 8) return null
        val loop = r.copyOfRange(start * 2, (cut + 1) * 2)
        val turns = PathOps.turning(loop, closed = true)
        var total = 0f
        for (t in turns) total += t
        if (abs(total) < 1.5f * PI.toFloat()) return null
        val lb = PathOps.bounds(loop)
        if (lb.width <= 0f || lb.height <= 0f) return null
        if (PathOps.polygonArea(loop) < MIN_FILL * lb.width * lb.height) return null
        return loop
    }

    /** Segment indices (head, tail) where the last 45% of the path crosses its first 30%, if it does. */
    private fun crossing(r: FloatArray): Pair<Int, Int>? {
        val m = PathOps.count(r)
        val headEnd = (m * 3) / 10
        val tailStart = (m * 55) / 100
        for (j in tailStart until m - 1) {
            for (i in 0 until headEnd) {
                if (segmentsCross(r, i, i + 1, j, j + 1)) return i to j
            }
        }
        return null
    }

    private fun segmentsCross(r: FloatArray, a: Int, b: Int, c: Int, d: Int): Boolean {
        val ax = r[a * 2]; val ay = r[a * 2 + 1]; val bx = r[b * 2]; val by = r[b * 2 + 1]
        val cx = r[c * 2]; val cy = r[c * 2 + 1]; val dx = r[d * 2]; val dy = r[d * 2 + 1]
        val d1 = cross(cx, cy, dx, dy, ax, ay); val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy); val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0f) != (d2 > 0f)) && ((d3 > 0f) != (d4 > 0f)) && d1 != 0f && d2 != 0f && d3 != 0f && d4 != 0f
    }

    private fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)
}
