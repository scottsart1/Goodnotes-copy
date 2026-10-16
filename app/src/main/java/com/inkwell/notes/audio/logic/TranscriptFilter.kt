package com.inkwell.notes.audio.logic

import com.inkwell.notes.core.model.TranscriptSegment
import java.util.Locale

/** Transcript search: which segments match and where to highlight. */
object TranscriptFilter {
    data class Match(val index: Int, val segment: TranscriptSegment, val ranges: List<IntRange>)

    /** Lower-cased search terms of [query] (whitespace separated, duplicates removed). */
    fun terms(query: String): List<String> =
        query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()

    /**
     * Segments containing every term of [query] (case-insensitive), with the ranges of all
     * term occurrences merged and sorted. A blank query returns every segment unhighlighted.
     */
    fun filter(segments: List<TranscriptSegment>, query: String): List<Match> {
        val terms = terms(query)
        if (terms.isEmpty()) return segments.mapIndexed { i, s -> Match(i, s, emptyList()) }
        val out = ArrayList<Match>()
        for ((i, s) in segments.withIndex()) {
            val ranges = highlight(s.text, terms) ?: continue
            out += Match(i, s, ranges)
        }
        return out
    }

    /** Merged highlight ranges when [text] contains all [terms]; null otherwise. */
    fun highlight(text: String, terms: List<String>): List<IntRange>? {
        val hay = text.lowercase(Locale.ROOT)
        // Lower-casing can change length for a few scripts; fall back to no highlighting then.
        val sameLength = hay.length == text.length
        val raw = ArrayList<IntRange>()
        for (t in terms) {
            var from = hay.indexOf(t)
            if (from < 0) return null
            while (from >= 0) {
                raw += from until from + t.length
                from = hay.indexOf(t, from + t.length)
            }
        }
        if (!sameLength) return emptyList()
        return merge(raw)
    }

    fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted.first()
        for (r in sorted.drop(1)) {
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else { out += cur; r }
        }
        out += cur
        return out
    }

    /** Index of the segment playing at [positionMs], or -1. */
    fun activeIndex(segments: List<TranscriptSegment>, positionMs: Long): Int {
        var lo = 0
        var hi = segments.lastIndex
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].startMs <= positionMs) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (best < 0) return -1
        val s = segments[best]
        val end = if (s.endMs > s.startMs) s.endMs else segments.getOrNull(best + 1)?.startMs ?: Long.MAX_VALUE
        return if (positionMs < end) best else -1
    }
}
