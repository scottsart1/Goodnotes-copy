package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.text.Excerpts
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.MatchQuality
import com.inkwell.notes.ocr.text.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptsTest {
    private fun terms(q: String) = FtsQuery.matchTokens(q)

    private fun matched(e: com.inkwell.notes.ocr.text.Excerpt) = e.text.substring(e.matchStart, e.matchEnd)

    @Test
    fun shortTextIsKeptWholeWithTheMatchedWord() {
        val e = Excerpts.build("Notes on Financial Markets", terms("financ"))
        assertEquals("Notes on Financial Markets", e.text)
        assertEquals("Financial", matched(e))
    }

    @Test
    fun phraseMatchCoversAllWords() {
        val e = Excerpts.build("The central bank raised interest rates again.", terms("interest rate"))
        assertEquals("interest rates", matched(e))
    }

    @Test
    fun whitespaceAndLineBreaksAreCollapsed() {
        val e = Excerpts.build("first line\n\n   second\tline", terms("second"))
        assertEquals("first line second line", e.text)
        assertEquals("second", matched(e))
    }

    @Test
    fun longTextGetsAWindowAroundTheMatchWithEllipses() {
        val before = (1..60).joinToString(" ") { "alpha$it" }
        val after = (1..60).joinToString(" ") { "omega$it" }
        val e = Excerpts.build("$before inflation $after", terms("inflation"))
        assertTrue(e.text.startsWith(Excerpts.ELLIPSIS))
        assertTrue(e.text.endsWith(Excerpts.ELLIPSIS))
        assertTrue("length ${e.text.length}", e.text.length <= Excerpts.DEFAULT_LENGTH + 2)
        assertEquals("inflation", matched(e))
        // Some context is kept before the match.
        assertTrue(e.matchStart > 20)
        // Cut at word boundaries: no partial "alphaNN" words at the edges.
        val inner = e.text.removePrefix(Excerpts.ELLIPSIS).removeSuffix(Excerpts.ELLIPSIS)
        assertTrue(inner.split(' ').first().matches(Regex("alpha\\d+")))
        assertTrue(inner.split(' ').last().matches(Regex("omega\\d+")))
    }

    @Test
    fun matchNearTheEndShiftsTheWindowInsteadOfTrailingEllipsis() {
        val before = (1..80).joinToString(" ") { "word$it" }
        val e = Excerpts.build("$before deflation", terms("deflation"))
        assertFalse(e.text.endsWith(Excerpts.ELLIPSIS))
        assertTrue(e.text.startsWith(Excerpts.ELLIPSIS))
        assertEquals("deflation", matched(e))
    }

    @Test
    fun withoutMatchTheOpeningIsShown() {
        val text = (1..100).joinToString(" ") { "w$it" }
        val e = Excerpts.build(text, terms("zzz"))
        assertEquals(-1, e.matchStart)
        assertEquals(-1, e.matchEnd)
        assertTrue(e.text.startsWith("w1 w2"))
        assertTrue(e.text.endsWith(Excerpts.ELLIPSIS))
    }

    @Test
    fun infixMatchFallsBackToSubstring() {
        val e = Excerpts.build("Financial Markets", terms("arket"))
        assertEquals("arket", matched(e))
    }

    @Test
    fun highlightRangesCoverEveryMatchingWordAndMerge() {
        val text = "Markets and market makers: marketing"
        val ranges = TextMatch.highlightRanges(text, terms("market"))
        assertEquals(listOf("Markets", "market", "marketing"), ranges.map { text.substring(it.first, it.last + 1) })
        val merged = TextMatch.mergeRanges(listOf(0..4, 3..8, 10..12, 13..14))
        assertEquals(listOf(0..8, 10..14), merged)
    }

    @Test
    fun matchQualityLevels() {
        assertEquals(MatchQuality.PHRASE, TextMatch.quality("interest rates rose", terms("interest rat")))
        assertEquals(MatchQuality.ALL_TOKENS, TextMatch.quality("rates of interest", terms("interest rates")))
        assertEquals(MatchQuality.SUBSTRING, TextMatch.quality("Financial Markets", terms("arket")))
        assertEquals(MatchQuality.PARTIAL, TextMatch.quality("interest only", terms("interest rates")))
        assertEquals(MatchQuality.NONE, TextMatch.quality("nothing here", terms("zebra")))
        // A single word: whole word beats prefix.
        assertEquals(MatchQuality.PHRASE, TextMatch.quality("the market", terms("market")))
        assertEquals(MatchQuality.ALL_TOKENS, TextMatch.quality("the markets", terms("market")))
    }
}
