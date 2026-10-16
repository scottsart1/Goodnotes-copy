package com.inkwell.notes.ink.recognize

import com.inkwell.notes.ink.geometry.Bounds
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Polyline helpers for gesture and shape recognition. Paths are packed x,y pairs in page points.
 * Pure Kotlin.
 */
object PathOps {
    fun count(xy: FloatArray): Int = xy.size / 2

    fun length(xy: FloatArray, closed: Boolean = false): Float {
        val n = count(xy)
        var len = 0f
        for (i in 1 until n) len += dist(xy, i - 1, i)
        if (closed && n > 1) len += dist(xy, n - 1, 0)
        return len
    }

    fun bounds(xy: FloatArray, out: Bounds = Bounds()): Bounds {
        out.setEmpty()
        var i = 0
        while (i + 1 < xy.size) { out.union(xy[i], xy[i + 1]); i += 2 }
        return out
    }

    fun dist(xy: FloatArray, i: Int, j: Int): Float {
        val dx = xy[j * 2] - xy[i * 2]; val dy = xy[j * 2 + 1] - xy[i * 2 + 1]
        return sqrt(dx * dx + dy * dy)
    }

    /** Extracts x,y pairs from samples of [stride] floats. */
    fun xyOf(points: FloatArray, stride: Int, count: Int = points.size / stride): FloatArray {
        val out = FloatArray(count * 2)
        for (i in 0 until count) { out[i * 2] = points[i * stride]; out[i * 2 + 1] = points[i * stride + 1] }
        return out
    }

    /** Drops consecutive points closer than [minDist]. */
    fun dedupe(xy: FloatArray, minDist: Float): FloatArray {
        val n = count(xy)
        if (n == 0) return xy
        val out = FloatArray(xy.size)
        out[0] = xy[0]; out[1] = xy[1]
        var k = 1
        for (i in 1 until n) {
            val dx = xy[i * 2] - out[(k - 1) * 2]; val dy = xy[i * 2 + 1] - out[(k - 1) * 2 + 1]
            if (dx * dx + dy * dy >= minDist * minDist || i == n - 1) {
                out[k * 2] = xy[i * 2]; out[k * 2 + 1] = xy[i * 2 + 1]; k++
            }
        }
        return out.copyOf(k * 2)
    }

    /**
     * Resamples the path into [n] points equally spaced along its arc length (the first and
     * last points are kept). With [closed] the closing segment is included and the result does
     * not repeat the first point.
     */
    fun resample(xy: FloatArray, n: Int, closed: Boolean = false): FloatArray {
        val m = count(xy)
        if (m == 0 || n < 2) return FloatArray(0)
        val src = if (closed && m > 1) xy.copyOf(xy.size + 2).also { it[xy.size] = xy[0]; it[xy.size + 1] = xy[1] } else xy
        val sm = count(src)
        val total = length(src)
        val out = FloatArray(n * 2)
        if (total <= 0f || sm < 2) {
            for (i in 0 until n) { out[i * 2] = xy[0]; out[i * 2 + 1] = xy[1] }
            return out
        }
        val segments = if (closed) n else n - 1
        val step = total / segments
        out[0] = src[0]; out[1] = src[1]
        var k = 1
        var acc = 0f
        var target = step
        for (i in 1 until sm) {
            val ax = src[(i - 1) * 2]; val ay = src[(i - 1) * 2 + 1]
            val bx = src[i * 2]; val by = src[i * 2 + 1]
            val seg = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            while (seg > 0f && acc + seg >= target && k < n) {
                val t = (target - acc) / seg
                out[k * 2] = ax + (bx - ax) * t; out[k * 2 + 1] = ay + (by - ay) * t
                k++
                target += step
            }
            acc += seg
        }
        while (k < n) {
            // Rounding: fill with the end point.
            val last = if (closed) 0 else sm - 1
            out[k * 2] = src[last * 2]; out[k * 2 + 1] = src[last * 2 + 1]
            k++
        }
        return out
    }

