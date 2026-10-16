package com.inkwell.notes.typed

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.typed.model.MarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of the Markdown importer used by "Insert" in the assistant panel. */
class MarkdownEdgeTest {

    @Test fun headingKeepsATrailingHashThatIsPartOfTheText() {
        val blocks = MarkdownBlocks.parse("## Learning C#\n\n# Issue #12")
        assertEquals(listOf(BlockType.HEADING, BlockType.HEADING), blocks.map { it.type })
        assertEquals("Learning C#", blocks[0].text)
        assertEquals("Issue #12", blocks[1].text)
    }

    @Test fun headingDropsAClosingSequenceSeparatedBySpace() {
        val blocks = MarkdownBlocks.parse("## Title ##")
        assertEquals("Title", blocks.single().text)
        assertEquals(2, blocks.single().level)
    }

    @Test fun emptyChecklistItemIsStillAChecklist() {
        val blocks = MarkdownBlocks.parse("- [ ]\n- [x]\n- [ ] buy milk")
        assertEquals(listOf(BlockType.CHECKLIST, BlockType.CHECKLIST, BlockType.CHECKLIST), blocks.map { it.type })
        assertEquals(listOf("", "", "buy milk"), blocks.map { it.text })
        assertEquals(listOf(false, true, false), blocks.map { it.checked })
    }

    @Test fun codeFenceWithoutClosingKeepsEverything() {
        val blocks = MarkdownBlocks.parse("```kotlin\nval a = 1\nval b = 2")
        assertEquals(1, blocks.size)
        assertEquals(BlockType.CODE, blocks[0].type)
        assertEquals("kotlin", blocks[0].language)
        assertEquals("val a = 1\nval b = 2", blocks[0].text)
    }

    @Test fun inlineFormattingSurvivesHeadingsAndListsWithCorrectOffsets() {
        val blocks = MarkdownBlocks.parse("# A **bold** title\n- item with `code`")
        val h = blocks[0]
        assertEquals("A bold title", h.text)
        assertEquals(1, h.spans.size)
        assertEquals(2, h.spans[0].start)
        assertEquals(6, h.spans[0].end)
        assertTrue(h.spans[0].bold)
        val li = blocks[1]
        assertEquals("item with code", li.text)
        assertEquals(10, li.spans.single().start)
        assertEquals(14, li.spans.single().end)
        assertTrue(li.spans.single().code)
    }

    @Test fun escapedHashAndPipeAreLiteral() {
        val blocks = MarkdownBlocks.parse("Price \\| cost \\# tag")
        assertEquals("Price | cost # tag", blocks.single().text)
    }
}
