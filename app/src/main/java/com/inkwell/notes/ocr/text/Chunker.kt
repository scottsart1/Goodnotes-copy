package com.inkwell.notes.ocr.text

import com.inkwell.notes.ocr.IndexedPassage
import kotlin.math.ln

/** An indexed passage with the text-index row it came from. */
data class SourcedPassage(val rowId: Long, val passage: IndexedPassage)

/**
 * About [Chunker.TARGET] characters of consecutive text from one page, one recording or one
 * typed document. [position] is its place in reading order.
 */
data class Chunk(
    val docId: String,
    val group: String,
    val position: Int,
    val parts: List<IndexedPassage>,
    val rowIds: Set<Long>,
) {
    val text: String = parts.joinToString("\n") { it.text.trim() }

    /** One passage for the whole chunk, located at its first part. */
    fun toPassage(textOverride: String? = null): IndexedPassage {
        val first = parts.first()
        return first.copy(text = textOverride ?: text)
    }
}

/**
 * Retrieval for AI context: chunks a document in reading order, scores chunks against the
 * question (BM25-style keyword weights, optionally semantic similarity), and picks the best
 * chunks with their neighbours within a character budget, returned in reading order.
 */
object Chunker {
    const val TARGET = 600
    const val MAX = 900
    private const val PRIMARY_SHARE = 0.7

