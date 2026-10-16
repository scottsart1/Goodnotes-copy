package com.inkwell.notes.ocr.ink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bounding box of one stroke (page points) with its index in the caller's list. */
data class StrokeBox(
    val index: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Epoch ms when the stroke was drawn; orders strokes within a word. */
    val startTime: Long,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** One line of handwriting. */
data class InkLine(
    /** Stroke indices in recognition order: words left to right, strokes of a word in drawing order. */
    val order: List<Int>,
    /** The same strokes grouped into words (left to right). */
    val words: List<List<Int>>,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Groups handwritten strokes into lines and words so each line can be recognised on its own.
 *
 * Lines: strokes of ordinary letter height are taken top to bottom and join the line whose
 * local centre band (around the horizontally nearest members, so slanted lines hold together)
 * they overlap by enough, relative to the median stroke height; otherwise they start a new
 * line. Dots, accents and punctuation (very short strokes) and tall strokes (ascenders,
 * brackets) are attached afterwards to the nearest suitable line. Strokes much taller and wider
 * than letters are treated as drawings and left out.
 *
 * Words: within a line, a horizontal gap wider than [WORD_GAP] median heights starts a new word
 * and one wider than [COLUMN_GAP] starts a separate line (side-by-side columns).
 */
object InkLines {
    const val MIN_HEIGHT = 2f
    const val SMALL = 0.35f
    const val TALL = 2.5f
    const val DRAWING = 5f
    const val BAND = 0.6f
    const val MIN_OVERLAP = 0.35f
    const val DOT_REACH = 1.0f
    const val ABOVE_WEIGHT = 0.7f
    const val WORD_GAP = 0.6f
    const val COLUMN_GAP = 4f
    private const val NEIGHBOURS = 3

    /** Median stroke height, ignoring specks; never below [MIN_HEIGHT]. */
    fun medianHeight(boxes: List<StrokeBox>): Float {
        if (boxes.isEmpty()) return MIN_HEIGHT
        val hs = boxes.map { it.height }.filter { it >= MIN_HEIGHT }.ifEmpty { boxes.map { it.height } }.sorted()
        val m = if (hs.size % 2 == 1) hs[hs.size / 2] else (hs[hs.size / 2 - 1] + hs[hs.size / 2]) / 2f
        return max(m, MIN_HEIGHT)
    }

    fun isDrawing(b: StrokeBox, median: Float): Boolean = b.height > DRAWING * median && b.width > DRAWING * median

    fun cluster(boxes: List<StrokeBox>): List<InkLine> {
        if (boxes.isEmpty()) return emptyList()
        val h = medianHeight(boxes)
        val text = boxes.filterNot { isDrawing(it, h) }
        if (text.isEmpty()) return emptyList()

        val normal = text.filter { it.height >= SMALL * h && it.height <= TALL * h }
        val small = text.filter { it.height < SMALL * h }
        val tall = text.filter { it.height > TALL * h }

        val lines = ArrayList<Acc>()
        for (s in normal.sortedWith(compareBy({ it.centerY }, { it.left }))) {
            val best = best(lines) { normalScore(it, s, h) }
            if (best == null) lines += Acc(s) else best.add(s, core = true)
        }
        for (s in tall.sortedBy { it.centerY }) {
            val best = best(lines) { tallScore(it, s) }
            if (best == null) lines += Acc(s) else best.add(s, core = false)
        }
        for (s in small.sortedBy { it.centerY }) {
            val best = best(lines) { smallScore(it, s, h) }
            if (best == null) lines += Acc(s, core = false) else best.add(s, core = false)
        }

        val out = ArrayList<InkLine>()
        for (acc in lines) out += splitColumns(acc.members, h)
        return out.sortedWith(compareBy({ (it.top + it.bottom) / 2f }, { it.left }))
            .let { sorted -> orderLines(sorted, h) }
    }

    /** Top to bottom; lines whose centres are within half a letter height read left to right. */
    private fun orderLines(lines: List<InkLine>, h: Float): List<InkLine> {
        val out = ArrayList<InkLine>(lines.size)
        var i = 0
        while (i < lines.size) {
            val c = (lines[i].top + lines[i].bottom) / 2f
            var j = i + 1
            while (j < lines.size && (lines[j].top + lines[j].bottom) / 2f - c < 0.5f * h) j++
            out += lines.subList(i, j).sortedBy { it.left }
            i = j
        }
        return out
    }

    /** Splits strokes of one line into words and, at very wide gaps, into separate lines. */
    fun splitColumns(members: List<StrokeBox>, median: Float): List<InkLine> {
        val words = splitWords(members, WORD_GAP * median)
        val segments = ArrayList<MutableList<List<StrokeBox>>>()
        var lastRight = Float.NEGATIVE_INFINITY
        for (w in words) {
            val left = w.minOf { it.left }
            if (segments.isEmpty() || left - lastRight > COLUMN_GAP * median) segments += ArrayList<List<StrokeBox>>()
            segments.last() += w
            lastRight = max(lastRight, w.maxOf { it.right })
        }
        return segments.map { seg ->
            val all = seg.flatten()
            InkLine(
                order = seg.flatMap { w -> w.map { it.index } },
                words = seg.map { w -> w.map { it.index } },
                left = all.minOf { it.left },
                top = all.minOf { it.top },
                right = all.maxOf { it.right },
                bottom = all.maxOf { it.bottom },
            )
        }
    }

    /**
     * Words of a line, left to right: a new word starts where the next stroke begins more than
     * [gap] to the right of everything so far. Strokes inside a word keep drawing order.
     */
    fun splitWords(members: List<StrokeBox>, gap: Float): List<List<StrokeBox>> {
        if (members.isEmpty()) return emptyList()
        val sorted = members.sortedWith(compareBy({ it.left }, { it.startTime }))
        val words = ArrayList<MutableList<StrokeBox>>()
        var right = Float.NEGATIVE_INFINITY
        for (s in sorted) {
            if (words.isEmpty() || s.left - right > gap) words += ArrayList<StrokeBox>()
            words.last() += s
            right = max(right, s.right)
        }
        return words.map { w -> w.sortedWith(compareBy({ it.startTime }, { it.index })) }
    }

    private inline fun best(lines: List<Acc>, score: (Acc) -> Float): Acc? {
        var best: Acc? = null
        var bestScore = 0f
        for (l in lines) {
            val s = score(l)
            if (s > bestScore) { best = l; bestScore = s }
        }
        return best
    }

    private class Acc(first: StrokeBox, core: Boolean = true) {
        val members = arrayListOf(first)
        val cores = if (core) arrayListOf(first) else arrayListOf()
        var left = first.left
        var right = first.right

        fun add(s: StrokeBox, core: Boolean) {
            members += s
            if (core) cores += s
            left = min(left, s.left)
            right = max(right, s.right)
        }

        /** Centre of the line near [x]: median centre of the horizontally nearest core strokes. */
        fun centerNear(x: Float): Float {
            val pool = cores.ifEmpty { members }
            if (pool.size <= NEIGHBOURS) return median(FloatArray(pool.size) { pool[it].centerY }, pool.size)
            // Linear selection of the NEIGHBOURS nearest strokes.
            val dist = FloatArray(NEIGHBOURS) { Float.POSITIVE_INFINITY }
            val cy = FloatArray(NEIGHBOURS)
            for (s in pool) {
                val d = abs(s.centerX - x)
                var k = NEIGHBOURS - 1
                if (d >= dist[k]) continue
                while (k > 0 && dist[k - 1] > d) {
                    dist[k] = dist[k - 1]; cy[k] = cy[k - 1]; k--
                }
                dist[k] = d; cy[k] = s.centerY
            }
            return median(cy, NEIGHBOURS)
        }

        private fun median(values: FloatArray, n: Int): Float {
            val v = values.copyOf(n).also { it.sort() }
            return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2f
        }
    }

    private fun normalScore(line: Acc, s: StrokeBox, h: Float): Float {
        val c = line.centerNear(s.centerX)
        val bandTop = c - BAND * h
        val bandBottom = c + BAND * h
        val overlap = min(s.bottom, bandBottom) - max(s.top, bandTop)
        val need = min(s.height, h).coerceAtLeast(0.5f)
        return if (overlap >= MIN_OVERLAP * need) overlap / need + 0.001f else 0f
    }

    private fun tallScore(line: Acc, s: StrokeBox): Float {
        val c = line.centerNear(s.centerX)
        if (c < s.top || c > s.bottom) return 0f
        return 1f - abs(s.centerY - c) / s.height.coerceAtLeast(0.5f) + 0.001f
    }

    private fun smallScore(line: Acc, s: StrokeBox, h: Float): Float {
        val c = line.centerNear(s.centerX)
        val reach = DOT_REACH * h
        // Dots and accents above the letters (i, j, é) sit further from the centre than
        // punctuation on the baseline, and belong to the line below them: weigh them closer.
        val d = if (s.centerY < c) (c - s.centerY) * ABOVE_WEIGHT else s.centerY - c
        if (d > reach) return 0f
        // Prefer lines that actually extend over the dot horizontally.
        val horizontal = if (s.centerX >= line.left - h && s.centerX <= line.right + h) 0.5f else 0f
        return 1f - d / reach + horizontal + 0.001f
    }
}
