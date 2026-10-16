package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.MarkdownBlocks
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {
    private var n = 0
    private fun parse(md: String) = MarkdownBlocks.parse(md) { "b${n++}" }

    @Test
    fun structure() {
        val blocks = parse(
            """
            # Summary
            The lecture covered **bond pricing** and rates.
            It continued here.

            ## Main points
            - Prices fall when rates rise [0:09]
              - Duration matters
            1. First
            2) Second
            - [ ] Read chapter 4
            - [x] Submit homework
            > A quote
            ---
            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        val types = blocks.map { it.type }
        assertEquals(
            listOf(
                BlockType.HEADING, BlockType.PARAGRAPH, BlockType.HEADING, BlockType.BULLET, BlockType.BULLET,
                BlockType.NUMBERED, BlockType.NUMBERED, BlockType.CHECKLIST, BlockType.CHECKLIST, BlockType.QUOTE,
                BlockType.DIVIDER, BlockType.CODE,
            ),
            types,
        )
        assertEquals(1, blocks[0].level)
        assertEquals("Summary", blocks[0].text)
        assertEquals("The lecture covered bond pricing and rates. It continued here.", blocks[1].text)
        assertEquals(2, blocks[2].level)
        assertEquals(0, blocks[3].level)
        assertEquals(1, blocks[4].level)
        assertEquals("Second", blocks[6].text)
        assertEquals(false, blocks[7].checked)
        assertEquals(true, blocks[8].checked)
        assertEquals("Read chapter 4", blocks[7].text)
        assertEquals("A quote", blocks[9].text)
        assertEquals("val x = 1", blocks[11].text)
        assertEquals("kotlin", blocks[11].language)
        assertEquals(blocks.size, blocks.map { it.id }.toSet().size)
    }

    @Test
    fun inlineSpans() {
        val (text, spans) = MarkdownBlocks.inline("Use **bold**, *italic* and `code`.")
        assertEquals("Use bold, italic and code.", text)
        assertEquals(TextSpan(4, 8, bold = true), spans[0])
        assertEquals(TextSpan(10, 16, italic = true), spans[1])
        assertEquals(TextSpan(21, 25, code = true), spans[2])
    }

    @Test
    fun snakeCaseAndLoneMarkersStayLiteral() {
        val (text, spans) = MarkdownBlocks.inline("a snake_case_name and 2 * 3 * 4")
        assertEquals("a snake_case_name and 2 * 3 * 4", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun nestedBoldItalic() {
        val (text, spans) = MarkdownBlocks.inline("**very *important***")
        assertEquals("very important", text)
        assertTrue(spans.any { it.bold && it.start == 0 && it.end == 14 })
        assertTrue(spans.any { it.italic && it.start == 5 && it.end == 14 })
    }

    @Test
    fun unterminatedCodeFenceIsKept() {
        val blocks = parse("Intro\n```\nline 1\nline 2")
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.CODE), blocks.map { it.type })
        assertEquals("line 1\nline 2", blocks[1].text)
    }

    @Test
    fun emptyInput() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("\n\n   \n").isEmpty())
    }
}
