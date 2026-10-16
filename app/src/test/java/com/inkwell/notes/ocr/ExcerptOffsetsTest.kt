package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.text.Excerpt
import com.inkwell.notes.ocr.text.Excerpts
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.TextMatch
import com.inkwell.notes.ocr.text.TextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excerpt windows and match offsets with multi-byte text and matches at the edges. */
class ExcerptOffsetsTest {
    private fun terms(q: String) = FtsQuery.matchTokens(q)
    private fun matched(e: Excerpt) = e.text.substring(e.matchStart, e.matchEnd)
    private val filler = (1..80).joinToString(" ") { "word$it" }

    /** True when every surrogate in [s] is part of a proper high/low pair. */
    private fun wellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate()) return false
                i++
            }
        }
        return true
    }

    @Test
    fun offsetsAreUtf16UnitsAndSurviveEmojiBeforeTheMatch() {
        val e = Excerpts.build("😀 note 😀 about inflation today", terms("inflation"))
        assertEquals("inflation", matched(e))
        assertEquals(e.text.indexOf("inflation"), e.matchStart)
    }

    @Test
    fun matchAtTheVeryStartOfALongTextKeepsNoLeadingEllipsis() {
        val e = Excerpts.build("Inflation $filler", terms("inflation"))
        assertFalse(e.text.startsWith(Excerpts.ELLIPSIS))
        assertEquals(0, e.matchStart)
        assertEquals("Inflation", matched(e))
        assertTrue(e.text.endsWith(Excerpts.ELLIPSIS))
    }

    @Test
    fun matchAtTheVeryEndOfALongTextIsFullyVisible() {
        val e = Excerpts.build("$filler deflation", terms("deflation"))
        assertEquals("deflation", matched(e))
        assertEquals(e.text.length, e.matchEnd)
    }

    @Test
    fun aPhraseLongerThanTheWindowIsStillCoveredCompletely() {
        // Eleven words (the query is capped at 12 tokens) of 14+ characters: longer than the window.
        val phrase = (1..11).joinToString(" ") { "extraordinarily$it" }
        val e = Excerpts.build("$filler $phrase $filler", terms(phrase))
        assertEquals(phrase, matched(e))
    }

    @Test
    fun excerptWindowsNeverSplitASurrogatePair() {
        // 60 emoji with no space to snap to, then the match: the window start would fall inside a pair.
        val emoji = "😀".repeat(60)
        val e = Excerpts.build("$emoji inflation $filler", terms("inflation"))
        assertTrue("excerpt holds a lone surrogate: ${e.text}", wellFormed(e.text))
        assertEquals("inflation", matched(e))
        // The window end inside an emoji run after the match.
        val tail = Excerpts.build("$filler inflation $emoji$emoji", terms("inflation"))
        assertTrue(wellFormed(tail.text))
        assertEquals("inflation", matched(tail))
        // The plain opening of a text without a match.
        val head = Excerpts.build(emoji + emoji, terms("zzz"))
        assertTrue(wellFormed(head.text))
    }

    @Test
    fun splitPassagesNeverEndOrStartWithHalfAnEmoji() {
        val pieces = TextSplitter.split("😀".repeat(700), target = 600, max = 901)
        assertTrue(pieces.size > 1)
        pieces.forEach { assertTrue("piece holds a lone surrogate", wellFormed(it)) }
        assertEquals("😀".repeat(700), pieces.joinToString(""))
    }

    @Test
    fun highlightRangesUseTheSameOffsetsAsTheExcerpt() {
        val text = "Café 😀 Financial markets and the market"
        val ranges = TextMatch.highlightRanges(text, terms("market"))
        assertEquals(listOf("markets", "market"), ranges.map { text.substring(it.first, it.last + 1) })
    }
}
