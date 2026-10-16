package com.inkwell.notes.typed

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.typed.model.History
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockOpsTest {
    private fun p(id: String, text: String, spans: List<TextSpan> = emptyList()) = TypedBlock(id, BlockType.PARAGRAPH, text, spans)

    @Test fun splitMovesTailAndItsSpans() {
        val blocks = listOf(p("a", "hello world", listOf(TextSpan(3, 8, bold = true))))
        val r = BlockOps.split(blocks, 0, 5, newId = "b")
        assertEquals(listOf("hello", " world"), r.blocks.map { it.text })
        assertEquals(listOf(TextSpan(3, 5, bold = true)), r.blocks[0].spans)
        assertEquals(listOf(TextSpan(0, 3, bold = true)), r.blocks[1].spans)
        assertEquals("b", r.blocks[1].id)
        assertEquals(1, r.focusIndex)
        assertEquals(0, r.caret)
    }

    @Test fun splitContinuesListsAndEndsHeadings() {
        val bullet = TypedBlock("a", BlockType.CHECKLIST, "task", level = 2, checked = true)
        val r = BlockOps.split(listOf(bullet), 0, 4, "b")
        assertEquals(BlockType.CHECKLIST, r.blocks[1].type)
        assertEquals(2, r.blocks[1].level)
        assertFalse(r.blocks[1].checked)

        val heading = TypedBlock("h", BlockType.HEADING, "Title", level = 2)
        val atEnd = BlockOps.split(listOf(heading), 0, 5, "x")
        assertEquals(BlockType.PARAGRAPH, atEnd.blocks[1].type)
        assertEquals(0, atEnd.blocks[1].level)
        val inMiddle = BlockOps.split(listOf(heading), 0, 2, "y")
        assertEquals(BlockType.HEADING, inMiddle.blocks[1].type)
        assertEquals(2, inMiddle.blocks[1].level)
    }

    @Test fun mergeJoinsTextAndSpans() {
        val blocks = listOf(p("a", "abc", listOf(TextSpan(0, 3, italic = true))), p("b", "def", listOf(TextSpan(1, 3, bold = true))))
        val r = BlockOps.merge(blocks, 1)!!
        assertEquals(1, r.blocks.size)
        assertEquals("abcdef", r.blocks[0].text)
        assertEquals(listOf(TextSpan(0, 3, italic = true), TextSpan(4, 6, bold = true)), r.blocks[0].spans)
        assertEquals(0, r.focusIndex)
        assertEquals(3, r.caret)
    }

    @Test fun splitThenMergeRestoresOriginal() {
        val original = p("a", "The quick brown fox", listOf(TextSpan(4, 15, bold = true), TextSpan(16, 19, italic = true)))
        val split = BlockOps.split(listOf(original), 0, 10, "b")
        val merged = BlockOps.merge(split.blocks, 1)!!
        assertEquals(original, merged.blocks.single())
    }

    @Test fun backspaceAtStartTurnsStyledBlocksIntoParagraphsFirst() {
        val blocks = listOf(p("a", "x"), TypedBlock("b", BlockType.BULLET, "item", level = 1))
        val outdented = BlockOps.backspaceAtStart(blocks, 1)!!
        assertEquals(0, outdented.blocks[1].level)
        assertEquals(BlockType.BULLET, outdented.blocks[1].type)
        val para = BlockOps.backspaceAtStart(outdented.blocks, 1)!!
        assertEquals(BlockType.PARAGRAPH, para.blocks[1].type)
        val merged = BlockOps.backspaceAtStart(para.blocks, 1)!!
        assertEquals(listOf("xitem"), merged.blocks.map { it.text })
        assertEquals(1, merged.caret)
    }

    @Test fun backspaceRemovesDividerAndEmptyBlockAfterImage() {
        val blocks = listOf(p("a", "x"), TypedBlock("d", BlockType.DIVIDER), p("b", "y"))
        val r = BlockOps.backspaceAtStart(blocks, 2)!!
        assertEquals(listOf("a", "b"), r.blocks.map { it.id })
        assertEquals(1, r.focusIndex)

        val img = listOf(TypedBlock("i", BlockType.IMAGE, assetId = "x.png"), p("e", ""))
        assertEquals(listOf("i"), BlockOps.backspaceAtStart(img, 1)!!.blocks.map { it.id })
        assertNull(BlockOps.backspaceAtStart(listOf(img[0], p("f", "text")), 1))
        assertNull(BlockOps.backspaceAtStart(listOf(p("only", "t")), 0))
    }

    @Test fun indentOnlyAffectsListsAndIsBounded() {
        val blocks = listOf(TypedBlock("a", BlockType.BULLET, "x"), p("b", "y"))
        assertEquals(1, BlockOps.indent(blocks, 0, 1)[0].level)
        assertEquals(0, BlockOps.indent(blocks, 0, -1)[0].level)
        assertEquals(blocks, BlockOps.indent(blocks, 1, 1))
        var b = blocks
        repeat(20) { b = BlockOps.indent(b, 0, 1) }
        assertEquals(BlockOps.MAX_INDENT, b[0].level)
    }

    @Test fun numberingRestartsAfterOtherBlocks() {
        val blocks = listOf(
            TypedBlock("1", BlockType.NUMBERED, "a"),
            TypedBlock("2", BlockType.NUMBERED, "b"),
            TypedBlock("3", BlockType.NUMBERED, "b1", level = 1),
            TypedBlock("4", BlockType.NUMBERED, "b2", level = 1),
            TypedBlock("5", BlockType.NUMBERED, "c"),
            p("6", "break"),
            TypedBlock("7", BlockType.NUMBERED, "again"),
        )
        assertEquals(listOf(1, 2, 1, 2, 3, 0, 1), BlockOps.numbering(blocks).toList())
        assertEquals("3.", BlockOps.numberLabel(3, 0))
        assertEquals("b.", BlockOps.numberLabel(2, 1))
        assertEquals("iv.", BlockOps.numberLabel(4, 2))
        assertEquals("aa.", BlockOps.numberLabel(27, 1))
    }

    @Test fun moveDuplicateDeleteAndTurnInto() {
        val blocks = listOf(p("a", "1"), p("b", "2"), p("c", "3"))
        assertEquals(listOf("b", "c", "a"), BlockOps.move(blocks, 0, 2).map { it.id })
        assertEquals(listOf("a", "x", "b", "c"), BlockOps.duplicate(blocks, 0, "x").map { it.id })
        assertEquals("1", BlockOps.duplicate(blocks, 0, "x")[1].text)
        assertEquals(1, BlockOps.delete(listOf(p("a", "z")), 0).size)
        assertTrue(BlockOps.delete(listOf(p("a", "z")), 0).single().text.isEmpty())
        val h = BlockOps.turnInto(p("a", "Title", listOf(TextSpan(0, 2, bold = true))), BlockType.HEADING, 2)
        assertEquals(2, h.level)
        assertEquals(1, h.spans.size)
        val code = BlockOps.turnInto(h, BlockType.CODE)
        assertTrue(code.spans.isEmpty())
        assertEquals("Title", code.text)
    }

    @Test fun outlineWordCountAndPlainText() {
        val blocks = listOf(
            TypedBlock("h1", BlockType.HEADING, "Intro", level = 1),
            p("p", "Two words."),
            TypedBlock("h2", BlockType.HEADING, "  ", level = 2),
            TypedBlock("t", BlockType.TABLE, rows = listOf(listOf("Name", "Age"), listOf("Ann", "42"))),
            TypedBlock("b", BlockType.BULLET, "item one"),
            TypedBlock("n", BlockType.NUMBERED, "first"),
        )
        assertEquals(listOf("h1"), BlockOps.outline(blocks).map { it.blockId })
        assertEquals(1 + 2 + 4 + 2 + 1, BlockOps.wordCount(blocks))
        val text = BlockOps.plainText(blocks)
        assertTrue(text.startsWith("# Intro"))
        assertTrue(text.contains("- item one\n1. first"))
        assertTrue(text.contains("| Name | Age |"))
    }

    @Test fun historyCoalescesTypingAndUndoesRedoes() {
        val h = History<String>(coalesceMs = 1000)
        h.record("a", "type:1", now = 0)
        h.record("ab", "type:1", now = 500) // coalesced with previous step
        h.record("abc", "struct", now = 600)
        assertEquals("abc", h.undo("abcd"))
        assertEquals("a", h.undo("abc"))
        assertNull(h.undo("a"))
        assertEquals("abc", h.redo("a"))
        assertTrue(h.canRedo)
        h.record("x", null, now = 5000)
        assertFalse(h.canRedo)
    }
}
