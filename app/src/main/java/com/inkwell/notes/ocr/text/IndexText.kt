package com.inkwell.notes.ocr.text

/**
 * Prepares text for the full-text index. The FTS4 "simple" tokenizer treats every character
 * >= U+0080 as part of a word, so typographic punctuation, no-break spaces and symbols glue
 * words together ("Don’t" is one token that the query "don't" can never match; "hello world"
 * hides "world"). This maps such characters to their ASCII equivalents, or separates them from
 * the neighbouring words, so the index splits text exactly where [FtsQuery.tokens] splits a
 * query. Letters, digits and combining marks of every script are left untouched.
 */
object IndexText {
    private const val SOFT_HYPHEN = '­'
    private const val ELLIPSIS = '…'
    private const val SINGLE_QUOTES = "‘’‚‛‹›´′"

    fun normalize(text: String): String {
        if (text.none { it.code >= 128 || it == '\u0000' }) return text
        val sb = StringBuilder(text.length + 8)
        for (i in text.indices) {
            val c = text[i]
            when {
                c == '\u0000' -> sb.append(' ')
                c.code < 128 -> sb.append(c)
                c == SOFT_HYPHEN -> Unit
                FtsQuery.isTermChar(c) -> sb.append(c)
                c.isWhitespace() -> sb.append(' ')
                else -> {
                    val ascii = ascii(c)
                    if (ascii != null) {
                        sb.append(ascii)
                    } else {
                        // A symbol the query tokenizer skips: keep it, but never glued to a word.
                        if (i > 0 && FtsQuery.isTermChar(text[i - 1])) sb.append(' ')
                        sb.append(c)
                        if (i + 1 < text.length && FtsQuery.isTermChar(text[i + 1])) sb.append(' ')
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * [index] moved off the middle of a surrogate pair (to its start), so cutting [s] there never
     * leaves half an emoji behind. Other indices are returned unchanged.
     */
    fun safeCut(s: String, index: Int): Int =
        if (index in 1 until s.length && s[index].isLowSurrogate() && s[index - 1].isHighSurrogate()) index - 1 else index

    /** The first at most [max] chars of [s], never ending with half a surrogate pair. */
    fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, safeCut(s, max.coerceAtLeast(0)))

    /** ASCII stand-in for typographic punctuation, or null when there is none. */
    private fun ascii(c: Char): String? {
        if (c == ELLIPSIS) return "..."
        if (c in SINGLE_QUOTES) return "'"
        return when (Character.getType(c)) {
            Character.DASH_PUNCTUATION.toInt() -> "-"
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt() -> "\""
            else -> null
        }
    }
}
