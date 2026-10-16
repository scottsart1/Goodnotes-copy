package com.inkwell.notes.typed

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.typed.model.MarkdownBlocks
import com.inkwell.notes.typed.model.MarkdownShortcuts
import com.inkwell.notes.typed.model.TextDiff
import com.inkwell.notes.typed.model.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test fun headingShortcuts() {
        assertEquals(BlockType.HEADING to 1, MarkdownShortcuts.detect("# ", 2)!!.let { it.type to it.level })
        assertEquals(2, MarkdownShortcuts.detect("## ", 3)!!.level)
        val h3 = MarkdownShortcuts.detect("### Title", 4)!!
        assertEquals(3, h3.level)
        assertEquals(4, h3.prefixLength)
    }

    @Test fun listShortcuts() {
        assertEquals(BlockType.BULLET, MarkdownShortcuts.detect("- ", 2)!!.type)
        assertEquals(BlockType.BULLET, MarkdownShortcuts.detect("* ", 2)!!.type)
        val num = MarkdownShortcuts.detect("1. ", 3)!!
        assertEquals(BlockType.NUMBERED, num.type)
        assertEquals(3, num.prefixLength)
        assertEquals(BlockType.NUMBERED, MarkdownShortcuts.detect("12) ", 4)!!.type)
        val check = MarkdownShortcuts.detect("[] ", 3)!!
        assertEquals(BlockType.CHECKLIST, check.type)
        assertEquals(false, check.checked)
        assertEquals(true, MarkdownShortcuts.detect("[x] ", 4)!!.checked)
    }

    @Test fun quoteCodeAndDividerShortcuts() {
        assertEquals(BlockType.QUOTE, MarkdownShortcuts.detect("> ", 2)!!.type)
        val code = MarkdownShortcuts.detect("``` ", 4)!!
        assertEquals(BlockType.CODE, code.type)
        assertNull(code.language)
        val kotlin = MarkdownShortcuts.detect("```kotlin ", 10)!!
        assertEquals("kotlin", kotlin.language)
        assertEquals(10, kotlin.prefixLength)
        assertEquals(BlockType.DIVIDER, MarkdownShortcuts.detect("--- ", 4)!!.type)
    }

    @Test fun shortcutsOnlyFireWhenTheSpaceWasJustTyped() {
        assertNull(MarkdownShortcuts.detect("#", 1))
        assertNull(MarkdownShortcuts.detect("# heading", 9))
        assertNull(MarkdownShortcuts.detect("a # ", 4))
        assertNull(MarkdownShortcuts.detect("#hashtag ", 9))
        assertNull(MarkdownShortcuts.detect("1.5 ", 4))
        assertNull(MarkdownShortcuts.detect("", 0))
        // Space typed later in the line after a prefix-like start does not trigger.
        assertNull(MarkdownShortcuts.detect("- item ", 7))
    }

    @Test fun diffFindsInsertDeleteAndReplace() {
        // Without a caret the longest common prefix wins.
        assertEquals(TextEdit(6, 0, "big "), TextDiff.compute("hello world", "hello big world"))
        assertEquals(TextEdit(5, 0, " big"), TextDiff.compute("hello world", "hello big world", caretAfter = 9))
        assertEquals(TextEdit(5, 6, ""), TextDiff.compute("hello world", "hello"))
        // The shared leading 'h' and trailing " world" are kept.
        assertEquals(TextEdit(1, 4, "owdy"), TextDiff.compute("hello world", "howdy world"))
        assertTrue(TextDiff.compute("same", "same").isNoOp)
    }

    @Test fun diffUsesCaretToDisambiguateRepeats() {
        // "aa" -> "aaa" typed at the very start (caret after the new char = 1)
        assertEquals(TextEdit(0, 0, "a"), TextDiff.compute("aa", "aaa", caretAfter = 1))
        // ... or at the end
        assertEquals(TextEdit(2, 0, "a"), TextDiff.compute("aa", "aaa", caretAfter = 3))
        // Backspace in the middle of "abba" -> "aba", caret 1
        assertEquals(TextEdit(1, 1, ""), TextDiff.compute("abba", "aba", caretAfter = 1))
        // Inconsistent caret falls back to prefix/suffix diff
        assertEquals(TextEdit(3, 0, "d"), TextDiff.compute("abc", "abcd", caretAfter = 0))
    }

    @Test fun parsesMarkdownIntoBlocks() {
        val md = """
            # Title
            Intro line one
            continues here.

            - first
              - nested
            1. one
            2. two
            - [ ] todo
            - [x] done
            > quoted
            > more

            ```kotlin
            val x = 1
            ```
            ---
            | A | B |
            | --- | --- |
            | 1 | 2 |
        """.trimIndent()
        val blocks = MarkdownBlocks.parse(md)
        val types = blocks.map { it.type }
        assertEquals(
            listOf(
                BlockType.HEADING, BlockType.PARAGRAPH, BlockType.BULLET, BlockType.BULLET, BlockType.NUMBERED, BlockType.NUMBERED,
                BlockType.CHECKLIST, BlockType.CHECKLIST, BlockType.QUOTE, BlockType.CODE, BlockType.DIVIDER, BlockType.TABLE,
            ),
            types,
        )
        assertEquals("Intro line one continues here.", blocks[1].text)
        assertEquals(1, blocks[3].level)
        assertEquals(true, blocks[7].checked)
        assertEquals("quoted more", blocks[8].text)
        assertEquals("kotlin", blocks[9].language)
        assertEquals("val x = 1", blocks[9].text)
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), blocks[11].rows)
    }

    @Test fun parsesInlineFormatting() {
        val st = MarkdownBlocks.parseInline("A **bold** and *it* with `code`, ~~gone~~ and [link](https://x.y).")
        assertEquals("A bold and it with code, gone and link.", st.text)
        assertTrue(TextSpan(2, 6, bold = true) in st.spans)
        assertTrue(TextSpan(11, 13, italic = true) in st.spans)
        assertTrue(TextSpan(19, 23, code = true) in st.spans)
        assertTrue(TextSpan(25, 29, strike = true) in st.spans)
        assertNotNull(st.spans.firstOrNull { it.link == "https://x.y" && it.start == 34 && it.end == 38 })
    }

    @Test fun unmatchedMarkersStayLiteral() {
        assertEquals("2 * 3 = 6", MarkdownBlocks.parseInline("2 * 3 = 6").text)
        assertEquals("snake_case_name", MarkdownBlocks.parseInline("snake_case_name").text)
        assertTrue(MarkdownBlocks.parseInline("snake_case_name").spans.isEmpty())
    }
}
