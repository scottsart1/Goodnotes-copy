package com.inkwell.notes.ink.geometry

import kotlin.math.PI

/**
 * Segment eraser geometry: removes the part of a polyline between its crossings with other
 * polylines (or itself). When it crosses nothing, sharp corners act as the cut points, so a
 * hand-drawn box loses one side. Pure Kotlin.
 */
object SegmentEraser {
    /** Turning angle (radians) above which a vertex counts as a corner. */
    const val CORNER_ANGLE = (PI / 3.0).toFloat()

    /**
     * Arc lengths along [target] where it crosses any of [others] or itself, sorted ascending.
     * Every polyline uses the same [stride].
     */
    fun crossings(target: FloatArray, stride: Int, others: List<FloatArray>, includeSelf: Boolean = true): FloatArray {
        val n = target.size / stride
        if (n < 2) return FloatArray(0)
        val arc = StrokeSplitter.arcLengths(target, stride)
        val out = FloatList(16)
        val tb = Geo.polylineBounds(target, stride, Bounds())
        val ob = Bounds()
        val segB = Bounds()
        for (other in others) {
            val m = other.size / stride
            if (m < 2) continue
            Geo.polylineBounds(other, stride, ob)
            if (!tb.intersects(ob)) continue
            for (i in 0 until n - 1) {
                val ax = target[i * stride]; val ay = target[i * stride + 1]
                val bx = target[(i + 1) * stride]; val by = target[(i + 1) * stride + 1]
                segB.setPoints(ax, ay, bx, by)
                if (!segB.intersects(ob)) continue
                for (j in 0 until m - 1) {
                    val cx = other[j * stride]; val cy = other[j * stride + 1]
                    val dx = other[(j + 1) * stride]; val dy = other[(j + 1) * stride + 1]
                    if (maxOf(cx, dx) < segB.left || minOf(cx, dx) > segB.right || maxOf(cy, dy) < segB.top || minOf(cy, dy) > segB.bottom) continue
                    val t = Geo.segmentIntersection(ax, ay, bx, by, cx, cy, dx, dy)
                    if (!t.isNaN()) out.add(arc[i] + (arc[i + 1] - arc[i]) * t)
                }
            }
        }
        if (includeSelf) {
            for (i in 0 until n - 1) {
                val ax = target[i * stride]; val ay = target[i * stride + 1]
                val bx = target[(i + 1) * stride]; val by = target[(i + 1) * stride + 1]
                segB.setPoints(ax, ay, bx, by)
                for (j in i + 2 until n - 1) {
                    val cx = target[j * stride]; val cy = target[j * stride + 1]
                    val dx = target[(j + 1) * stride]; val dy = target[(j + 1) * stride + 1]
                    if (maxOf(cx, dx) < segB.left || minOf(cx, dx) > segB.right || maxOf(cy, dy) < segB.top || minOf(cy, dy) > segB.bottom) continue
                    val t = Geo.segmentIntersection(ax, ay, bx, by, cx, cy, dx, dy)
                    if (!t.isNaN()) {
                        val u = Geo.segmentIntersection(cx, cy, dx, dy, ax, ay, bx, by)
                        out.add(arc[i] + (arc[i + 1] - arc[i]) * t)
                        if (!u.isNaN()) out.add(arc[j] + (arc[j + 1] - arc[j]) * u)
                    }
                }
            }
        }
        val arr = out.toArray()
        arr.sort()
        return dedupe(arr, 1e-3f)
    }

    /** Collapses sorted values closer than [eps] (crossings found twice at shared sample points). */
    fun dedupe(sorted: FloatArray, eps: Float): FloatArray {
        if (sorted.size < 2) return sorted
        val out = FloatList(sorted.size)
        out.add(sorted[0])
        for (i in 1 until sorted.size) if (sorted[i] - out[out.size - 1] > eps) out.add(sorted[i])
        return out.toArray()
    }

    /**
     * Arc lengths of sharp corners. Very short segments (below [minSegment]) are merged first so
     * sampling jitter does not create false corners.
     */
    fun corners(target: FloatArray, stride: Int, minSegment: Float, angle: Float = CORNER_ANGLE): FloatArray {
        val n = target.size / stride
        if (n < 3) return FloatArray(0)
        val arc = StrokeSplitter.arcLengths(target, stride)
        // Resample: keep vertices at least minSegment apart.
        val keep = IntArray(n)
        var k = 0
        keep[k++] = 0
        for (i in 1 until n - 1) {
            val last = keep[k - 1]
            if (arc[i] - arc[last] >= minSegment) keep[k++] = i
        }
        if (arc[n - 1] - arc[keep[k - 1]] >= minSegment || k == 1) keep[k++] = n - 1 else keep[k - 1] = n - 1
        val out = FloatList(8)
        for (q in 1 until k - 1) {
            val a = keep[q - 1]; val b = keep[q]; val c = keep[q + 1]
            val ang = Geo.turnAngle(
                target[a * stride], target[a * stride + 1], target[b * stride], target[b * stride + 1],
                target[c * stride], target[c * stride + 1],
            )
            if (ang >= angle) out.add(arc[b])
        }
        return out.toArray()
    }

    /**
     * Removes the span of [target] around arc length [touch] bounded by the nearest [cuts]
     * (sorted). Returns the remaining pieces (0..2). With no cuts the whole polyline goes.
     */
    fun removeSpan(target: FloatArray, stride: Int, touch: Float, cuts: FloatArray, minPieceLength: Float): List<FloatArray> {
        val n = target.size / stride
        if (n < 2) return emptyList()
        val total = StrokeSplitter.arcLengths(target, stride)[n - 1]
        var lo = 0f
        var hi = total
        // Ignore cuts at the very ends; they do not split anything.
        val eps = 1e-3f
        for (c in cuts) {
            if (c <= eps || c >= total - eps) continue
            if (c <= touch && c > lo) lo = c
            if (c > touch && c < hi) hi = c
        }
        val out = ArrayList<FloatArray>(2)
        if (lo > minPieceLength) StrokeSplitter.subPolyline(target, stride, 0f, lo)?.let { out += it }
        if (total - hi > minPieceLength) StrokeSplitter.subPolyline(target, stride, hi, total)?.let { out += it }
        return out
    }
}
