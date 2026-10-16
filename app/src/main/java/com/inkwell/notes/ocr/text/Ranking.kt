package com.inkwell.notes.ocr.text

import com.inkwell.notes.core.model.TextSource
import kotlin.math.exp

/** What the ranker needs to know about one candidate. */
data class RankInput(
    val text: String,
    val source: TextSource,
    /** Last modification of the document (epoch ms). */
    val modifiedAt: Long,
    /** Recognition confidence of the passage, null when unknown or not recognised. */
    val confidence: Float? = null,
    /** Cosine similarity to the query embedding, when semantic search ran. */
    val semantic: Float? = null,
)

/**
 * Scores search candidates. The weights are layered so the documented priority holds:
 * exact phrase > all words > title boost > recency. Each tier's weight exceeds the sum of every
 * weaker signal, so e.g. a phrase in a page body always outranks an all-words title match.
 */
object Ranking {
    const val PHRASE = 10.0
    const val ALL_TOKENS = 6.0
    const val SUBSTRING = 4.5
    const val PARTIAL_MAX = 1.0
    const val TITLE_BOOST = 2.0
    const val TAG_BOOST = 1.5
    const val FOLDER_BOOST = 1.0
    const val RECENCY_MAX = 1.0
    const val EXACT_WORD_MAX = 0.5
    /** Low-confidence recognition loses at most this much. */
    const val CONFIDENCE_PENALTY_MAX = 0.3
    /** A purely semantic match scores this times its similarity (well below keyword matches). */
    const val SEMANTIC_ONLY = 4.0
    /** Bonus when a keyword match is also semantically close. */
    const val SEMANTIC_BONUS = 2.0
    private const val RECENCY_HALF_DAYS = 30.0
    private const val DAY_MS = 86_400_000.0

    fun score(input: RankInput, query: List<String>, now: Long): Double {
        val words = TextMatch.words(input.text)
        val quality = TextMatch.quality(input.text, words, query)
        val base = when (quality) {
            MatchQuality.PHRASE -> PHRASE
            MatchQuality.ALL_TOKENS -> ALL_TOKENS
            MatchQuality.SUBSTRING -> SUBSTRING
            MatchQuality.PARTIAL -> PARTIAL_MAX * TextMatch.coverage(input.text, words, query)
            MatchQuality.NONE -> 0.0
        }
        val keyword = quality != MatchQuality.NONE
        var s = base
        if (keyword) {
            s += sourceBoost(input.source)
            s += EXACT_WORD_MAX * TextMatch.exactWordFraction(words, query)
            s += recency(input.modifiedAt, now)
            input.confidence?.let { c -> s -= CONFIDENCE_PENALTY_MAX * (1.0 - c.coerceIn(0f, 1f)) }
        }
        input.semantic?.let { sim ->
            val v = sim.toDouble().coerceIn(0.0, 1.0)
            s += if (keyword) SEMANTIC_BONUS * v else SEMANTIC_ONLY * v + recency(input.modifiedAt, now) * 0.25
        }
        return s
    }

    fun sourceBoost(source: TextSource): Double = when (source) {
        TextSource.TITLE -> TITLE_BOOST
        TextSource.TAG -> TAG_BOOST
        TextSource.FOLDER -> FOLDER_BOOST
        else -> 0.0
    }

    /** 0..[RECENCY_MAX], halving every [RECENCY_HALF_DAYS] days. */
    fun recency(modifiedAt: Long, now: Long): Double {
        if (modifiedAt <= 0) return 0.0
        val days = ((now - modifiedAt).coerceAtLeast(0L)) / DAY_MS
        return RECENCY_MAX * exp(-days * kotlin.math.ln(2.0) / RECENCY_HALF_DAYS)
    }

    /**
     * Merges keyword and semantic candidates keyed by [key]: a candidate found both ways keeps
     * one entry whose semantic similarity is attached. Order of first appearance is kept.
     */
    fun <T> merge(keyword: List<T>, semantic: List<Pair<T, Float>>, key: (T) -> Any, withSemantic: (T, Float) -> T): List<T> {
        val out = LinkedHashMap<Any, T>()
        for (k in keyword) out.putIfAbsent(key(k), k)
        for ((item, sim) in semantic) {
            val id = key(item)
            val existing = out[id]
            out[id] = withSemantic(existing ?: item, sim)
        }
        return out.values.toList()
    }
}
