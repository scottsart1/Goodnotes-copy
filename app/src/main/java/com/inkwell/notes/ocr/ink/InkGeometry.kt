package com.inkwell.notes.ocr.ink

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import kotlin.math.exp

/** Plain helpers to prepare strokes for handwriting recognition. */
object InkGeometry {
    /** Strokes that carry writing: everything except highlighter, with at least one point. */
    fun writingStrokes(strokes: List<Stroke>): List<Stroke> =
        strokes.filter { it.pen != PenType.HIGHLIGHTER && it.pointCount > 0 }

    /** Bounding box of [s] in page points, or null when it has no finite points. */
    fun box(index: Int, s: Stroke): StrokeBox? {
        val p = s.points
        val n = s.pointCount
        if (n == 0) return null
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val x = p[i * InkPoints.STRIDE + InkPoints.X]
            val y = p[i * InkPoints.STRIDE + InkPoints.Y]
            if (!x.isFinite() || !y.isFinite()) continue
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        if (!l.isFinite()) return null
        return StrokeBox(index, l, t, r, b, s.createdAt)
    }

    /**
     * Absolute timestamps (epoch ms) of [s]'s points: createdAt plus each point's offset. When
     * strokes are fed out of drawing order (words re-ordered left to right), a stroke that would
     * start before [notBefore] is shifted to start just after it, keeping its own timing, so the
     * recogniser always sees non-decreasing time.
     */
    fun timestamps(s: Stroke, notBefore: Long): LongArray {
        val n = s.pointCount
        val out = LongArray(n)
        var prev = Long.MIN_VALUE
        for (i in 0 until n) {
            val off = s.points[i * InkPoints.STRIDE + InkPoints.TIME]
            val t = s.createdAt + (if (off.isFinite() && off > 0f) off.toLong() else 0L)
            out[i] = if (t < prev) prev else t
            prev = out[i]
        }
        if (n > 0 && notBefore != Long.MIN_VALUE && out[0] <= notBefore) {
            val shift = notBefore + 1 - out[0]
            for (i in 0 until n) out[i] += shift
        }
        return out
    }

    /**
     * 0..1 confidence of the top candidate from ML Kit candidate scores (lower score = better,
     * a log-likelihood style cost): the top candidate's share of exp(-score) over all scored
     * candidates. Null when scores are unavailable or there is nothing to compare against.
     */
    fun confidenceFromScores(scores: List<Float?>): Float? {
        val top = scores.firstOrNull() ?: return null
        val others = scores.drop(1).filterNotNull().filter { it.isFinite() }
        if (!top.isFinite() || others.isEmpty()) return null
        var sum = 1.0
        for (s in others) sum += exp((top - s).toDouble().coerceIn(-50.0, 50.0))
        return (1.0 / sum).toFloat().coerceIn(0f, 1f)
    }

    /** Length-weighted mean of block confidences, ignoring unknown ones. */
    fun meanConfidence(blocks: List<Pair<String, Float?>>): Float? {
        var weight = 0.0
        var sum = 0.0
        for ((text, c) in blocks) {
            if (c == null) continue
            val w = text.length.coerceAtLeast(1).toDouble()
            sum += c * w
            weight += w
        }
        return if (weight == 0.0) null else (sum / weight).toFloat()
    }

    /** Identity of a line of strokes for caching recognition results. */
    fun signature(strokes: List<Stroke>): String {
        var h = 1125899906842597L
        for (s in strokes) {
            h = 31 * h + s.id.hashCode()
            h = 31 * h + s.pointCount
            if (s.pointCount > 0) {
                h = 31 * h + s.points[0].toRawBits()
                h = 31 * h + s.points[1].toRawBits()
                val last = (s.pointCount - 1) * InkPoints.STRIDE
                h = 31 * h + s.points[last].toRawBits()
                h = 31 * h + s.points[last + 1].toRawBits()
            }
        }
        return "${strokes.size}:$h"
    }
}
