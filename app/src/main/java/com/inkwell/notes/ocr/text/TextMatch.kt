package com.inkwell.notes.ocr.text

/** A word of a text: [start] until [end] (exclusive) and its lower-cased form. */
data class WordSpan(val start: Int, val end: Int, val lower: String)

/** How well a piece of text matches a query, strongest first. */
enum class MatchQuality {
    /** The query words appear consecutively (the last one may be a prefix). */
    PHRASE,
    /** Every query word starts some word of the text. */
    ALL_TOKENS,
    /** Every query word occurs inside the text, not necessarily at a word start ("arket" in "Markets"). */
    SUBSTRING,
    /** Only some query words occur. */
    PARTIAL,
    NONE,
}

/** Word-level matching shared by ranking, excerpts and highlighting. */
object TextMatch {
    /** Words of [text] split like the search index, with their positions. */
    fun words(text: String): List<WordSpan> {
        val out = ArrayList<WordSpan>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!FtsQuery.isTermChar(text[i])) { i++; continue }
            val start = i
            var transparent = false
            while (i < n && (FtsQuery.isTermChar(text[i]) || FtsQuery.isTransparent(text[i]))) {
                if (FtsQuery.isTransparent(text[i])) transparent = true
                i++
            }
            // Invisible format characters (soft hyphens, joiners) are not part of the word, like in queries.
            val raw = text.substring(start, i)
            out += WordSpan(start, i, (if (transparent) raw.filterNot { FtsQuery.isTransparent(it) } else raw).lowercase())
        }
        return out
    }

    /** Index of the first word where the whole query starts as a phrase, or -1. */
    fun phraseStart(words: List<WordSpan>, query: List<String>): Int {
        if (query.isEmpty() || words.size < query.size) return -1
        val last = query.size - 1
        outer@ for (i in 0..words.size - query.size) {
            for (k in query.indices) {
                val w = words[i + k].lower
                val ok = if (k == last) w.startsWith(query[k]) else w == query[k]
                if (!ok) continue@outer
            }
            return i
        }
        return -1
    }

    fun quality(text: String, query: List<String>): MatchQuality = quality(text, words(text), query)

    fun quality(text: String, words: List<WordSpan>, query: List<String>): MatchQuality {
        if (query.isEmpty()) return MatchQuality.NONE
        if (query.size == 1) {
            val q = query[0]
            if (words.any { it.lower == q }) return MatchQuality.PHRASE
            if (words.any { it.lower.startsWith(q) }) return MatchQuality.ALL_TOKENS
        } else {
            if (phraseStart(words, query) >= 0) return MatchQuality.PHRASE
            if (query.all { q -> words.any { it.lower.startsWith(q) } }) return MatchQuality.ALL_TOKENS
        }
        val lower = text.lowercase()
        val contained = query.count { lower.contains(it) }
        return when {
            contained == query.size -> MatchQuality.SUBSTRING
            contained > 0 || query.any { q -> words.any { it.lower.startsWith(q) } } -> MatchQuality.PARTIAL
            else -> MatchQuality.NONE
        }
    }

    /** Fraction (0..1) of query words that occur as complete words. */
    fun exactWordFraction(words: List<WordSpan>, query: List<String>): Double {
        if (query.isEmpty()) return 0.0
        val set = words.mapTo(HashSet()) { it.lower }
        return query.count { it in set }.toDouble() / query.size
    }

    /** Fraction (0..1) of query words that start some word, or occur anywhere in the text. */
    fun coverage(text: String, words: List<WordSpan>, query: List<String>): Double {
        if (query.isEmpty()) return 0.0
        val lower = text.lowercase()
        return query.count { q -> words.any { it.lower.startsWith(q) } || lower.contains(q) }.toDouble() / query.size
    }

    /**
     * Character ranges in [text] to highlight for [query]: every word that starts with a query
     * word (the whole word, so "financ" highlights "financial"), falling back to raw substring
     * occurrences when a query word only appears inside other words. Sorted, non-overlapping.
     */
    fun highlightRanges(text: String, query: List<String>): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        val words = words(text)
        val lower = text.lowercase()
        for (q in query) {
            var found = false
            for (w in words) {
                if (w.lower.startsWith(q)) {
                    ranges += w.start until w.end
                    found = true
                }
            }
            if (!found && q.isNotEmpty() && lower.length == text.length) {
                var from = 0
                while (true) {
                    val i = lower.indexOf(q, from)
                    if (i < 0) break
                    ranges += i until (i + q.length)
                    from = i + q.length
                }
            }
        }
        return mergeRanges(ranges)
    }

    fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted.firstOrNull() ?: return emptyList()
        for (r in sorted.drop(1)) {
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else { out += cur; r }
        }
        out += cur
        return out
    }

    /** Collapses whitespace runs (including line breaks) to single spaces and trims. */
    fun collapseWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var space = false
        for (c in text) {
            if (c.isWhitespace()) {
                space = true
            } else {
                if (space && sb.isNotEmpty()) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
