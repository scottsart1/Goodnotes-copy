package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.WordClusterer
import com.inkwell.notes.ink.tools.EraserEngine
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Word-level layout of handwriting: baseline angle (straighten), reflow into a width and word
 * spacing. Words come from [WordClusterer]; lines group words by vertical position. Pure Kotlin.
 */
object HandwritingLayout {
    class Word(val ids: Set<String>, val bounds: Bounds)

    fun words(strokes: List<Stroke>): List<Word> {
        val boxes = strokes.map { EraserEngine.boxOf(it) }
        val byId = boxes.associateBy { it.id }
        return WordClusterer.words(boxes).map { ids ->
            val b = Bounds()
            for (id in ids) byId[id]?.let { b.union(it.bounds) }
            Word(ids, b)
        }
    }

    /** Words grouped into lines (top to bottom), each line sorted left to right. */
    fun lines(words: List<Word>): List<List<Word>> {
        if (words.isEmpty()) return emptyList()
        val hs = words.map { it.bounds.height }.sorted()
        val h = max(hs[hs.size / 2], 4f)
        val sorted = words.sortedBy { it.bounds.centerY }
        val lines = ArrayList<MutableList<Word>>()
        var lineCenter = Float.NaN
        for (w in sorted) {
            if (lines.isEmpty() || abs(w.bounds.centerY - lineCenter) > h * 0.6f) {
                lines += mutableListOf(w)
                lineCenter = w.bounds.centerY
            } else {
                val line = lines.last()
                line += w
                lineCenter = line.map { it.bounds.centerY }.average().toFloat()
            }
        }
        return lines.map { l -> l.sortedBy { it.bounds.left } }
    }

    /** Baseline angle in radians (positive = rising to the left in y-down space), clamped to +-35 degrees. */
    fun baselineAngle(strokes: List<Stroke>): Float {
        val ws = words(strokes)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        if (ws.size >= 2) {
            for (w in ws) { xs += w.bounds.centerX; ys += w.bounds.centerY }
        } else {
            for (s in strokes) for (i in 0 until s.pointCount) { xs += s.points[i * InkPoints.STRIDE]; ys += s.points[i * InkPoints.STRIDE + 1] }
        }
        if (xs.size < 2) return 0f
        val mx = xs.average(); val my = ys.average()
        var sxx = 0.0; var sxy = 0.0
        for (k in xs.indices) { val dx = xs[k] - mx; sxx += dx * dx; sxy += dx * (ys[k] - my) }
        if (sxx < 1e-6) return 0f
        return atan(sxy / sxx).toFloat().coerceIn(-0.61f, 0.61f)
    }

    fun rotate(s: Stroke, angle: Float, cx: Float, cy: Float): Stroke {
        val c = cos(angle); val sn = sin(angle)
        val p = s.points.copyOf()
        var i = 0
        while (i + 1 < p.size) {
            val dx = p[i] - cx; val dy = p[i + 1] - cy
            p[i] = cx + dx * c - dy * sn
            p[i + 1] = cy + dx * sn + dy * c
            i += InkPoints.STRIDE
        }
        return s.copy(points = p)
    }

    /**
     * Moves (stroke id -> dx, dy) that re-wrap the words into lines of [width] starting at
     * ([left], first line's position). Null when nothing would move.
     */
    fun reflow(strokes: List<Stroke>, left: Float, top: Float, width: Float): Map<String, Pair<Float, Float>>? {
        val ls = lines(words(strokes))
        if (ls.isEmpty()) return null
        val all = ls.flatten()
        val hs = all.map { it.bounds.height }.sorted()
        val h = max(hs[hs.size / 2], 4f)
        val gaps = ArrayList<Float>()
        for (l in ls) for (k in 1 until l.size) gaps += l[k].bounds.left - l[k - 1].bounds.right
        gaps.sort()
        val gap = (if (gaps.isEmpty()) h * 0.5f else gaps[gaps.size / 2]).coerceIn(h * 0.25f, h * 2.5f)
        val centers = ls.map { l -> l.map { it.bounds.centerY }.average().toFloat() }
        val spacings = (1 until centers.size).map { centers[it] - centers[it - 1] }.filter { it > h * 0.8f }.sorted()
        val lineHeight = if (spacings.isEmpty()) h * 1.6f else spacings[spacings.size / 2]
        var y = centers[0]
        var x = left
        val out = HashMap<String, Pair<Float, Float>>()
        var moved = false
        for (w in all) {
            if (x > left && x + w.bounds.width > left + width) { x = left; y += lineHeight }
            val dx = x - w.bounds.left; val dy = y - w.bounds.centerY
            if (abs(dx) > 0.01f || abs(dy) > 0.01f) moved = true
            for (id in w.ids) out[id] = dx to dy
            x += w.bounds.width + gap
        }
        @Suppress("UNUSED_VARIABLE") val unusedTop = top
        return if (moved) out else null
    }

    /** Moves that scale the gaps between words on each line by [factor] (first word stays). */
    fun respace(strokes: List<Stroke>, factor: Float): Map<String, Pair<Float, Float>>? {
        val ls = lines(words(strokes))
        val out = HashMap<String, Pair<Float, Float>>()
        var moved = false
        for (l in ls) {
            if (l.size < 2) continue
            var right = l[0].bounds.right
            for (k in 1 until l.size) {
                val w = l[k]
                val gap = max(0.5f, (w.bounds.left - l[k - 1].bounds.right) * factor)
                val newLeft = right + gap
                val dx = newLeft - w.bounds.left
                if (abs(dx) > 0.01f) moved = true
                for (id in w.ids) out[id] = dx to 0f
                right = newLeft + w.bounds.width
            }
        }
        return if (moved) out else null
    }
}
