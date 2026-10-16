package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.core.model.SpeakerTurn

/**
 * Who spoke when, ready to draw under the waveform: one segment per stretch of speech as
 * fractions 0..1 of the timeline, in time order, with the voice's palette index. Built once
 * per change of turns or speakers, so drawing allocates nothing.
 */
class LaneGeometry(
    val starts: FloatArray,
    val ends: FloatArray,
    /** Palette index per segment. */
    val colors: IntArray,
    val labels: Array<String>,
    /** Where tapping the segment seeks to. */
    val startMs: LongArray,
) {
    val size: Int get() = starts.size

    fun isEmpty(): Boolean = size == 0

    companion object {
        val EMPTY = LaneGeometry(FloatArray(0), FloatArray(0), IntArray(0), emptyArray(), LongArray(0))
    }
}

object SpeakerLanes {
    /**
     * Segments for [turns] over [durationMs]. Turns of one voice separated by less than
     * [mergeGapMs] (a breath) become one segment; turns outside the timeline are clipped.
     */
    fun build(turns: List<SpeakerTurn>, durationMs: Long, colorOf: (label: String) -> Int, mergeGapMs: Long = durationMs / 1_000): LaneGeometry {
        if (durationMs <= 0 || turns.isEmpty()) return LaneGeometry.EMPTY
        val sorted = turns.sortedBy { it.startMs }
        val s = ArrayList<Long>(sorted.size)
        val e = ArrayList<Long>(sorted.size)
        val l = ArrayList<String>(sorted.size)
        for (t in sorted) {
            val a = t.startMs.coerceIn(0, durationMs)
            val b = t.endMs.coerceIn(0, durationMs)
            if (b <= a) continue
            val last = l.lastIndex
            if (last >= 0 && l[last] == t.label && a - e[last] <= mergeGapMs) {
                e[last] = maxOf(e[last], b)
                continue
            }
            s += a
            e += b
            l += t.label
        }
        val d = durationMs.toFloat()
        return LaneGeometry(
            starts = FloatArray(s.size) { s[it] / d },
            ends = FloatArray(e.size) { e[it] / d },
            colors = IntArray(l.size) { colorOf(l[it]) },
            labels = l.toTypedArray(),
            startMs = LongArray(s.size) { s[it] },
        )
    }

    /** The segment under [fraction], or the nearest one within [slop]; -1 when none. */
    fun hit(g: LaneGeometry, fraction: Float, slop: Float): Int {
        if (g.isEmpty()) return -1
        var lo = 0
        var hi = g.size - 1
        var before = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (g.starts[mid] <= fraction) { before = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (before >= 0 && fraction <= g.ends[before]) return before
        val after = before + 1
        val dBefore = if (before >= 0) fraction - g.ends[before] else Float.MAX_VALUE
        val dAfter = if (after < g.size) g.starts[after] - fraction else Float.MAX_VALUE
        val (best, dist) = if (dBefore <= dAfter) before to dBefore else after to dAfter
        return if (best >= 0 && dist <= slop) best else -1
    }
}
