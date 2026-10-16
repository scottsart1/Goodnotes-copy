package com.inkwell.notes.ink.render

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.ink.geometry.Geo
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Computes the filled outline of a variable-width stroke: per-sample radius (pressure, speed,
 * direction, tilt, start taper) and left/right offset curves, split into separate "runs" at
 * sharp corners so the outline never folds over itself. Every run is emitted with the same
 * orientation, so a non-zero fill unions them without double blending (translucent pens stay
 * even where a stroke overlaps itself).
 *
 * The same instance type is used for the live stroke and the committed renderer; one instance
 * per thread (it keeps scratch buffers). Pure Kotlin, unit tested.
 */
class StrokeGeometry {
    var count = 0
        private set
    var xs = FloatArray(64); private set
    var ys = FloatArray(64); private set
    var rs = FloatArray(64); private set
    var lx = FloatArray(64); private set
    var ly = FloatArray(64); private set
    var rx = FloatArray(64); private set
    var ry = FloatArray(64); private set
    private var ps = FloatArray(64)
    private var ts = FloatArray(64)
    private var tilts = FloatArray(64)
    private var arc = FloatArray(64)

    /** Run boundaries: run k spans samples [runStart[k], runEnd[k]] inclusive. */
    var runCount = 0
        private set
    var runStart = IntArray(8); private set
    var runEnd = IntArray(8); private set

    /** True when the stroke is a single dot (draw a circle/square at sample 0 with radius [dotRadius]). */
    var isDot = false
        private set
    var dotRadius = 0f
        private set
    var flatCaps = false
        private set

    /**
     * Computes the outline for [count] samples of [points] (InkPoints layout) followed by
     * [extraCount] samples of [extra] (e.g. predicted samples, never committed).
     */
    fun compute(
        points: FloatArray, count: Int,
        extra: FloatArray?, extraCount: Int,
        pen: PenType, width: Float, sensitivity: Float,
    ) {
        val st = InkPoints.STRIDE
        val total = count + extraCount
        ensure(total)
        flatCaps = pen == PenType.HIGHLIGHTER
        isDot = false
        runCount = 0
        // 1. Gather samples, dropping near-duplicates and anything non-finite (a NaN sample would
        //    otherwise poison every offset of the outline and the whole stroke would vanish).
        val minDist = max(0.01f, width * 0.02f)
        val minDist2 = minDist * minDist
        var n = 0
        for (k in 0 until total) {
            val src = if (k < count) points else extra!!
            val o = (if (k < count) k else k - count) * st
            val x = src[o]; val y = src[o + 1]
            if (!x.isFinite() || !y.isFinite()) continue
            val pressure = src[o + InkPoints.PRESSURE].let { if (it.isFinite()) it else 0.5f }
            if (n > 0 && Geo.distSq(xs[n - 1], ys[n - 1], x, y) < minDist2) {
                // Keep the newest pressure so the tip follows a lifting pen.
                if (k == total - 1) { ps[n - 1] = pressure }
                continue
            }
            xs[n] = x; ys[n] = y
            ps[n] = pressure
            tilts[n] = src[o + InkPoints.TILT].let { if (it.isFinite()) it else 0f }
            ts[n] = src[o + InkPoints.TIME].let { if (it.isFinite()) it else if (n > 0) ts[n - 1] else 0f }
            n++
        }
        this.count = n
        if (n == 0) return
        // 2. Arc length.
        arc[0] = 0f
        for (i in 1 until n) arc[i] = arc[i - 1] + Geo.dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
        // 3. Radii.
        var maxR = 0f
        var lastSpeed = 0f
        for (i in 0 until n) {
            val j = max(0, i - 3)
            val dt = ts[i] - ts[j]
            val speed = if (i > j && dt > 0.5f) (arc[i] - arc[j]) / dt else lastSpeed
            lastSpeed = speed
            val p = 0.25f * ps[max(0, i - 1)] + 0.5f * ps[i] + 0.25f * ps[minOf(n - 1, i + 1)]
            var dirX = 0f; var dirY = 0f
            if (pen == PenType.FOUNTAIN && n > 1) {
                val a = max(0, i - 1); val b = minOf(n - 1, i + 1)
                val ddx = xs[b] - xs[a]; val ddy = ys[b] - ys[a]
                val len = sqrt(ddx * ddx + ddy * ddy)
                if (len > 1e-4f) { dirX = ddx / len; dirY = ddy / len }
            }
            val f = PenModel.widthFactor(pen, p, sensitivity, speed, tilts[i], dirX, dirY, arc[i], width)
            val r = max(PenModel.MIN_RADIUS, width * f * 0.5f)
            rs[i] = r
            if (r > maxR) maxR = r
        }
        if (n == 1 || arc[n - 1] < width * 0.25f) {
            isDot = true
            dotRadius = maxR
            return
        }
        // 4. Runs split at sharp corners.
        var start = 0
        var turned = 0f
        for (i in 1 until n - 1) {
            val ang = Geo.turnAngle(xs[i - 1], ys[i - 1], xs[i], ys[i], xs[i + 1], ys[i + 1])
            turned += ang
            if (ang > SPLIT_ANGLE || turned > SPLIT_ACCUMULATED) {
                addRun(start, i)
                start = i
                turned = 0f
            }
        }
        addRun(start, n - 1)
        // 5. Offsets per run (tangents one-sided at run ends, so corners stay crisp).
        for (k in 0 until runCount) {
            val a = runStart[k]; val b = runEnd[k]
            for (i in a..b) {
                val i0 = if (i == a) a else i - 1
                val i1 = if (i == b) b else i + 1
                var tx = xs[i1] - xs[i0]; var ty = ys[i1] - ys[i0]
                var len = sqrt(tx * tx + ty * ty)
                if (len < 1e-5f) {
                    // Degenerate tangent: fall back to the run's overall direction.
                    tx = xs[b] - xs[a]; ty = ys[b] - ys[a]; len = sqrt(tx * tx + ty * ty)
                    if (len < 1e-5f) { tx = 1f; ty = 0f; len = 1f }
                }
                val nx = -ty / len * rs[i]; val ny = tx / len * rs[i]
                // Offsets are stored per run slot: index (k, i) maps to i + k so junction samples don't collide.
                val slot = i + k
                lx[slot] = xs[i] + nx; ly[slot] = ys[i] + ny
                rx[slot] = xs[i] - nx; ry[slot] = ys[i] - ny
            }
        }
    }

