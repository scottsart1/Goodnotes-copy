package com.inkwell.notes.ink.geometry

/**
 * Precision eraser geometry: removes the parts of a sampled polyline (stride floats per sample,
 * x and y first, every other channel interpolated linearly) lying within a swept eraser capsule.
 */
object StrokeSplitter {

    /**
     * @param points packed samples
     * @param stride floats per sample (>= 2)
     * @param ex0 eraser segment start
     * @param r eraser reach (radius) in the same units
     * @param minPieceLength pieces shorter than this are dropped (no stray specks)
     * @return null when the polyline is untouched, otherwise the remaining pieces (possibly empty)
     */
    fun erase(
        points: FloatArray, stride: Int,
        ex0: Float, ey0: Float, ex1: Float, ey1: Float,
        r: Float, minPieceLength: Float = 0f,
    ): List<FloatArray>? {
        val n = points.size / stride
        if (n == 0) return null
        if (n == 1) {
            val inside = Geo.distSqPointSegment(points[0], points[1], ex0, ey0, ex1, ey1) <= r * r
            return if (inside) emptyList() else null
        }
        // Quick reject on bounds.
        val b = Geo.polylineBounds(points, stride, Bounds())
        val eb = Bounds().setPoints(ex0, ey0, ex1, ey1).outset(r)
        if (!b.intersects(eb)) return null

        val pieces = ArrayList<FloatArray>()
        val cur = FloatList(stride * 16)
        var open = false
        var touched = false
        val iv = FloatArray(2)
        val tmp = FloatArray(stride)

        val p0Inside = Geo.distSqPointSegment(points[0], points[1], ex0, ey0, ex1, ey1) <= r * r
        if (!p0Inside) { open = true; cur.addSample(points, 0, stride) } else touched = true

        for (i in 0 until n - 1) {
            val ax = points[i * stride]; val ay = points[i * stride + 1]
            val bx = points[(i + 1) * stride]; val by = points[(i + 1) * stride + 1]
            // A tangential graze (zero-length interval inside the segment) removes nothing.
            val hit = Geo.capsuleInterval(ax, ay, bx, by, ex0, ey0, ex1, ey1, r, iv) &&
                !(iv[1] - iv[0] < 1e-4f && iv[0] > 0f && iv[1] < 1f)
            if (!hit) {
                if (!open) { open = true; cur.addSample(points, i, stride) }
                cur.addSample(points, i + 1, stride)
                continue
            }
            touched = true
            val t0 = iv[0]; val t1 = iv[1]
            if (t0 > 0f && open) {
                interpolate(points, i, stride, t0, tmp)
                cur.addRaw(tmp)
                emit(cur, stride, minPieceLength, pieces)
                open = false
            } else if (open) {
                emit(cur, stride, minPieceLength, pieces)
                open = false
            }
            if (t1 < 1f) {
                open = true
                interpolate(points, i, stride, t1, tmp)
                cur.addRaw(tmp)
                cur.addSample(points, i + 1, stride)
            }
        }
        if (open) emit(cur, stride, minPieceLength, pieces)
        return if (touched) pieces else null
    }

    private fun emit(cur: FloatList, stride: Int, minLen: Float, out: MutableList<FloatArray>) {
        val arr = cur.toArray()
        cur.clear()
        val count = arr.size / stride
        if (count < 2) return
        if (Geo.polylineLength(arr, stride, count) < minLen) return
        out += arr
    }

    /** Linear interpolation of every channel between sample i and i+1. */
    fun interpolate(points: FloatArray, i: Int, stride: Int, t: Float, out: FloatArray) {
        val a = i * stride; val b = (i + 1) * stride
        for (k in 0 until stride) out[k] = points[a + k] + (points[b + k] - points[a + k]) * t
    }

    /**
     * Extracts the sub-polyline between arc lengths [s0] and [s1] (s0 < s1), interpolating the
     * end samples. Returns null when the span is degenerate.
     */
    fun subPolyline(points: FloatArray, stride: Int, s0: Float, s1: Float): FloatArray? {
        val n = points.size / stride
        if (n < 2 || s1 <= s0) return null
        val out = FloatList(stride * 16)
        val tmp = FloatArray(stride)
        var acc = 0f
        var started = false
        for (i in 0 until n - 1) {
            val segLen = Geo.dist(points[i * stride], points[i * stride + 1], points[(i + 1) * stride], points[(i + 1) * stride + 1])
            val segStart = acc
            val segEnd = acc + segLen
            if (!started && s0 <= segEnd) {
                val t = if (segLen > 0f) ((s0 - segStart) / segLen).coerceIn(0f, 1f) else 0f
                interpolate(points, i, stride, t, tmp)
                out.addRaw(tmp)
                started = true
            }
            if (started) {
                if (s1 <= segEnd) {
                    val t = if (segLen > 0f) ((s1 - segStart) / segLen).coerceIn(0f, 1f) else 1f
                    interpolate(points, i, stride, t, tmp)
                    out.addRaw(tmp)
                    break
                }
                out.addSample(points, i + 1, stride)
            }
            acc = segEnd
        }
        val arr = out.toArray()
        return if (arr.size / stride >= 2) arr else null
    }

    /** Cumulative arc lengths per sample. */
    fun arcLengths(points: FloatArray, stride: Int): FloatArray {
        val n = points.size / stride
        val out = FloatArray(n)
        for (i in 1 until n) out[i] = out[i - 1] + Geo.dist(points[(i - 1) * stride], points[(i - 1) * stride + 1], points[i * stride], points[i * stride + 1])
        return out
    }
}

/** Minimal growable float buffer (avoids boxing). */
class FloatList(initialCapacity: Int = 32) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(4))
    var size = 0
        private set

    fun add(v: Float) {
        ensure(size + 1)
        data[size++] = v
    }

    fun addRaw(values: FloatArray) {
        ensure(size + values.size)
        System.arraycopy(values, 0, data, size, values.size)
        size += values.size
    }

    fun addSample(src: FloatArray, index: Int, stride: Int) {
        ensure(size + stride)
        System.arraycopy(src, index * stride, data, size, stride)
        size += stride
    }

    operator fun get(i: Int): Float = data[i]

    fun clear() { size = 0 }

    fun toArray(): FloatArray = data.copyOf(size)

    private fun ensure(cap: Int) {
        if (cap > data.size) data = data.copyOf(maxOf(cap, data.size * 2))
    }
}
