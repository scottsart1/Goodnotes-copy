package com.inkwell.notes.ocr.text

/**
 * Turns free text typed by the user into a safe SQLite FTS4 MATCH expression.
 *
 * The index uses FTS4's "simple" tokenizer: a term is a run of ASCII letters/digits or of
 * characters >= U+0080, and only ASCII is case-folded. Queries are split the same way, every
 * term is quoted (so words like OR / NOT / NEAR and stray quotes or dashes never act as
 * operators) and terms are combined with the implicit AND. Terms of two or more characters
 * match as prefixes so results appear while the user is still typing.
 */
object FtsQuery {
    const val MAX_TOKENS = 12
    const val MIN_PREFIX_LENGTH = 2
    private const val MAX_TOKEN_LENGTH = 64

    /** Search terms of [query] in order, de-duplicated, ASCII lower-cased (like the tokenizer). */
    fun tokens(query: String): List<String> {
        val out = LinkedHashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out += sb.toString().take(MAX_TOKEN_LENGTH)
                sb.setLength(0)
            }
        }
        for (ch in query) {
            if (isTermChar(ch)) sb.append(asciiLower(ch)) else flush()
            if (out.size >= MAX_TOKENS) break
        }
        flush()
        return out.take(MAX_TOKENS)
    }

    /**
     * Terms for matching in Kotlin (excerpts, ranking): like [tokens] but fully lower-cased, so
     * "École" and "école" compare equal.
     */
    fun matchTokens(query: String): List<String> = tokens(query).map { it.lowercase() }.distinct()

    /** The MATCH expression for [tokens], or null when there is nothing to search for. */
    fun build(tokens: List<String>): String? {
        val terms = tokens.filter { it.isNotBlank() }.take(MAX_TOKENS)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { t ->
            val escaped = escape(t)
            if (t.length >= MIN_PREFIX_LENGTH) "\"$escaped*\"" else "\"$escaped\""
        }
    }

    /** Convenience: [build] of [tokens] of [query]. */
    fun forQuery(query: String): String? = build(tokens(query))

    /** Doubles embedded quotes, the only character that is special inside an FTS phrase. */
    fun escape(term: String): String = term.replace("\"", "\"\"")

    /** Escapes LIKE wildcards for use with `ESCAPE '\'`. */
    fun escapeLike(text: String): String = buildString(text.length + 4) {
        for (c in text) {
            if (c == '%' || c == '_' || c == '\\') append('\\')
            append(c)
        }
    }

    /**
     * A character that belongs to a search term. Mirrors the simple tokenizer for ASCII; for
     * other characters only letters, digits and combining marks count, so typographic quotes,
     * dashes or ellipses typed in a query never become part of a term.
     */
    fun isTermChar(c: Char): Boolean = when {
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> true
        c.code < 128 -> false
        else -> Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') (c + 32) else c
}