    /** Offset arrays are indexed by `sample + run` (see [compute]). */
    fun slot(run: Int, sample: Int): Int = sample + run

    /** Largest radius of the current outline. */
    fun maxRadius(): Float {
        var m = 0f
        for (i in 0 until count) if (rs[i] > m) m = rs[i]
        return if (isDot) dotRadius else m
    }

    private fun addRun(a: Int, b: Int) {
        if (b <= a) return
        if (runCount == runStart.size) { runStart = runStart.copyOf(runCount * 2); runEnd = runEnd.copyOf(runCount * 2) }
        runStart[runCount] = a; runEnd[runCount] = b; runCount++
    }

    private fun ensure(n: Int) {
        val need = n + 8
        if (xs.size >= need && lx.size >= need * 2) return
        val cap = max(need, xs.size * 2)
        xs = xs.copyOf(cap); ys = ys.copyOf(cap); rs = rs.copyOf(cap); ps = ps.copyOf(cap)
        ts = ts.copyOf(cap); tilts = tilts.copyOf(cap); arc = arc.copyOf(cap)
        // A sample can appear in two runs (junction), so offsets need up to 2x slots.
        lx = FloatArray(cap * 2); ly = FloatArray(cap * 2); rx = FloatArray(cap * 2); ry = FloatArray(cap * 2)
    }

    companion object {
        /** Local turn (radians) that splits the outline into a new run (~50 degrees). */
        const val SPLIT_ANGLE = 0.87f
        /** Accumulated turn within one run before splitting (~120 degrees). */
        const val SPLIT_ACCUMULATED = 2.1f
    }
}
