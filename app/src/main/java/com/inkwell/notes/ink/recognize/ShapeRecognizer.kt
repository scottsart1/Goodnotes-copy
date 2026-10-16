package com.inkwell.notes.ink.recognize

import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.ink.geometry.Bounds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recognises a single hand-drawn stroke as a clean shape: line, arrow, rectangle / square,
 * ellipse / circle, triangle or convex polygon (up to 8 sides). The stroke is resampled by arc
 * length; lines are fitted by total least squares, polygons by corner detection (Douglas-Peucker
 * on the closed path) refined by intersecting per-side line fits, ellipses by principal axes.
 * Each candidate is scored by its fit error relative to the shape size, and anything that fits
 * poorly (handwriting, scribbles, figure eights, spirals) is rejected. Pure Kotlin.
 */
object ShapeRecognizer {
    /**
     * A recognised shape. [points] follow the ShapeItem layout: LINE/ARROW start and end;
     * RECTANGLE/ELLIPSE two corners of the unrotated box (rotated by [rotation] degrees around
     * its centre); TRIANGLE/POLYGON vertices. [error] is the mean fit distance relative to the
     * shape's diagonal (0 = perfect).
     */
    class Result(val kind: ShapeKind, val points: FloatArray, val rotation: Float, val error: Float) {
        val isClosed: Boolean get() = kind != ShapeKind.LINE && kind != ShapeKind.ARROW && kind != ShapeKind.DOUBLE_ARROW
        override fun toString(): String = "Result($kind, rotation=$rotation, error=$error, points=${points.joinToString()})"
    }

    // Line: chord / path length and the largest deviation relative to the chord.
    private const val LINE_STRAIGHTNESS = 0.9f
    private const val LINE_MAX_DEV = 0.065f
    // Closed shapes: closing gap relative to the perimeter and fit errors relative to the diagonal.
    private const val CLOSE_GAP = 0.16f
    private const val FIT_MEAN = 0.032f
    private const val FIT_MAX = 0.11f
    /** Fit error under which a closed shape counts as confidently recognised (auto recognition). */
    const val CONFIDENT_ERROR = 0.022f

    /**
     * @param xy stroke samples as x,y pairs (page points)
     * @param minSize smallest accepted extent (page points) so letters are never converted
     * @param closedOnly only closed shapes (automatic recognition on pen-up)
     */
    fun recognize(xy: FloatArray, minSize: Float, closedOnly: Boolean = false): Result? {
        if (PathOps.count(xy) < 4) return null
        val b = PathOps.bounds(xy)
        val size = max(b.width, b.height)
        if (size < minSize || size <= 0f) return null
        val pts = PathOps.dedupe(xy, size * 0.002f)
        if (PathOps.count(pts) < 4) return null
        val len = PathOps.length(pts)
        if (len <= 0f) return null
        if (!closedOnly) {
            line(pts, len)?.let { return it }
            arrow(pts, len, b)?.let { return it }
        }
        return closed(pts, len, b)
    }

    // ------------------------------------------------------------------ Lines

    fun line(pts: FloatArray, len: Float): Result? {
        val n = PathOps.count(pts)
        val chord = PathOps.dist(pts, 0, n - 1)
        if (chord <= 0f || chord / len < LINE_STRAIGHTNESS) return null
        val r = PathOps.resample(pts, 48)
        val pa = PathOps.principalAxis(r)
        val cx = pa[0]; val cy = pa[1]; val ux = pa[2]; val uy = pa[3]
        var maxDev = 0f
        for (i in 0 until PathOps.count(r)) {
            val dx = r[i * 2] - cx; val dy = r[i * 2 + 1] - cy
            maxDev = max(maxDev, abs(dx * uy - dy * ux))
        }
        if (maxDev > LINE_MAX_DEV * chord) return null
        val t0 = (pts[0] - cx) * ux + (pts[1] - cy) * uy
        val t1 = (pts[(n - 1) * 2] - cx) * ux + (pts[(n - 1) * 2 + 1] - cy) * uy
        return Result(
            ShapeKind.LINE,
            floatArrayOf(cx + ux * t0, cy + uy * t0, cx + ux * t1, cy + uy * t1),
            0f, maxDev / chord,
        )
    }

