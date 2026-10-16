package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.Geo
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Layout of handwriting for straighten, reflow, word spacing and convert-to-text sizing.
 *
 * Segmentation works lines first, then words: strokes are deskewed by a robust estimate of the
 * writing slope, letter strokes are grouped into lines by their vertical centre (dots, accents
 * and punctuation attach to the nearest line), and each line is split into words at gaps clearly
 * wider than the gaps between letters (an adaptive split of the gap distribution, relative to
 * the line's letter height). The baseline is a regression through the bottoms of letter strokes
 * with descenders rejected as outliers, pooled across lines. Pure Kotlin.
 */
object HandwritingLayout {
    class Word(val ids: Set<String>, val bounds: Bounds)

    class Line(
        val words: List<Word>,
        val bounds: Bounds,
        /** Median vertical centre of the line's letter strokes. */
        val center: Float,
        /** Median bottom of the line's letter strokes (descenders excluded). */
        val baseline: Float,
        /** Median height of the line's letter strokes. */
        val letterHeight: Float,
    )

    private class Box(val stroke: Stroke, val bounds: Bounds, val bottomX: Float, val bottomY: Float) {
        val id: String get() = stroke.id
    }

    private const val MIN_HEIGHT = 4f

    // ------------------------------------------------------------------ Segmentation

    private fun box(s: Stroke): Box {
        val st = InkPoints.STRIDE
        val b = Geo.polylineBounds(s.points, st, Bounds())
        var bx = b.centerX; var by = Float.NEGATIVE_INFINITY
        for (i in 0 until s.pointCount) {
            val y = s.points[i * st + 1]
            if (y > by) { by = y; bx = s.points[i * st] }
        }
        if (by == Float.NEGATIVE_INFINITY) by = b.bottom
        return Box(s, b, bx, by)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        return s[s.size / 2]
    }

    /** Median height of letter-sized strokes (ignoring dots and specks). */
    private fun letterHeight(boxes: List<Box>): Float {
        val hs = boxes.map { it.bounds.height }.filter { it > 0.5f }
        val m = median(hs)
        val letters = hs.filter { it >= 0.35f * m }
        return max(MIN_HEIGHT, median(letters))
    }

    /** Lines of handwriting, top to bottom, each with its words left to right. */
    fun segment(strokes: List<Stroke>): List<Line> {
        if (strokes.isEmpty()) return emptyList()
        val boxes = strokes.map { box(it) }
        val h = letterHeight(boxes)
        // Group level first, then again along the writing's slope when it is noticeably tilted
        // (a tilted line would otherwise fall apart into fragments).
        var groups = group(boxes, h, 0f)
        val slope = pooledSlope(groups, h)
        if (abs(slope) > 0.02f) groups = group(boxes, h, slope)
        return groups.map { buildLine(it, slope) }
    }

    /** Strokes grouped into lines (top to bottom) after removing [slope] (dy/dx). */
    private fun group(boxes: List<Box>, h: Float, slope: Float): List<List<Box>> {
        fun cy(b: Box) = b.bounds.centerY - slope * b.bounds.centerX
        val big = boxes.filter { it.bounds.height >= 0.35f * h }.sortedBy { cy(it) }
        val small = boxes.filter { it.bounds.height < 0.35f * h }
        val groups = ArrayList<MutableList<Box>>()
        val centers = ArrayList<Float>()
        for (b in big) {
            val c = cy(b)
            val last = groups.lastIndex
            if (last >= 0 && abs(c - centers[last]) <= 0.6f * max(h, min(b.bounds.height, 2f * h) * 0.8f)) {
                groups[last] += b
                centers[last] = median(groups[last].map { cy(it) })
            } else {
                groups += mutableListOf(b)
                centers += c
            }
        }
        // Dots, accents and punctuation join the nearest line within reach; others stand alone.
        for (b in small) {
            val c = cy(b)
            var best = -1; var bestD = Float.POSITIVE_INFINITY
            for (k in groups.indices) {
                val d = abs(c - centers[k])
                if (d < bestD) { bestD = d; best = k }
            }
            if (best >= 0 && bestD <= 1.3f * h) groups[best] += b else { groups += mutableListOf(b); centers += c }
        }
        return groups.indices.sortedBy { centers[it] }.map { groups[it] }
    }

    /**
     * Writing slope (dy/dx) pooled across lines: within each line a regression through the
     * bottoms of letter strokes, refitted without descenders.
     */
    private fun pooledSlope(groups: List<List<Box>>, h: Float): Float {
        var sxx = 0.0; var sxy = 0.0
        for (g in groups) {
            val pts = g.filter { it.bounds.height >= 0.35f * h }.map { it.bottomX to it.bottomY }
            if (pts.size < 2) continue
            val keep = withoutDescenders(pts, h)
            val kx = keep.map { it.first }.average(); val ky = keep.map { it.second }.average()
            for ((x, y) in keep) { sxx += (x - kx) * (x - kx); sxy += (x - kx) * (y - ky) }
        }
        if (sxx < 1e-6) return 0f
        return (sxy / sxx).toFloat().coerceIn(-0.7f, 0.7f)
    }

    /** Drops points lying well below a line fitted through them (descenders), refitting twice. */
    private fun withoutDescenders(points: List<Pair<Float, Float>>, h: Float): List<Pair<Float, Float>> {
        var pts = points
        repeat(3) {
            if (pts.size < 3) return pts
            val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
            var sxx = 0.0; var sxy = 0.0
            for ((x, y) in pts) { sxx += (x - mx) * (x - mx); sxy += (x - mx) * (y - my) }
            val slope = if (sxx < 1e-6) 0.0 else sxy / sxx
            val icept = my - slope * mx
            val keep = pts.filter { (x, y) -> y - (slope * x + icept) <= 0.3f * h }
            if (keep.size == pts.size || keep.size < 2) return pts
            pts = keep
        }
        return pts
    }

    private fun buildLine(members: List<Box>, slope: Float): Line {
        val h = letterHeight(members)
        val letters = members.filter { it.bounds.height >= 0.35f * h }.ifEmpty { members }
        val sorted = members.sortedBy { it.bounds.left }
        // Raw gaps: each stroke's left minus the furthest right edge before it.
        val gaps = ArrayList<Float>()
        var right = Float.NEGATIVE_INFINITY
        for (b in sorted) {
            if (right > Float.NEGATIVE_INFINITY) gaps += b.bounds.left - right
            right = max(right, b.bounds.right)
        }
        val threshold = wordGapThreshold(gaps, h)
        val words = ArrayList<Word>()
        var ids = LinkedHashSet<String>()
        var wb = Bounds()
        right = Float.NEGATIVE_INFINITY
        for (b in sorted) {
            if (ids.isNotEmpty() && b.bounds.left - right > threshold) {
                words += Word(ids, wb)
                ids = LinkedHashSet(); wb = Bounds()
            }
            ids += b.id
            wb.union(b.bounds)
            right = max(right, b.bounds.right)
        }
        if (ids.isNotEmpty()) words += Word(ids, wb)
        val lb = Bounds()
        for (b in members) lb.union(b.bounds)
        val baseline = robustBaseline(letters, h, slope)
        return Line(words, lb, median(letters.map { it.bounds.centerY }), baseline, h)
    }

    /**
     * Gap above which strokes belong to different words: the largest clear jump in the sorted
     * positive gaps when there is one, otherwise a fixed fraction of the letter height.
     */
    fun wordGapThreshold(gaps: List<Float>, letterHeight: Float): Float {
        val h = max(letterHeight, MIN_HEIGHT)
        val pos = gaps.filter { it > 0f }.sorted()
        if (pos.size >= 2) {
            var bestRatio = 0f; var at = -1
            for (k in 0 until pos.size - 1) {
                val lo = max(pos[k], 0.06f * h); val hi = pos[k + 1]
                if (hi < 0.3f * h) continue
                val ratio = hi / lo
                if (ratio > bestRatio) { bestRatio = ratio; at = k }
            }
            if (at >= 0 && bestRatio >= 1.8f) return sqrt(max(pos[at], 0.06f * h) * pos[at + 1]).coerceIn(0.2f * h, 1.0f * h)
        }
        return 0.45f * h
    }

    private fun robustBaseline(letters: List<Box>, h: Float, slope: Float): Float {
        // Bottoms deskewed to the line's mean x; descenders (well below the median) are dropped.
        if (letters.isEmpty()) return 0f
        val mx = letters.map { it.bottomX }.average().toFloat()
        val ys = letters.map { it.bottomY - slope * (it.bottomX - mx) }
        val m = median(ys)
        return median(ys.filter { it - m <= 0.3f * h }.ifEmpty { ys })
    }

    // ------------------------------------------------------------------ Legacy word/line views

    fun words(strokes: List<Stroke>): List<Word> = segment(strokes).flatMap { it.words }

    /** Words grouped into lines (top to bottom), each line sorted left to right. */
    fun lines(words: List<Word>): List<List<Word>> {
        if (words.isEmpty()) return emptyList()
        val h = max(median(words.map { it.bounds.height }), MIN_HEIGHT)
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

    // ------------------------------------------------------------------ Straighten

    /**
     * Baseline angle in radians (positive = falling to the right in y-down space), from a
     * regression of letter-stroke bottoms pooled across lines, clamped to +-35 degrees.
     */
    fun baselineAngle(strokes: List<Stroke>): Float {
        if (strokes.isEmpty()) return 0f
        val boxes = strokes.map { box(it) }
        val h = letterHeight(boxes)
        var slope = pooledSlope(group(boxes, h, 0f), h)
        if (abs(slope) > 0.02f) slope = pooledSlope(group(boxes, h, slope), h)
        return atan(slope).coerceIn(-0.61f, 0.61f)
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

    // ------------------------------------------------------------------ Reflow & spacing

    /** Typical gap between words, from the lines' inter-word gaps. */
    private fun typicalWordGap(lines: List<Line>, h: Float): Float {
        val gaps = ArrayList<Float>()
        for (l in lines) for (k in 1 until l.words.size) gaps += l.words[k].bounds.left - l.words[k - 1].bounds.right
        return (if (gaps.isEmpty()) h * 0.6f else median(gaps)).coerceIn(h * 0.25f, h * 2.5f)
    }

    /**
     * Distance between consecutive lines' baselines (the writing's line height): the lower
     * quartile of the spacings, so paragraph gaps do not inflate it.
     */
    private fun linePitch(lines: List<Line>, h: Float): Float {
        val d = (1 until lines.size).map { lines[it].baseline - lines[it - 1].baseline }.filter { it > h * 0.8f }.sorted()
        return if (d.isEmpty()) h * 1.8f else d[(d.size - 1) / 4]
    }

    /**
     * Moves (stroke id -> dx, dy) that re-wrap the words, in reading order, into lines of [width]
     * starting at [left]. Line height and word gaps follow the original writing; a blank-line gap
     * between paragraphs is kept; the first line keeps its vertical position (at least [top]).
     * Null when nothing would move.
     */
    fun reflow(strokes: List<Stroke>, left: Float, top: Float, width: Float): Map<String, Pair<Float, Float>>? {
        val lines = segment(strokes)
        if (lines.isEmpty()) return null
        val h = median(lines.map { it.letterHeight })
        val gap = typicalWordGap(lines, h)
        val pitch = linePitch(lines, h)
        // The first line stays where it is (never above the selection's top).
        var y = max(lines[0].baseline, top)
        var x = left
        val out = HashMap<String, Pair<Float, Float>>()
        var moved = false
        for ((k, line) in lines.withIndex()) {
            if (k > 0) {
                val spacing = line.baseline - lines[k - 1].baseline
                if (spacing > pitch * 1.8f) {
                    // Paragraph break: start a new line and keep the blank lines.
                    val blank = ((spacing / pitch).roundToInt() - 1).coerceIn(1, 3)
                    y += pitch * (1 + blank)
                    x = left
                }
            }
            for (w in line.words) {
                if (x > left && x + w.bounds.width > left + width) { x = left; y += pitch }
                val dx = x - w.bounds.left; val dy = y - line.baseline
                if (abs(dx) > 0.01f || abs(dy) > 0.01f) moved = true
                for (id in w.ids) out[id] = dx to dy
                x += w.bounds.width + gap
            }
        }
        return if (moved) out else null
    }

    /** Moves that scale the gaps between words on each line by [factor] (the first word stays). */
    fun respace(strokes: List<Stroke>, factor: Float): Map<String, Pair<Float, Float>>? {
        val out = HashMap<String, Pair<Float, Float>>()
        var moved = false
        for (line in segment(strokes)) {
            val ws = line.words
            if (ws.size < 2) continue
            var right = ws[0].bounds.right
            for (k in 1 until ws.size) {
                val w = ws[k]
                val g = max(0.5f, (w.bounds.left - ws[k - 1].bounds.right) * factor)
                val newLeft = right + g
                val dx = newLeft - w.bounds.left
                if (abs(dx) > 0.01f) moved = true
                for (id in w.ids) out[id] = dx to 0f
                right = newLeft + w.bounds.width
            }
        }
        return if (moved) out else null
    }

    /**
     * Font size (points) for typed text replacing this handwriting: the height of the writing's
     * ascenders above the baseline maps to a typical font's ascender height (about 0.72 em),
     * never more than the line spacing allows.
     */
    fun estimateFontSize(strokes: List<Stroke>): Float {
        val lines = segment(strokes)
        if (lines.isEmpty()) return 14f
        val ascents = ArrayList<Float>()
        val byId = strokes.associateBy { it.id }
        for (line in lines) {
            val tops = line.words.flatMap { w -> w.ids.mapNotNull { byId[it] } }
                .map { box(it).bounds }
                .filter { it.height >= 0.35f * line.letterHeight }
                .map { it.top }
                .sorted()
            if (tops.isEmpty()) continue
            val top = tops[(tops.size * 0.2f).toInt().coerceIn(0, tops.size - 1)]
            ascents += line.baseline - top
        }
        var size = (median(ascents) / 0.72f)
        if (lines.size >= 2) size = min(size, linePitch(lines, median(lines.map { it.letterHeight })) / 1.15f)
        return size.coerceIn(8f, 96f)
    }
}
