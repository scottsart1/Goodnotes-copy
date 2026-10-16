package com.inkwell.notes.ocr.text

/** A short passage around a match; [matchStart]/[matchEnd] index into [text] (-1 when there is no match). */
data class Excerpt(val text: String, val matchStart: Int, val matchEnd: Int)

/** Builds the ~160 character excerpts shown in search results. */
object Excerpts {
    const val DEFAULT_LENGTH = 160
    /** How much text to keep before the match, so it reads in context. */
    private const val LEAD = 48
    /** How far a window edge may move to land on a word boundary. */
    private const val SNAP = 16
    const val ELLIPSIS = "…"

    /**
     * Excerpt of [text] around the best match of [query] (match tokens, see [FtsQuery.matchTokens]):
     * the phrase if present, otherwise the earliest word starting with a query word, otherwise the
     * earliest raw occurrence. Whitespace is collapsed; cut edges get an ellipsis. The match range
     * covers whole words.
     */
    fun build(text: String, query: List<String>, maxLength: Int = DEFAULT_LENGTH): Excerpt {
        val clean = TextMatch.collapseWhitespace(text)
        if (clean.isEmpty()) return Excerpt("", -1, -1)
        val match = findMatch(clean, query)
        if (match == null) return head(clean, maxLength)
        val (mStart, mEnd) = match
        val len = clean.length
        if (len <= maxLength) return Excerpt(clean, mStart, mEnd)

        var start = (mStart - LEAD).coerceAtLeast(0)
        if (len - start < maxLength) start = (len - maxLength).coerceAtLeast(0)
        if (start > 0) start = snapForward(clean, start, mStart)
        var end = (start + maxLength).coerceAtMost(len)
        if (end < len) end = snapBackward(clean, end, mEnd)
        if (end < mEnd) end = mEnd.coerceAtMost(len)
        // Never cut through an emoji (surrogate pair) at either edge.
        start = IndexText.safeCut(clean, start)
        if (IndexText.safeCut(clean, end) != end) end++

        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < len) ELLIPSIS else ""
        val body = clean.substring(start, end).trim()
        val trimShift = clean.substring(start, end).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val s = prefix.length + (mStart - start - trimShift)
        val e = prefix.length + (mEnd - start - trimShift)
        val out = prefix + body + suffix
        val ms = s.coerceIn(0, out.length)
        val me = e.coerceIn(ms, (prefix.length + body.length).coerceAtMost(out.length))
        return if (me > ms) Excerpt(out, ms, me) else Excerpt(out, -1, -1)
    }

    /** The opening of [clean] when nothing matched. */
    private fun head(clean: String, maxLength: Int): Excerpt {
        if (clean.length <= maxLength) return Excerpt(clean, -1, -1)
        val end = IndexText.safeCut(clean, snapBackward(clean, maxLength, 0))
        return Excerpt(clean.substring(0, end).trimEnd() + ELLIPSIS, -1, -1)
    }

    /** Start/end (exclusive) of the best match in [clean], or null. */
    fun findMatch(clean: String, query: List<String>): Pair<Int, Int>? {
        if (query.isEmpty()) return null
        val words = TextMatch.words(clean)
        val p = TextMatch.phraseStart(words, query)
        if (p >= 0) return words[p].start to words[p + query.size - 1].end
        var best: WordSpan? = null
        for (w in words) {
            if (query.any { w.lower.startsWith(it) }) { best = w; break }
        }
        if (best != null) return best.start to best.end
        val lower = clean.lowercase()
        if (lower.length != clean.length) return null
        var bestIdx = -1
        var bestLen = 0
        for (q in query) {
            val i = lower.indexOf(q)
            if (i >= 0 && (bestIdx < 0 || i < bestIdx)) { bestIdx = i; bestLen = q.length }
        }
        return if (bestIdx >= 0) bestIdx to bestIdx + bestLen else null
    }

    /** Moves [pos] forward to the start of the next word, without passing [limit]. */
    private fun snapForward(s: String, pos: Int, limit: Int): Int {
        if (pos <= 0 || s[pos - 1].isWhitespace()) return pos
        val max = minOf(limit, pos + SNAP, s.length)
        var i = pos
        while (i < max && !s[i].isWhitespace()) i++
        return if (i < max) i + 1 else pos
    }

    /** Moves [pos] back to the end of the previous word, not before [limit]. */
    private fun snapBackward(s: String, pos: Int, limit: Int): Int {
        if (pos >= s.length || s[pos].isWhitespace()) return pos
        val min = maxOf(limit, pos - SNAP, 0)
        var i = pos
        while (i > min && !s[i - 1].isWhitespace()) i--
        return if (i > min) i - 1 else pos
    }
}