    // ------------------------------------------------------------------ Arrows

    /** A shaft followed by a head with barbs on both sides, drawn in one stroke (head at either end). */
    fun arrow(pts: FloatArray, len: Float, b: Bounds): Result? =
        arrowFrom(pts, len, b) ?: arrowFrom(reversed(pts), len, b)

    private fun reversed(xy: FloatArray): FloatArray {
        val n = PathOps.count(xy)
        val out = FloatArray(xy.size)
        for (i in 0 until n) { out[i * 2] = xy[(n - 1 - i) * 2]; out[i * 2 + 1] = xy[(n - 1 - i) * 2 + 1] }
        return out
    }

    private fun arrowFrom(pts: FloatArray, len: Float, b: Bounds): Result? {
        val r = PathOps.resample(pts, 96)
        val diag = hypot(b.width, b.height)
        val keep = PathOps.simplify(r, 0.035f * diag)
        if (keep.size < 4) return null
        val ia = keep[0]; val it = keep[1]
        val ax = r[ia * 2]; val ay = r[ia * 2 + 1]
        var tx = r[it * 2]; var ty = r[it * 2 + 1]
        var shaft = hypot(tx - ax, ty - ay)
        if (shaft < 0.45f * len) return null
        var ux = (tx - ax) / shaft; var uy = (ty - ay) / shaft
        // The shaft itself must be straight.
        for (i in ia..it) {
            val d = abs((r[i * 2] - ax) * uy - (r[i * 2 + 1] - ay) * ux)
            if (d > 0.06f * shaft) return null
        }
        // The tip is the most forward point of the head region.
        val n = PathOps.count(r)
        var best = it
        var bestAlong = shaft
        for (i in it until n) {
            val along = (r[i * 2] - ax) * ux + (r[i * 2 + 1] - ay) * uy
            if (along > bestAlong) { bestAlong = along; best = i }
        }
        tx = r[best * 2]; ty = r[best * 2 + 1]
        shaft = hypot(tx - ax, ty - ay)
        ux = (tx - ax) / shaft; uy = (ty - ay) / shaft
        var leftPerp = 0f; var leftAlong = 0f
        var rightPerp = 0f; var rightAlong = 0f
        var headReach = 0f
        for (i in it until n) {
            val dx = r[i * 2] - tx; val dy = r[i * 2 + 1] - ty
            val reach = hypot(dx, dy)
            if (reach > 0.55f * shaft) return null
            headReach = max(headReach, reach)
            val back = -(dx * ux + dy * uy)
            val side = dx * uy - dy * ux
            if (back < 0.03f * shaft) continue
            if (side > leftPerp) { leftPerp = side; leftAlong = back }
            if (-side > rightPerp) { rightPerp = -side; rightAlong = back }
        }
        if (headReach < 0.08f * shaft) return null
        if (leftPerp < 0.06f * shaft || rightPerp < 0.06f * shaft) return null
        val leftAngle = atan2(leftPerp, leftAlong) * 180f / PI.toFloat()
        val rightAngle = atan2(rightPerp, rightAlong) * 180f / PI.toFloat()
        if (leftAngle !in 10f..78f || rightAngle !in 10f..78f) return null
        return Result(ShapeKind.ARROW, floatArrayOf(ax, ay, tx, ty), 0f, 0f)
    }

    // ------------------------------------------------------------------ Closed shapes

    private class Fit(val result: Result, val mean: Float, val max: Float, val vertices: Int)

