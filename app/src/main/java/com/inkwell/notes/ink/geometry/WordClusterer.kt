package com.inkwell.notes.ink.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Groups handwritten strokes into words: strokes on the same line whose horizontal gap is small
 * relative to the writing height are linked, with a looser gap for strokes written in quick
 * succession, and accents (i-dots, t-bars) attach to the letters below them. Pure Kotlin.
 */
object WordClusterer {
    /** One stroke's ink bounds (without width padding) and timing. */
    class StrokeBox(
        val id: String,
        val bounds: Bounds,
        /** Wall clock ms of the first sample. */
        val startMs: Long,
        /** Wall clock ms of the last sample. */
        val endMs: Long,
    )

    /** Gap (relative to line height) that still joins letters of one word. */
    const val GAP_FACTOR = 0.38f
    /** Gap allowed for strokes written within [QUICK_MS] of each other. */
    const val QUICK_GAP_FACTOR = 0.6f
    const val QUICK_MS = 1500L
    const val MIN_LINE_HEIGHT = 6f

    /** Ids of the word containing [seedId] (always includes the seed when present). */
    fun wordOf(seedId: String, strokes: List<StrokeBox>): Set<String> {
        val seed = strokes.firstOrNull { it.id == seedId } ?: return emptySet()
        val h = lineHeight(seed, strokes)
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<StrokeBox>()
        result += seed.id
        queue += seed
        // Guard against chaining across a whole page with very tight spacing.
        val maxWidth = h * 14f
        val word = seed.bounds.copy()
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            for (b in strokes) {
                if (b.id in result) continue
                if (!linked(a, b, h)) continue
                val grown = word.copy().union(b.bounds)
                if (grown.width > maxWidth) continue
                word.set(grown)
                result += b.id
                queue += b
            }
        }
        return result
    }

    /** Partitions all strokes into words (each id appears in exactly one set). */
    fun words(strokes: List<StrokeBox>): List<Set<String>> {
        val assigned = HashSet<String>()
        val out = ArrayList<Set<String>>()
        // Left-to-right, top-to-bottom so clusters grow in reading order.
        val sorted = strokes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        for (s in sorted) {
            if (s.id in assigned) continue
            val w = wordOf(s.id, strokes.filter { it.id !in assigned })
            assigned += w
            out += w
        }
        return out
    }

    /**
     * Writing height around [seed]: median ink height of strokes overlapping its vertical band,
     * which is robust to accents and descenders.
     */
    fun lineHeight(seed: StrokeBox, strokes: List<StrokeBox>): Float {
        // Look a little above and below so an accent seed (an i-dot) still finds its letters.
        val pad = max(seed.bounds.height, MIN_LINE_HEIGHT) * 2f
        val band = seed.bounds.copy().also { it.top -= pad; it.bottom += pad }
        val heights = ArrayList<Float>()
        for (s in strokes) {
            val overlap = min(s.bounds.bottom, band.bottom) - max(s.bounds.top, band.top)
            if (overlap > 0f || s === seed) heights += s.bounds.height
        }
        if (heights.isEmpty()) return max(MIN_LINE_HEIGHT, seed.bounds.height)
        heights.sort()
        val median = heights[heights.size / 2]
        return max(MIN_LINE_HEIGHT, max(median, seed.bounds.height * 0.5f))
    }

    fun linked(a: StrokeBox, b: StrokeBox, h: Float): Boolean {
        val ab = a.bounds; val bb = b.bounds
        val gapX = max(0f, max(ab.left, bb.left) - min(ab.right, bb.right))
        val overlapY = min(ab.bottom, bb.bottom) - max(ab.top, bb.top)
        val minH = max(1f, min(ab.height, bb.height))
        val quick = abs(b.startMs - a.endMs) <= QUICK_MS || abs(a.startMs - b.endMs) <= QUICK_MS
        val gapLimit = h * if (quick) QUICK_GAP_FACTOR else GAP_FACTOR
        if (overlapY >= 0.25f * minH || overlapY >= 0.25f * h) {
            return gapX <= gapLimit
        }
        // Accent above/below: small stroke horizontally over the other, vertically close.
        val small = if (ab.height < bb.height) ab else bb
        val big = if (small === ab) bb else ab
        val horizontallyOver = small.right >= big.left - h * 0.15f && small.left <= big.right + h * 0.15f
        val gapY = max(0f, max(ab.top, bb.top) - min(ab.bottom, bb.bottom))
        return small.height <= h * 0.6f && horizontallyOver && gapY <= h * 0.6f
    }
}