    private val STOP_WORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "did", "do", "does", "for", "from", "had", "has",
        "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my", "no", "not", "of", "on", "or", "our", "so",
        "that", "the", "their", "them", "then", "there", "these", "they", "this", "to", "us", "was", "we", "were", "what",
        "when", "where", "which", "who", "why", "will", "with", "you", "your", "about", "please", "tell", "explain",
        "summarize", "summarise", "give", "show", "list", "find",
    )

    /** Query words worth scoring: lower-cased terms without stop words or single letters. */
    fun queryTerms(question: String): List<String> {
        val all = FtsQuery.matchTokens(question)
        val useful = all.filter { it.length > 1 && it !in STOP_WORDS }
        return useful.ifEmpty { all.filter { it.length > 1 } }
    }

    fun chunk(passages: List<SourcedPassage>, target: Int = TARGET, max: Int = MAX): List<Chunk> {
        val out = ArrayList<Chunk>()
        val positions = HashMap<String, Int>()
        var parts = ArrayList<IndexedPassage>()
        var rows = LinkedHashSet<Long>()
        var group: String? = null
        var docId: String? = null
        var length = 0

        fun flush() {
            val d = docId
            val g = group
            if (parts.isNotEmpty() && d != null && g != null) {
                val pos = positions.getOrDefault(d, 0)
                positions[d] = pos + 1
                out += Chunk(d, g, pos, parts, rows)
            }
            parts = ArrayList()
            rows = LinkedHashSet()
            length = 0
        }

        for (sp in passages) {
            val p = sp.passage
            val text = p.text.trim()
            if (text.isEmpty()) continue
            val g = groupOf(p)
            if (g != group || p.documentId != docId) {
                flush()
                group = g
                docId = p.documentId
            }
            val pieces = if (text.length > max) TextSplitter.split(text, target, max) else listOf(text)
            for (piece in pieces) {
                if (parts.isNotEmpty() && length + 1 + piece.length > target) flush()
                parts += p.copy(text = piece)
                rows += sp.rowId
                length += piece.length + if (parts.size > 1) 1 else 0
            }
        }
        flush()
        return out
    }

    private fun groupOf(p: IndexedPassage): String = when {
        p.recordingId != null -> "rec:${p.recordingId}"
        p.pageId != null -> "page:${p.pageId}"
        else -> "doc"
    }

    /** Keyword relevance of each chunk for [terms] (see [queryTerms]); 0 when nothing matches. */
    fun keywordScores(chunks: List<Chunk>, terms: List<String>): DoubleArray {
        val n = chunks.size
        if (n == 0 || terms.isEmpty()) return DoubleArray(n)
        val words = chunks.map { TextMatch.words(it.text) }
        val df = terms.map { q -> words.count { ws -> ws.any { it.lower.startsWith(q) } } }
        return DoubleArray(n) { i ->
            val ws = words[i]
            var s = 0.0
            terms.forEachIndexed { k, q ->
                val tf = ws.count { it.lower.startsWith(q) }
                if (tf > 0) {
                    val idf = ln(1.0 + (n - df[k] + 0.5) / (df[k] + 0.5))
                    s += idf * (tf * 2.2) / (tf + 1.2)
                }
            }
            if (s > 0 && terms.size > 1 && TextMatch.phraseStart(ws, terms) >= 0) s *= 1.5
            s
        }
    }

    /**
     * Combines keyword scores (normalised to 0..1) with optional semantic similarities
     * (cosine, per chunk; null entries = no vector).
     */
    fun combine(keyword: DoubleArray, semantic: List<Float?>?): DoubleArray {
        val maxKw = keyword.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return DoubleArray(keyword.size) { i ->
            val kw = keyword[i] / maxKw
            val sem = semantic?.getOrNull(i)?.let { (it - SEMANTIC_FLOOR).coerceAtLeast(0f) / (1f - SEMANTIC_FLOOR) } ?: 0f
            kw + 1.5 * sem
        }
    }

    /** Below this cosine a chunk is treated as unrelated. */
    const val SEMANTIC_FLOOR = 0.2f

    /**
     * The best chunks and their neighbours within [maxChars], in reading order. Falls back to
     * the opening chunks when nothing scores above zero.
     */
    fun select(chunks: List<Chunk>, scores: DoubleArray, maxChars: Int, neighbours: Int = 1): List<Chunk> {
        if (chunks.isEmpty() || maxChars <= 0) return emptyList()
        val ranked = chunks.indices.filter { scores.getOrElse(it) { 0.0 } > 0.0 }.sortedByDescending { scores[it] }
        if (ranked.isEmpty()) return opening(chunks, maxChars)
        val chosen = sortedSetOf<Int>()
        var used = 0
        fun cost(i: Int) = chunks[i].text.length + 1
        fun tryAdd(i: Int, budget: Int): Boolean {
            if (i !in chunks.indices || i in chosen) return false
            if (used + cost(i) > budget) return false
            chosen += i
            used += cost(i)
            return true
        }
        val primaryBudget = (maxChars * PRIMARY_SHARE).toInt().coerceAtLeast(1)
        for (i in ranked) {
            if (chosen.isEmpty()) {
                if (!tryAdd(i, maxChars)) return listOf(truncated(chunks[i], maxChars))
            } else if (!tryAdd(i, primaryBudget)) {
                break
            }
        }
        val hits = ranked.filter { it in chosen }
        for (i in hits) {
            for (d in 1..neighbours) {
                for (j in intArrayOf(i - d, i + d)) {
                    if (j in chunks.indices && chunks[j].docId == chunks[i].docId) tryAdd(j, maxChars)
                }
            }
        }
        for (i in ranked) tryAdd(i, maxChars)
        return chosen.map { chunks[it] }
    }

    /** Opening chunks of each document in order, within [maxChars]. */
    fun opening(chunks: List<Chunk>, maxChars: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        var used = 0
        for (c in chunks) {
            val cost = c.text.length + 1
            if (used + cost > maxChars) {
                if (out.isEmpty()) out += truncated(c, maxChars)
                break
            }
            out += c
            used += cost
        }
        return out
    }

    /** [c] cut to [maxChars] at a word boundary. */
    fun truncated(c: Chunk, maxChars: Int): Chunk {
        if (c.text.length <= maxChars) return c
        val cut = c.text.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
        val text = c.text.substring(0, cut).trimEnd()
        return c.copy(parts = listOf(c.parts.first().copy(text = text)))
    }
}