    private fun closed(pts: FloatArray, len: Float, b: Bounds): Result? {
        val probe = PathOps.resample(pts, 128)
        val n = PathOps.count(probe)
        // Overshoot: cut where the path comes back nearest to its start.
        var cut = n - 1
        var cutD = Float.POSITIVE_INFINITY
        for (i in (n * 6) / 10 until n) {
            val d = PathOps.dist(probe, 0, i)
            if (d < cutD) { cutD = d; cut = i }
        }
        val loop = probe.copyOf((cut + 1) * 2)
        val perimeter = PathOps.length(loop) + cutD
        if (perimeter <= 0f || cutD > CLOSE_GAP * perimeter) return null
        val r = PathOps.resample(loop, 96, closed = true)
        val turns = PathOps.turning(r, closed = true)
        var total = 0f
        for (t in turns) total += t
        if (abs(total) !in (1.6f * PI.toFloat())..(2.6f * PI.toFloat())) return null
        val diag = hypot(PathOps.bounds(r).width, PathOps.bounds(r).height)
        if (diag <= 0f) return null
        val ell = ellipse(r, diag)
        val poly = polygon(r, diag)
        val ellOk = ell != null && ell.mean <= FIT_MEAN && ell.max <= FIT_MAX
        val polyOk = poly != null && poly.mean <= FIT_MEAN && poly.max <= FIT_MAX
        return when {
            polyOk && ellOk -> {
                val p = poly!!; val e = ell!!
                val preferPoly = if (p.vertices <= 4) p.mean <= e.mean else p.mean < 0.6f * e.mean
                if (preferPoly) p.result else e.result
            }
            polyOk -> poly!!.result
            ellOk -> ell!!.result
            else -> null
        }
    }

    private fun ellipse(r: FloatArray, diag: Float): Fit? {
        val n = PathOps.count(r)
        val pa = PathOps.principalAxis(r)
        val ux = pa[2]; val uy = pa[3]
        val vx = -uy; val vy = ux
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        val lx = FloatArray(n); val ly = FloatArray(n)
        for (i in 0 until n) {
            val dx = r[i * 2] - pa[0]; val dy = r[i * 2 + 1] - pa[1]
            lx[i] = dx * ux + dy * uy; ly[i] = dx * vx + dy * vy
            minX = min(minX, lx[i]); maxX = max(maxX, lx[i]); minY = min(minY, ly[i]); maxY = max(maxY, ly[i])
        }
        // Extents overstate a jittery outline slightly; use robust radii from the mean radial fit.
        var a = (maxX - minX) / 2f; var bb = (maxY - minY) / 2f
        if (a <= 1e-3f || bb <= 1e-3f) return null
        val ox = (maxX + minX) / 2f; val oy = (maxY + minY) / 2f
        // One refinement step: scale radii so the mean normalised radius is 1.
        var meanR = 0f
        for (i in 0 until n) meanR += sqrt(sq((lx[i] - ox) / a) + sq((ly[i] - oy) / bb))
        meanR /= n
        a *= meanR; bb *= meanR
        var sum = 0f; var worst = 0f
        for (i in 0 until n) {
            val qx = lx[i] - ox; val qy = ly[i] - oy
            val rr = sqrt(sq(qx / a) + sq(qy / bb))
            val q = sqrt(qx * qx + qy * qy)
            val d = if (rr > 1e-6f) q * abs(1f - 1f / rr) else max(a, bb)
            sum += d; worst = max(worst, d)
        }
        val cx = pa[0] + ox * ux + oy * vx
        val cy = pa[1] + ox * uy + oy * vy
        var theta = atan2(uy, ux) * 180f / PI.toFloat()
        // Normalise to (-90, 90] and snap near-axis ellipses upright.
        while (theta <= -90f) theta += 180f
        while (theta > 90f) theta -= 180f
        if (abs(theta) > 45f) {
            // Express with the box axes closest to the page axes.
            val t = a; a = bb; bb = t
            theta = if (theta > 0f) theta - 90f else theta + 90f
        }
        if (abs(a - bb) <= 0.1f * max(a, bb)) {
            val rad = (a + bb) / 2f; a = rad; bb = rad; theta = 0f
        } else if (abs(theta) <= 8f) theta = 0f
        val res = Result(ShapeKind.ELLIPSE, floatArrayOf(cx - a, cy - bb, cx + a, cy + bb), theta, sum / n / diag)
        return Fit(res, sum / n / diag, worst / diag, 0)
    }

