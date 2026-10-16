package com.inkwell.notes.core.naming

import java.util.Locale

/**
 * Offline topic inference: no network, no key, deterministic.
 *
 * Text is split into clauses (lines, sentences, commas), tokenised, and scored as unigrams,
 * bigrams and trigrams. Each occurrence counts with the weight of where it appears
 * (headings > first lines > body), a boost for capitalised mid-sentence terms and acronyms,
 * and a length factor so a repeated phrase ("monetary policy") beats its parts. Only terms
 * that repeat (or appear in a heading and again elsewhere) can become the topic, and
 * stopwords plus note-taking filler never can. Returns null when there are fewer than
 * [minMeaningfulTokens] meaningful words or nothing repeats.
 */
class LocalTopicExtractor(
    private val minMeaningfulTokens: Int = 12,
) {
    /** A scored candidate phrase. [display] is already Title Cased. */
    data class Candidate(val key: String, val display: String, val words: Int, val count: Int, val score: Double)

    /** The best 1-5 word topic in Title Case, or null when the content is insufficient. */
    fun extract(content: NamingContent): String? {
        val analysis = analyse(content)
        if (analysis.meaningfulTokens < minMeaningfulTokens) return null
        return analysis.ranked.firstNotNullOfOrNull { DateNaming.cleanTopic(it.display) }
    }

    /** The most salient repeated terms (for the AI prompt), best first. */
    fun keyTerms(content: NamingContent, limit: Int = 8): List<String> {
        val ranked = analyse(content).ranked
        val picked = ArrayList<Candidate>()
        for (c in ranked) {
            if (picked.size >= limit) break
            // Skip a term already covered by a better phrase ("policy" after "monetary policy").
            if (picked.any { p -> containsWords(p.key, c.key) || containsWords(c.key, p.key) }) continue
            // Prefer a comparably strong phrase over one of its words ("central bank" over "central").
            if (c.words == 1 && ranked.any { p -> p.words > 1 && containsWords(p.key, c.key) && p.score >= c.score * 0.6 }) continue
            picked += c
        }
        return picked.map { it.display }
    }

    /** Number of meaningful (non-stopword) tokens; below ~3 even an AI cannot name the document. */
    fun meaningfulTokenCount(content: NamingContent): Int = analyse(content).meaningfulTokens

    internal data class Analysis(val meaningfulTokens: Int, val ranked: List<Candidate>)

    internal fun analyse(content: NamingContent): Analysis {
        val stats = HashMap<String, Stat>()
        var meaningful = 0
        var order = 0
        for ((text, kind) in weightedParts(content)) {
            for (clause in clauses(text)) {
                val tokens = tokenize(clause)
                meaningful += tokens.count { it.meaningful }
                for (i in tokens.indices) {
                    for (n in 1..3) {
                        if (i + n > tokens.size) break
                        val gram = tokens.subList(i, i + n)
                        if (!isCandidate(gram)) continue
                        val key = gram.joinToString(" ") { it.stem }
                        val stat = stats.getOrPut(key) { Stat(n, order++, lengthFactor(gram)) }
                        stat.add(gram, kind, startsClause = i == 0)
                    }
                }
            }
        }
        val ranked = stats.entries
            .filter { (_, s) -> s.count >= 2 || (s.inHeading && s.outsideHeading) }
            .map { (key, s) -> Candidate(key, s.display(), s.words, s.count, s.score * s.factor) }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.words }
                    .thenBy { stats.getValue(it.key).firstSeen },
            )
        return Analysis(meaningful, ranked)
    }

    private class Token(val surface: String, val stem: String, val meaningful: Boolean, val connector: Boolean) {
        val capitalised: Boolean get() = surface.first().isUpperCase()
        val acronym: Boolean get() = isAcronym(surface)
    }

    private class Stat(val words: Int, val firstSeen: Int, val factor: Double) {
        var count = 0
        var score = 0.0
        var inHeading = false
        var outsideHeading = false
        val surfaces = HashMap<String, Int>()

        fun add(gram: List<Token>, kind: SegmentKind, startsClause: Boolean) {
            count++
            var weight = kind.weight
            val content = gram.filter { !it.connector }
            val properNoun = content.all { it.capitalised } && (!startsClause || content.size >= 2)
            if (content.any { it.acronym }) weight *= ACRONYM_BOOST else if (properNoun) weight *= CAPITAL_BOOST
            score += weight
            if (kind == SegmentKind.HEADING) inHeading = true else outsideHeading = true
            val surface = gram.joinToString(" ") { it.surface }
            surfaces[surface] = (surfaces[surface] ?: 0) + 1
        }

        /** Most frequent spelling, preferring one that is not shouted in ALL CAPS. */
        fun display(): String {
            val best = surfaces.entries.maxWithOrNull(
                compareBy<Map.Entry<String, Int>> { it.value + if (isShouted(it.key)) 0.0 else 0.5 }.thenBy { it.key },
            )?.key.orEmpty()
            return titleCase(best)
        }
    }

    /** A lead counts only for its first sentence; whatever follows in the same segment is body text. */
    private fun weightedParts(content: NamingContent): List<Pair<String, SegmentKind>> = content.segments.flatMap { seg ->
        if (seg.kind != SegmentKind.LEAD) return@flatMap listOf(seg.text to seg.kind)
        val end = SENTENCE_END.find(seg.text)?.range?.first ?: return@flatMap listOf(seg.text to seg.kind)
        listOf(seg.text.substring(0, end) to SegmentKind.LEAD, seg.text.substring(end) to SegmentKind.BODY)
    }

    /**
     * Longer repeated phrases are more specific than their words. A list-like "X and Y" is
     * rewarded less than a concept such as "Bank of England" or "Bond Market Volatility".
     */
    private fun lengthFactor(gram: List<Token>): Double = when {
        gram.size == 1 -> 1.0
        gram.size == 2 -> 1.75
        gram[1].meaningful -> 2.1
        gram[1].stem == "and" || gram[1].stem == "&" -> 1.4
        else -> 1.9
    }

    private fun isCandidate(gram: List<Token>): Boolean = when (gram.size) {
        1 -> gram[0].meaningful
        2 -> gram[0].meaningful && gram[1].meaningful
        else -> gram[0].meaningful && gram[2].meaningful && (gram[1].meaningful || gram[1].connector)
    }

    private fun clauses(text: String): List<String> = CLAUSE_BREAK.split(text).filter { it.isNotBlank() }

    private fun tokenize(clause: String): List<Token> = WORD.findAll(clause.replace('’', '\'')).mapNotNull { m ->
        var surface = m.value.trim('\'', '-')
        if (surface.endsWith("'s", ignoreCase = true)) surface = surface.dropLast(2)
        if (surface.isEmpty() || surface.none { it.isLetter() }) return@mapNotNull null
        val key = surface.lowercase(Locale.ROOT)
        val connector = key in NamingStopwords.CONNECTORS
        val stop = NamingStopwords.isStop(key)
        val meaningful = !stop && !connector && (key.length >= 3 || isAcronym(surface))
        Token(surface, stem(key), meaningful, connector)
    }.toList()

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['\\-][\\p{L}\\p{N}]+)*")
        private val SENTENCE_END = Regex("[.!?\\n]")
        private val CLAUSE_BREAK = Regex("[\\r\\n.!?;:,()\\[\\]{}\"“”•·|/\\\\]+|\\s[-–—]+\\s|\\t")
        private const val CAPITAL_BOOST = 1.3
        private const val ACRONYM_BOOST = 1.25
        private val TITLE_MINOR = setOf("of", "and", "for", "in", "on", "the", "to", "a", "an")

        /** Groups simple English plurals ("yields" with "yield") without changing what is displayed. */
        internal fun stem(key: String): String = when {
            key.length > 4 && key.endsWith("ies") -> key.dropLast(3) + "y"
            key.length > 4 && key.endsWith("s") && !key.endsWith("ss") && !key.endsWith("us") && !key.endsWith("is") -> key.dropLast(1)
            else -> key
        }

        /** "GDP", "EU", "Q3", "COVID-19": short and without lowercase letters. */
        private fun isAcronym(s: String): Boolean {
            val letters = s.count { it.isLetter() }
            return letters in 1..5 && s.length >= 2 && s.none { it.isLowerCase() }
        }

        private fun isShouted(s: String): Boolean = s.count { it.isLetter() } > 4 && s.none { it.isLowerCase() }

        private fun containsWords(phrase: String, part: String): Boolean =
            phrase != part && " $phrase ".contains(" $part ")

        internal fun titleCase(phrase: String): String {
            val words = phrase.split(' ').filter { it.isNotEmpty() }
            return words.mapIndexed { i, w ->
                val lower = w.lowercase(Locale.ROOT)
                when {
                    // Acronyms and camel case keep their spelling ("GDP", "iPhone"); shouted words do not.
                    w.drop(1).any { it.isUpperCase() } && w.count { it.isLetter() } <= 5 -> w
                    w.drop(1).any { it.isUpperCase() } && w.any { it.isLowerCase() } -> w
                    i > 0 && i < words.lastIndex && lower in TITLE_MINOR -> lower
                    else -> lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
            }.joinToString(" ")
        }
    }
}
