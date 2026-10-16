package com.inkwell.notes.ink.recognize

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Hand-drawn-looking test strokes: sampled paths with jitter and slow wobble. */
class SyntheticInk(seed: Int, private val jitter: Float = 0.8f, private val wobble: Float = 1.2f) {
    private val rnd = Random(seed)
    private val phase = rnd.nextFloat() * 6f

    /** Path through [pts] (x,y pairs) sampled about every [step] points, with noise. */
    fun polyline(vararg pts: Float, step: Float = 3f): FloatArray {
        val out = ArrayList<Float>()
        var t = 0f
        for (k in 0 until pts.size / 2 - 1) {
            val ax = pts[k * 2]; val ay = pts[k * 2 + 1]; val bx = pts[k * 2 + 2]; val by = pts[k * 2 + 3]
            val len = hypot(bx - ax, by - ay)
            val n = (len / step).toInt().coerceAtLeast(2)
            for (i in 0 until n) {
                val f = i / n.toFloat()
                add(out, ax + (bx - ax) * f, ay + (by - ay) * f, t)
                t += step
            }
        }
        add(out, pts[pts.size - 2], pts[pts.size - 1], t)
        return out.toFloatArray()
    }

    /** Ellipse centred at (cx, cy) with radii, starting at [startDeg], sweeping [sweepDeg]. */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, startDeg: Float = -90f, sweepDeg: Float = 360f, rotationDeg: Float = 0f, samples: Int = 120): FloatArray {
        val out = ArrayList<Float>()
        val rot = rotationDeg * PI.toFloat() / 180f
        for (i in 0..samples) {
            val a = (startDeg + sweepDeg * i / samples) * PI.toFloat() / 180f
            val x = rx * cos(a); val y = ry * sin(a)
            add(out, cx + x * cos(rot) - y * sin(rot), cy + x * sin(rot) + y * cos(rot), i * 3f)
        }
        return out.toFloatArray()
    }

    private fun add(out: ArrayList<Float>, x: Float, y: Float, t: Float) {
        val wx = wobble * sin(t * 0.021f + phase); val wy = wobble * cos(t * 0.017f + phase * 1.3f)
        out += x + wx + (rnd.nextFloat() - 0.5f) * 2f * jitter
        out += y + wy + (rnd.nextFloat() - 0.5f) * 2f * jitter
    }

    companion object {
        /** A closed polygon path through [verts] (repeating the first vertex), rotated around its centre. */
        fun closed(vararg verts: Float): FloatArray = verts + floatArrayOf(verts[0], verts[1])

        fun rotate(xy: FloatArray, deg: Float, cx: Float, cy: Float): FloatArray {
            val a = deg * PI.toFloat() / 180f
            val out = xy.copyOf()
            for (i in 0 until xy.size / 2) {
                val dx = xy[i * 2] - cx; val dy = xy[i * 2 + 1] - cy
                out[i * 2] = cx + dx * cos(a) - dy * sin(a)
                out[i * 2 + 1] = cy + dx * sin(a) + dy * cos(a)
            }
            return out
        }

        /** Times (ms) for [xy] drawn at [speed] points per ms. */
        fun times(xy: FloatArray, speed: Float): FloatArray {
            val n = xy.size / 2
            val t = FloatArray(n)
            for (i in 1 until n) t[i] = t[i - 1] + hypot(xy[i * 2] - xy[i * 2 - 2], xy[i * 2 + 1] - xy[i * 2 - 1]) / speed
            return t
        }

        /**
         * Cursive letters along a baseline at y, x-height [h], starting at x. Each entry is a
         * list of (x, y) control points in letter units (x 0..1 per letter width, y 0 = baseline,
         * 1 = x-height, negative = descender) that are smoothed into a stroke.
         */
        fun cursive(word: String, x: Float, y: Float, h: Float, seed: Int = 7): FloatArray {
            val ctrl = ArrayList<Float>()
            var ox = x
            for (ch in word) {
                val pts = LETTERS[ch] ?: error("No letter $ch")
                for (k in 0 until pts.size / 2) { ctrl += ox + pts[k * 2] * h; ctrl += y - pts[k * 2 + 1] * h }
                ox += LETTER_WIDTH.getValue(ch) * h
            }
            return SyntheticInk(seed, jitter = 0.3f, wobble = 0.3f).smooth(ctrl.toFloatArray())
        }

        private val LETTERS = mapOf(
            // m: up-stroke, three arches, exit.
            'm' to floatArrayOf(0f, 0f, 0.1f, 0.8f, 0.2f, 1f, 0.3f, 0.6f, 0.3f, 0f, 0.35f, 0.7f, 0.5f, 1f, 0.65f, 0.6f, 0.65f, 0f, 0.7f, 0.7f, 0.85f, 1f, 1f, 0.6f, 1f, 0.05f, 1.1f, 0.2f),
            // w: down-up-down-up with rounded bottoms.
            'w' to floatArrayOf(0f, 1f, 0.12f, 0.1f, 0.25f, 0f, 0.38f, 0.5f, 0.5f, 0.9f, 0.62f, 0.1f, 0.75f, 0f, 0.88f, 0.6f, 1f, 1f, 1.1f, 0.9f),
            // e: loop.
            'e' to floatArrayOf(0f, 0.3f, 0.5f, 0.55f, 0.6f, 0.85f, 0.4f, 1f, 0.1f, 0.6f, 0.15f, 0.1f, 0.5f, 0f, 0.8f, 0.2f),
            // n: up, arch, down, exit.
            'n' to floatArrayOf(0f, 0f, 0.1f, 1f, 0.15f, 0.2f, 0.3f, 0.8f, 0.55f, 1f, 0.75f, 0.6f, 0.75f, 0.05f, 0.9f, 0.2f),
            // l: tall loop.
            'l' to floatArrayOf(0f, 0f, 0.4f, 1.6f, 0.45f, 2.2f, 0.3f, 2.3f, 0.2f, 1.8f, 0.3f, 0.2f, 0.6f, 0f),
        )
        private val LETTER_WIDTH = mapOf('m' to 1.15f, 'w' to 1.15f, 'e' to 0.85f, 'n' to 0.95f, 'l' to 0.65f)
    }

    /** Catmull-Rom smoothing through control points, sampled about every 1.5 points, with noise. */
    fun smooth(ctrl: FloatArray): FloatArray {
        val n = ctrl.size / 2
        val out = ArrayList<Float>()
        var t = 0f
        for (k in 0 until n - 1) {
            val p0 = maxOf(0, k - 1); val p1 = k; val p2 = k + 1; val p3 = minOf(n - 1, k + 2)
            val len = hypot(ctrl[p2 * 2] - ctrl[p1 * 2], ctrl[p2 * 2 + 1] - ctrl[p1 * 2 + 1])
            val steps = (len / 1.5f).toInt().coerceAtLeast(2)
            for (i in 0 until steps) {
                val u = i / steps.toFloat()
                add(out, cr(ctrl[p0 * 2], ctrl[p1 * 2], ctrl[p2 * 2], ctrl[p3 * 2], u), cr(ctrl[p0 * 2 + 1], ctrl[p1 * 2 + 1], ctrl[p2 * 2 + 1], ctrl[p3 * 2 + 1], u), t)
                t += 1.5f
            }
        }
        add(out, ctrl[(n - 1) * 2], ctrl[(n - 1) * 2 + 1], t)
        return out.toFloatArray()
    }

    private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, u: Float): Float {
        val u2 = u * u; val u3 = u2 * u
        return 0.5f * (2f * p1 + (-p0 + p2) * u + (2f * p0 - 5f * p1 + 4f * p2 - p3) * u2 + (-p0 + 3f * p1 - 3f * p2 + p3) * u3)
    }
}