    private fun polygon(r: FloatArray, diag: Float): Fit? {
        val n = PathOps.count(r)
        // Split the closed path at two far-apart points and simplify both halves.
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += r[i * 2]; cy += r[i * 2 + 1] }
        cx /= n; cy /= n
        var i0 = 0; var dBest = -1f
        for (i in 0 until n) { val d = hypot(r[i * 2] - cx, r[i * 2 + 1] - cy); if (d > dBest) { dBest = d; i0 = i } }
        var i1 = 0; dBest = -1f
        for (i in 0 until n) { val d = PathOps.dist(r, i0, i); if (d > dBest) { dBest = d; i1 = i } }
        if (i0 == i1) return null
        val eps = 0.04f * diag
        val verts = ArrayList<Int>()
        for ((s, e) in listOf(i0 to i1, i1 to i0)) {
            val count = ((e - s) % n + n) % n + 1
            val chain = FloatArray(count * 2)
            for (k in 0 until count) { val idx = (s + k) % n; chain[k * 2] = r[idx * 2]; chain[k * 2 + 1] = r[idx * 2 + 1] }
            val keep = PathOps.simplify(chain, eps)
            for (k in 0 until keep.size - 1) verts += (s + keep[k]) % n
        }
        // Merge vertices that are nearly collinear or too close.
        var changed = true
        while (changed && verts.size > 3) {
            changed = false
            val perimeter = polygonPerimeter(r, verts)
            for (k in verts.indices) {
                val p = verts[(k - 1 + verts.size) % verts.size]; val v = verts[k]; val q = verts[(k + 1) % verts.size]
                val turn = turnDegrees(r, p, v, q)
                val shortSide = min(PathOps.dist(r, p, v), PathOps.dist(r, v, q))
                if (turn < 22f || shortSide < 0.04f * perimeter) { verts.removeAt(k); changed = true; break }
            }
        }
        val k = verts.size
        if (k !in 3..8) return null
        // Refine each vertex as the intersection of the lines fitted to its two sides.
        val lines = ArrayList<FloatArray>(k)
        for (j in 0 until k) {
            val s = verts[j]; val e = verts[(j + 1) % k]
            val span = ((e - s) % n + n) % n
            val trim = (span * 0.18f).toInt()
            lines += if (span - 2 * trim >= 2) PathOps.fitLine(r, (s + trim) % n, (e - trim + n) % n)
            else {
                val dx = r[e * 2] - r[s * 2]; val dy = r[e * 2 + 1] - r[s * 2 + 1]
                val l = hypot(dx, dy).coerceAtLeast(1e-6f)
                floatArrayOf(r[s * 2], r[s * 2 + 1], dx / l, dy / l)
            }
        }
        val poly = FloatArray(k * 2)
        for (j in 0 until k) {
            val v = verts[j]
            val p = PathOps.intersect(lines[(j - 1 + k) % k], lines[j])
            if (p != null && hypot(p[0] - r[v * 2], p[1] - r[v * 2 + 1]) <= 0.2f * diag) {
                poly[j * 2] = p[0]; poly[j * 2 + 1] = p[1]
            } else {
                poly[j * 2] = r[v * 2]; poly[j * 2 + 1] = r[v * 2 + 1]
            }
        }
        if (!isConvex(poly)) return null
        val sides = FloatArray(k) { j -> PathOps.dist(poly, j, (j + 1) % k) }
        if (k >= 5 && sides.min() < 0.4f * sides.max()) return null
        if (sides.min() < 0.08f * sides.max()) return null
        // Fit error of the outline against the polygon.
        var sum = 0f; var worst = 0f
        for (i in 0 until n) {
            var d = Float.POSITIVE_INFINITY
            for (j in 0 until k) {
                val a = j; val bIdx = (j + 1) % k
                d = min(d, PathOps.distToSegment(r[i * 2], r[i * 2 + 1], poly[a * 2], poly[a * 2 + 1], poly[bIdx * 2], poly[bIdx * 2 + 1]))
            }
            sum += d; worst = max(worst, d)
        }
        val mean = sum / n / diag
        val res = when (k) {
            3 -> Result(ShapeKind.TRIANGLE, poly, 0f, mean)
            4 -> rectangle(poly)?.let { (pts, rot) -> Result(ShapeKind.RECTANGLE, pts, rot, mean) }
                ?: Result(ShapeKind.POLYGON, poly, 0f, mean)
            else -> Result(ShapeKind.POLYGON, poly, 0f, mean)
        }
        return Fit(res, mean, worst / diag, k)
    }

    /** A quadrilateral with right angles as an (optionally rotated) rectangle: corners + rotation. */
    private fun rectangle(q: FloatArray): Pair<FloatArray, Float>? {
        for (j in 0 until 4) {
            val turn = turnDegreesPts(q, (j + 3) % 4, j, (j + 1) % 4)
            if (abs(turn - 90f) > 16f) return null
        }
        // Dominant orientation: circular mean of 4x the side angles.
        var sx = 0.0; var sy = 0.0
        for (j in 0 until 4) {
            val a = atan2(q[((j + 1) % 4) * 2 + 1] - q[j * 2 + 1], q[((j + 1) % 4) * 2] - q[j * 2]).toDouble()
            val w = hypot(q[((j + 1) % 4) * 2 + 1] - q[j * 2 + 1], q[((j + 1) % 4) * 2] - q[j * 2]).toDouble()
            sx += w * kotlin.math.cos(4 * a); sy += w * kotlin.math.sin(4 * a)
        }
        var theta = (atan2(sy, sx) / 4.0).toFloat() // radians in (-45, 45]
        if (abs(theta * 180f / PI.toFloat()) <= 7f) theta = 0f
        var cx = 0f; var cy = 0f
        for (j in 0 until 4) { cx += q[j * 2]; cy += q[j * 2 + 1] }
        cx /= 4f; cy /= 4f
        val cs = cos(theta); val sn = sin(theta)
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        val xs = FloatArray(4); val ys = FloatArray(4)
        for (j in 0 until 4) {
            val dx = q[j * 2] - cx; val dy = q[j * 2 + 1] - cy
            xs[j] = dx * cs + dy * sn; ys[j] = -dx * sn + dy * cs
            minX = min(minX, xs[j]); maxX = max(maxX, xs[j]); minY = min(minY, ys[j]); maxY = max(maxY, ys[j])
        }
        // Average opposite sides rather than taking the outermost corners.
        val sortedX = xs.sorted(); val sortedY = ys.sorted()
        val l = (sortedX[0] + sortedX[1]) / 2f; val r = (sortedX[2] + sortedX[3]) / 2f
        val t = (sortedY[0] + sortedY[1]) / 2f; val b = (sortedY[2] + sortedY[3]) / 2f
        var w = r - l; var h = b - t
        if (w <= 0f || h <= 0f) return null
        val ocx = (l + r) / 2f; val ocy = (t + b) / 2f
        val ccx = cx + ocx * cs - ocy * sn; val ccy = cy + ocx * sn + ocy * cs
        if (abs(w - h) <= 0.12f * max(w, h)) { val s = (w + h) / 2f; w = s; h = s }
        val deg = theta * 180f / PI.toFloat()
        return floatArrayOf(ccx - w / 2f, ccy - h / 2f, ccx + w / 2f, ccy + h / 2f) to (if (deg == 0f) 0f else deg)
    }

    private fun polygonPerimeter(r: FloatArray, verts: List<Int>): Float {
        var p = 0f
        for (k in verts.indices) p += PathOps.dist(r, verts[k], verts[(k + 1) % verts.size])
        return p
    }

    /** Turning angle in degrees (0 = straight on) at v for the path p -> v -> q (indices into r). */
    private fun turnDegrees(r: FloatArray, p: Int, v: Int, q: Int): Float = turnDegreesPts(r, p, v, q)

    private fun turnDegreesPts(xy: FloatArray, p: Int, v: Int, q: Int): Float {
        val ax = xy[v * 2] - xy[p * 2]; val ay = xy[v * 2 + 1] - xy[p * 2 + 1]
        val bx = xy[q * 2] - xy[v * 2]; val by = xy[q * 2 + 1] - xy[v * 2 + 1]
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la < 1e-6f || lb < 1e-6f) return 0f
        return acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
    }

    private fun isConvex(poly: FloatArray): Boolean {
        val k = poly.size / 2
        var sign = 0
        for (j in 0 until k) {
            val a = j; val b = (j + 1) % k; val c = (j + 2) % k
            val cross = (poly[b * 2] - poly[a * 2]) * (poly[c * 2 + 1] - poly[b * 2 + 1]) -
                (poly[b * 2 + 1] - poly[a * 2 + 1]) * (poly[c * 2] - poly[b * 2])
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return sign != 0
    }

    private fun sq(v: Float) = v * v
}
