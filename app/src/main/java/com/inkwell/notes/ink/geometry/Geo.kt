package com.inkwell.notes.ink.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure 2D geometry used by erasers, hit testing and the lasso. All functions are allocation free. */
object Geo {
    const val EPS = 1e-6f

    fun distSq(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        return dx * dx + dy * dy
    }

    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = sqrt(distSq(ax, ay, bx, by))

    /** Parameter t in [0,1] of the point of segment AB closest to P. */
    fun closestParam(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < EPS) return 0f
        return (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    }

    /** Squared distance from P to segment AB. */
    fun distSqPointSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val t = closestParam(px, py, ax, ay, bx, by)
        return distSq(px, py, ax + (bx - ax) * t, ay + (by - ay) * t)
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float): Float = ax * by - ay * bx

    /**
     * Intersection of segments AB and CD. Returns the parameter t on AB (0..1) of the crossing,
     * or NaN when they do not cross. Collinear overlaps return NaN (treated as touching, not crossing).
     */
    fun segmentIntersection(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        val rx = bx - ax; val ry = by - ay
        val sx = dx - cx; val sy = dy - cy
        val denom = cross(rx, ry, sx, sy)
        if (abs(denom) < 1e-9f) return Float.NaN
        val qpx = cx - ax; val qpy = cy - ay
        val t = cross(qpx, qpy, sx, sy) / denom
        val u = cross(qpx, qpy, rx, ry) / denom
        return if (t in 0f..1f && u in 0f..1f) t else Float.NaN
    }

    /** Squared minimum distance between segments AB and CD. */
    fun segmentDistanceSq(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        if (!segmentIntersection(ax, ay, bx, by, cx, cy, dx, dy).isNaN()) return 0f
        return min(
            min(distSqPointSegment(ax, ay, cx, cy, dx, dy), distSqPointSegment(bx, by, cx, cy, dx, dy)),
            min(distSqPointSegment(cx, cy, ax, ay, bx, by), distSqPointSegment(dx, dy, ax, ay, bx, by)),
        )
    }

    /**
     * Parameter interval of segment AB lying within distance [r] of segment CD (a capsule).
     * Writes [t0, t1] into [out] and returns true when the interval is non-empty. Because the
     * capsule is convex the result is always a single interval. With C == D this is the
     * classic segment/circle intersection.
     */
    fun capsuleInterval(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
        r: Float, out: FloatArray,
    ): Boolean {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        // Disc around C.
        if (discInterval(ax, ay, bx, by, cx, cy, r, out)) { lo = min(lo, out[0]); hi = max(hi, out[1]) }
        // Disc around D.
        if (discInterval(ax, ay, bx, by, dx, dy, r, out)) { lo = min(lo, out[0]); hi = max(hi, out[1]) }
        // Rectangle around CD.
        val ex = dx - cx; val ey = dy - cy
        val len = sqrt(ex * ex + ey * ey)
        if (len > EPS) {
            val ux = ex / len; val uy = ey / len
            // s(t) = (P(t)-C)·u in [0, len];  w(t) = (P(t)-C)·n in [-r, r]
            val s0 = (ax - cx) * ux + (ay - cy) * uy
            val s1 = (bx - ax) * ux + (by - ay) * uy
            val w0 = (ax - cx) * -uy + (ay - cy) * ux
            val w1 = (bx - ax) * -uy + (by - ay) * ux
            var a = 0f; var b = 1f
            var ok = true
            // 0 <= s0 + t s1 <= len
            if (abs(s1) < EPS) { if (s0 < 0f || s0 > len) ok = false } else {
                var ta = (0f - s0) / s1; var tb = (len - s0) / s1
                if (ta > tb) { val tmp = ta; ta = tb; tb = tmp }
                a = max(a, ta); b = min(b, tb)
            }
            // -r <= w0 + t w1 <= r
            if (ok) {
                if (abs(w1) < EPS) { if (w0 < -r || w0 > r) ok = false } else {
                    var ta = (-r - w0) / w1; var tb = (r - w0) / w1
                    if (ta > tb) { val tmp = ta; ta = tb; tb = tmp }
                    a = max(a, ta); b = min(b, tb)
                }
            }
            if (ok && a <= b) { lo = min(lo, a); hi = max(hi, b) }
        }
        lo = max(lo, 0f); hi = min(hi, 1f)
        if (lo > hi) return false
        out[0] = lo; out[1] = hi
        return true
    }

    /** Parameter interval of AB inside the disc (cx, cy, r), clamped to [0,1]. */
    fun discInterval(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, r: Float, out: FloatArray): Boolean {
        val dx = bx - ax; val dy = by - ay
        val fx = ax - cx; val fy = ay - cy
        val a = dx * dx + dy * dy
        val c = fx * fx + fy * fy - r * r
        if (a < EPS) {
            if (c <= 0f) { out[0] = 0f; out[1] = 1f; return true }
            return false
        }
        val b = 2f * (fx * dx + fy * dy)
        val disc = b * b - 4f * a * c
        if (disc < 0f) return false
        val sq = sqrt(disc)
        val t0 = (-b - sq) / (2f * a)
        val t1 = (-b + sq) / (2f * a)
        val lo = max(t0, 0f); val hi = min(t1, 1f)
        if (lo > hi) return false
        out[0] = lo; out[1] = hi
        return true
    }

    /** Even-odd point in polygon test; [poly] packs x,y pairs, [n] = vertex count. */
    fun pointInPolygon(x: Float, y: Float, poly: FloatArray, n: Int = poly.size / 2): Boolean {
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y)) {
                val xCross = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Signed area (shoelace); positive for clockwise polygons in y-down coordinates. */
    fun polygonArea(poly: FloatArray, n: Int = poly.size / 2): Float {
        var a = 0f
        var j = n - 1
        for (i in 0 until n) {
            a += poly[j * 2] * poly[i * 2 + 1] - poly[i * 2] * poly[j * 2 + 1]
            j = i
        }
        return a * 0.5f
    }

    /** Length of a polyline with the given float [stride] (x, y at offsets 0 and 1). */
    fun polylineLength(points: FloatArray, stride: Int, count: Int = points.size / stride): Float {
        var len = 0f
        for (i in 1 until count) len += dist(points[(i - 1) * stride], points[(i - 1) * stride + 1], points[i * stride], points[i * stride + 1])
        return len
    }

    /** Bounds of a polyline into [out]. */
    fun polylineBounds(points: FloatArray, stride: Int, out: Bounds, count: Int = points.size / stride): Bounds {
        out.setEmpty()
        for (i in 0 until count) out.union(points[i * stride], points[i * stride + 1])
        return out
    }

    /** Minimum squared distance from P to a polyline. */
    fun distSqPointPolyline(px: Float, py: Float, points: FloatArray, stride: Int, count: Int = points.size / stride): Float {
        if (count == 0) return Float.POSITIVE_INFINITY
        if (count == 1) return distSq(px, py, points[0], points[1])
        var best = Float.POSITIVE_INFINITY
        for (i in 1 until count) {
            val d = distSqPointSegment(px, py, points[(i - 1) * stride], points[(i - 1) * stride + 1], points[i * stride], points[i * stride + 1])
            if (d < best) best = d
        }
        return best
    }

    /** Minimum squared distance between segment AB and a polyline. */
    fun distSqSegmentPolyline(ax: Float, ay: Float, bx: Float, by: Float, points: FloatArray, stride: Int, count: Int = points.size / stride): Float {
        if (count == 0) return Float.POSITIVE_INFINITY
        if (count == 1) return distSqPointSegment(points[0], points[1], ax, ay, bx, by)
        var best = Float.POSITIVE_INFINITY
        for (i in 1 until count) {
            val d = segmentDistanceSq(ax, ay, bx, by, points[(i - 1) * stride], points[(i - 1) * stride + 1], points[i * stride], points[i * stride + 1])
            if (d < best) { best = d; if (best == 0f) return 0f }
        }
        return best
    }

    /** Turning angle (radians, 0..PI) at B for the path A -> B -> C. */
    fun turnAngle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        val ux = bx - ax; val uy = by - ay
        val vx = cx - bx; val vy = cy - by
        val lu = sqrt(ux * ux + uy * uy); val lv = sqrt(vx * vx + vy * vy)
        if (lu < EPS || lv < EPS) return 0f
        val cos = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f)
        return acos(cos)
    }

    /** True when the segment AB touches the rectangle (inclusive). */
    fun segmentIntersectsRect(ax: Float, ay: Float, bx: Float, by: Float, r: Bounds): Boolean {
        if (r.contains(ax, ay) || r.contains(bx, by)) return true
        if (max(ax, bx) < r.left || min(ax, bx) > r.right || max(ay, by) < r.top || min(ay, by) > r.bottom) return false
        return !segmentIntersection(ax, ay, bx, by, r.left, r.top, r.right, r.top).isNaN() ||
            !segmentIntersection(ax, ay, bx, by, r.right, r.top, r.right, r.bottom).isNaN() ||
            !segmentIntersection(ax, ay, bx, by, r.right, r.bottom, r.left, r.bottom).isNaN() ||
            !segmentIntersection(ax, ay, bx, by, r.left, r.bottom, r.left, r.top).isNaN()
    }

    /** Squared distance from P to the rectangle (0 inside). */
    fun distSqPointRect(px: Float, py: Float, r: Bounds): Float {
        val dx = max(max(r.left - px, 0f), px - r.right)
        val dy = max(max(r.top - py, 0f), py - r.bottom)
        return dx * dx + dy * dy
    }

    fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
