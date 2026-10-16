package com.inkwell.notes.typed

import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.typed.model.InlineStyle
import com.inkwell.notes.typed.model.Marks
import com.inkwell.notes.typed.model.SpanOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanOpsTest {
    private fun bold(s: Int, e: Int) = TextSpan(s, e, bold = true)

    @Test fun insertBeforeSpanShiftsIt() {
        // "hello world" with "world" bold; insert "big " at 6.
        val out = SpanOps.replace(listOf(bold(6, 11)), 11, 6, 0, 4)
        // Inserting at the start of a bold run does not extend it (char before is plain).
        assertEquals(listOf(bold(10, 15)), out)
    }

    @Test fun insertInsideSpanExtendsIt() {
        val out = SpanOps.replace(listOf(bold(0, 5)), 11, 2, 0, 3)
        assertEquals(listOf(bold(0, 8)), out)
    }

    @Test fun typingAtEndOfSpanContinuesIt() {
        val out = SpanOps.replace(listOf(bold(0, 5)), 5, 5, 0, 1)
        assertEquals(listOf(bold(0, 6)), out)
    }

    @Test fun insertAfterSpanLeavesItAlone() {
        val out = SpanOps.replace(listOf(bold(0, 3)), 10, 7, 0, 2)
        assertEquals(listOf(bold(0, 3)), out)
    }

    @Test fun deleteInsideSpanShrinksIt() {
        val out = SpanOps.replace(listOf(bold(2, 8)), 10, 3, 2, 0)
        assertEquals(listOf(bold(2, 6)), out)
    }

    @Test fun deleteAcrossSpanStartClampsIt() {
        // delete [0,4) of a text where [2,8) is bold -> bold [0,4)
        val out = SpanOps.replace(listOf(bold(2, 8)), 10, 0, 4, 0)
        assertEquals(listOf(bold(0, 4)), out)
    }

    @Test fun deleteWholeSpanRemovesIt() {
        val out = SpanOps.replace(listOf(bold(2, 5)), 10, 1, 6, 0)
        assertTrue(out.isEmpty())
    }

    @Test fun replaceSelectionInheritsStyleOfPrecedingChar() {
        // "abcdef", bold [0,3); replace "cd" (2..4) with "XYZ" -> inherits bold from 'b'
        val out = SpanOps.replace(listOf(bold(0, 3)), 6, 2, 2, 3)
        assertEquals(listOf(bold(0, 5)), out)
    }

    @Test fun pendingMarksOverrideInheritance() {
        val out = SpanOps.replace(emptyList(), 5, 5, 0, 3, insertMarks = Marks(italic = true))
        assertEquals(listOf(TextSpan(5, 8, italic = true)), out)
    }

    @Test fun codeAndLinksDoNotLeakAtTheirEnd() {
        val code = listOf(TextSpan(0, 4, code = true))
        assertTrue(SpanOps.replace(code, 4, 4, 0, 1).single().let { it.start == 0 && it.end == 4 })
        val link = listOf(TextSpan(0, 4, link = "https://a.b"))
        assertEquals(listOf(TextSpan(0, 4, link = "https://a.b")), SpanOps.replace(link, 4, 4, 0, 2))
        // ...but typing inside them continues them.
        assertEquals(listOf(TextSpan(0, 6, link = "https://a.b")), SpanOps.replace(link, 4, 2, 0, 2))
    }

    @Test fun typingAtStartContinuesFollowingBasicStyles() {
        val out = SpanOps.replace(listOf(TextSpan(0, 3, bold = true, code = true)), 3, 0, 0, 2)
        assertEquals(listOf(TextSpan(0, 2, bold = true), TextSpan(2, 5, bold = true, code = true)), out)
    }

    @Test fun toggleAppliesThenRemoves() {
        val on = SpanOps.toggle(emptyList(), 10, 2, 6, InlineStyle.BOLD)
        assertEquals(listOf(bold(2, 6)), on)
        val off = SpanOps.toggle(on, 10, 2, 6, InlineStyle.BOLD)
        assertTrue(off.isEmpty())
    }

    @Test fun togglePartialRangeExtendsToWholeRange() {
        val out = SpanOps.toggle(listOf(bold(0, 3)), 10, 2, 6, InlineStyle.BOLD)
        assertEquals(listOf(bold(0, 6)), out)
    }

    @Test fun overlappingStylesSplitIntoRuns() {
        val b = SpanOps.toggle(emptyList(), 10, 0, 6, InlineStyle.BOLD)
        val bi = SpanOps.toggle(b, 10, 3, 9, InlineStyle.ITALIC)
        assertEquals(
            listOf(TextSpan(0, 3, bold = true), TextSpan(3, 6, bold = true, italic = true), TextSpan(6, 9, italic = true)),
            bi,
        )
        assertTrue(SpanOps.isActive(bi, 10, 3, 6, InlineStyle.ITALIC))
        assertFalse(SpanOps.isActive(bi, 10, 0, 6, InlineStyle.ITALIC))
    }

    @Test fun normalizeClampsAndMerges() {
        val out = SpanOps.normalize(listOf(bold(-3, 2), bold(2, 4), TextSpan(8, 30, italic = true), TextSpan(5, 5, bold = true)), 10)
        assertEquals(listOf(bold(0, 4), TextSpan(8, 10, italic = true)), out)
    }

    @Test fun linkRangeAndRemoval() {
        val spans = SpanOps.setLink(listOf(bold(0, 10)), 12, 4, 8, "https://inkwell.app")
        val (range, url) = SpanOps.linkRangeAt(spans, 12, 6)!!
        assertEquals(4 until 8, range)
        assertEquals("https://inkwell.app", url)
        assertEquals(4 until 8, SpanOps.linkRangeAt(spans, 12, 8)!!.first)
        assertNull(SpanOps.linkRangeAt(spans, 12, 2))
        val removed = SpanOps.setLink(spans, 12, 4, 8, null)
        assertEquals(listOf(bold(0, 10)), removed)
    }

    @Test fun sliceAndConcatRoundTrip() {
        val spans = listOf(bold(1, 4), TextSpan(6, 9, italic = true))
        val a = SpanOps.slice(spans, 10, 0, 5)
        val b = SpanOps.slice(spans, 10, 5, 10)
        assertEquals(listOf(bold(1, 4)), a)
        assertEquals(listOf(TextSpan(1, 4, italic = true)), b)
        assertEquals(spans, SpanOps.concat(a, 5, b, 5))
    }

    @Test fun collapsedCaretReportsInheritedStyle() {
        assertTrue(SpanOps.isActive(listOf(bold(0, 4)), 8, 4, 4, InlineStyle.BOLD))
        assertFalse(SpanOps.isActive(listOf(bold(0, 4)), 8, 6, 6, InlineStyle.BOLD))
    }
}