    /**
     * Principal axis of the points: returns [cx, cy, ux, uy, varianceAlong, varianceAcross]
     * where (ux, uy) is the unit direction of greatest spread.
     */
    fun principalAxis(xy: FloatArray): FloatArray {
        val n = count(xy)
        if (n == 0) return floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f)
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += xy[i * 2]; my += xy[i * 2 + 1] }
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val dx = xy[i * 2] - mx; val dy = xy[i * 2 + 1] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= n; syy /= n; sxy /= n
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val ux = kotlin.math.cos(theta); val uy = kotlin.math.sin(theta)
        val along = sxx * ux * ux + 2 * sxy * ux * uy + syy * uy * uy
        val across = sxx * uy * uy - 2 * sxy * ux * uy + syy * ux * ux
        return floatArrayOf(mx.toFloat(), my.toFloat(), ux.toFloat(), uy.toFloat(), along.toFloat(), across.toFloat())
    }

    /** Signed turning angle (radians) at every interior vertex; index i is the turn at point i. */
    fun turning(xy: FloatArray, closed: Boolean): FloatArray {
        val n = count(xy)
        val out = FloatArray(n)
        if (n < 3) return out
        for (i in 0 until n) {
            val prev = if (i == 0) { if (!closed) continue; n - 1 } else i - 1
            val next = if (i == n - 1) { if (!closed) continue; 0 } else i + 1
            val ax = xy[i * 2] - xy[prev * 2]; val ay = xy[i * 2 + 1] - xy[prev * 2 + 1]
            val bx = xy[next * 2] - xy[i * 2]; val by = xy[next * 2 + 1] - xy[i * 2 + 1]
            if ((ax == 0f && ay == 0f) || (bx == 0f && by == 0f)) continue
            out[i] = atan2(ax * by - ay * bx, ax * bx + ay * by)
        }
        return out
    }

    /** Shoelace area (absolute) of the polygon formed by the points. */
    fun polygonArea(xy: FloatArray): Float {
        val n = count(xy)
        if (n < 3) return 0f
        var a = 0.0
        var j = n - 1
        for (i in 0 until n) {
            a += xy[j * 2].toDouble() * xy[i * 2 + 1] - xy[i * 2].toDouble() * xy[j * 2 + 1]
            j = i
        }
        return abs(a * 0.5).toFloat()
    }

    /** Convex hull (counter-clockwise in y-up terms, Andrew's monotone chain) as x,y pairs. */
    fun convexHull(xy: FloatArray): FloatArray {
        val n = count(xy)
        if (n < 3) return xy.copyOf()
        val idx = (0 until n).sortedWith(compareBy<Int>({ xy[it * 2] }, { xy[it * 2 + 1] }))
        val hull = IntArray(2 * n)
        var k = 0
        fun cross(o: Int, a: Int, b: Int): Float =
            (xy[a * 2] - xy[o * 2]) * (xy[b * 2 + 1] - xy[o * 2 + 1]) - (xy[a * 2 + 1] - xy[o * 2 + 1]) * (xy[b * 2] - xy[o * 2])
        for (i in idx) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], i) <= 0f) k--
            hull[k++] = i
        }
        val lower = k + 1
        for (j in idx.indices.reversed()) {
            val i = idx[j]
            while (k >= lower && cross(hull[k - 2], hull[k - 1], i) <= 0f) k--
            hull[k++] = i
        }
        val m = max(0, k - 1)
        val out = FloatArray(m * 2)
        for (i in 0 until m) { out[i * 2] = xy[hull[i] * 2]; out[i * 2 + 1] = xy[hull[i] * 2 + 1] }
        return out
    }

    /** Distance from (px, py) to segment a-b. */
    fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-12f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val qx = ax + dx * t - px; val qy = ay + dy * t - py
        return sqrt(qx * qx + qy * qy)
    }

    /**
     * Douglas-Peucker simplification of an open polyline. Returns the indices of the kept points
     * (always including the first and last).
     */
    fun simplify(xy: FloatArray, epsilon: Float): IntArray {
        val n = count(xy)
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var best = -1; var bestD = epsilon
            for (i in s + 1 until e) {
                val d = distToSegment(xy[i * 2], xy[i * 2 + 1], xy[s * 2], xy[s * 2 + 1], xy[e * 2], xy[e * 2 + 1])
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) {
                keep[best] = true
                stack.addLast(s to best); stack.addLast(best to e)
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    /**
     * Total-least-squares line through points [from, to] (indices, inclusive, wrapping modulo the
     * point count): returns [cx, cy, ux, uy].
     */
    fun fitLine(xy: FloatArray, from: Int, to: Int): FloatArray {
        val n = count(xy)
        val len = ((to - from) % n + n) % n + 1
        val sub = FloatArray(len * 2)
        for (k in 0 until len) {
            val i = (from + k) % n
            sub[k * 2] = xy[i * 2]; sub[k * 2 + 1] = xy[i * 2 + 1]
        }
        val pa = principalAxis(sub)
        return floatArrayOf(pa[0], pa[1], pa[2], pa[3])
    }

    /** Intersection of two lines given as [cx, cy, ux, uy]; null when (nearly) parallel. */
    fun intersect(l1: FloatArray, l2: FloatArray): FloatArray? {
        val denom = l1[2] * l2[3] - l1[3] * l2[2]
        if (abs(denom) < 1e-4f) return null
        val dx = l2[0] - l1[0]; val dy = l2[1] - l1[1]
        val t = (dx * l2[3] - dy * l2[2]) / denom
        return floatArrayOf(l1[0] + l1[2] * t, l1[1] + l1[3] * t)
    }
}
