package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.index.IndexWriter
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FTS4 "simple" tokenizer treats every character >= U+0080 as part of a word. Typographic
 * punctuation and no-break spaces (common in PDF text, OCR output and smart-quote keyboards)
 * therefore glue words together in the index: "Don’t" becomes one token that the query for
 * "don't" (tokens "don", "t") can never match, and "hello world" hides "world" entirely.
 * [IndexWriter.clean] has to normalise such characters so the index splits where the query does.
 */
class IndexTextCleaningTest {

    @Test
    fun typographicApostrophesAndQuotesBecomeAscii() {
        assertEquals("Don't panic", IndexWriter.clean("Don’t panic"))
        assertEquals("Keynes's 'general' theory", IndexWriter.clean("Keynes’s ‘general’ theory"))
        assertEquals("\"quoted\" text", IndexWriter.clean("“quoted” text"))
        assertEquals("\"guillemets\"", IndexWriter.clean("«guillemets»"))
    }

    @Test
    fun noBreakAndThinSpacesSeparateWords() {
        assertEquals("hello world", IndexWriter.clean("hello world"))
        assertEquals("1 000 000", IndexWriter.clean("1 000 000"))
    }

    @Test
    fun dashesAndEllipsesBecomeAscii() {
        assertEquals("2024-2025 - a range", IndexWriter.clean("2024–2025 — a range"))
        assertEquals("wait... what", IndexWriter.clean("wait… what"))
    }

    @Test
    fun symbolsGluedToWordsAreSeparatedButKept() {
        assertEquals("€ 50 budget", IndexWriter.clean("€50 budget"))
        assertEquals("a → b", IndexWriter.clean("a→b"))
        // Already separated: nothing changes.
        assertEquals("€ 50", IndexWriter.clean("€ 50"))
    }

    @Test
    fun lettersDigitsAndMarksOfEveryScriptAreUntouched() {
        assertEquals("École naïve Übung", IndexWriter.clean("École naïve Übung"))
        assertEquals("日本語 テキスト", IndexWriter.clean("日本語 テキスト"))
        // Decomposed accents (combining marks) stay attached to their letter.
        assertEquals("café", IndexWriter.clean("café"))
    }

    @Test
    fun cleanedTextSplitsIntoTheSameWordsAsAQueryWould() {
        val samples = listOf(
            "Don’t panic", "hello world", "€50", "wait… what", "l’école « normale »",
            "a→b→c", " leading and trailing ",
        )
        for (s in samples) {
            val cleaned = IndexWriter.clean(s)!!
            // Every character the query tokenizer would skip must also break words for the
            // simple tokenizer: it has to be ASCII or whitespace, never a glued >= U+0080 symbol.
            cleaned.forEachIndexed { i, c ->
                if (c.code >= 128 && !FtsQuery.isTermChar(c) && !c.isWhitespace()) {
                    val before = cleaned.getOrNull(i - 1)
                    val after = cleaned.getOrNull(i + 1)
                    assertTrue("'$c' glued to a word in '$cleaned'", before == null || !FtsQuery.isTermChar(before))
                    assertTrue("'$c' glued to a word in '$cleaned'", after == null || !FtsQuery.isTermChar(after))
                }
            }
            // And the words the ranker sees are the words the index sees.
            assertEquals(TextMatch.words(s).map { it.lower }, TextMatch.words(cleaned).map { it.lower })
        }
    }

    @Test
    fun blankAndNulOnlyTextIsDropped() {
        assertNull(IndexWriter.clean(""))
        assertNull(IndexWriter.clean("   \u0000  "))
        assertEquals("a b", IndexWriter.clean("a\u0000b"))
    }

    @Test
    fun veryLongTextIsCapped() {
        val long = "x".repeat(IndexWriter.MAX_ROW_CHARS + 100)
        assertEquals(IndexWriter.MAX_ROW_CHARS, IndexWriter.clean(long)!!.length)
    }
}
