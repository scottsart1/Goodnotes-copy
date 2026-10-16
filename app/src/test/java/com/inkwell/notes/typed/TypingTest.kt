package com.inkwell.notes.typed

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.typed.model.Marks
import com.inkwell.notes.typed.model.Typing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTest {
    private var n = 0
    private val ids: () -> String = { "n${n++}" }

    private fun p(text: String, spans: List<TextSpan> = emptyList()) = TypedBlock("a", BlockType.PARAGRAPH, text, spans)

    @Test fun plainTypingShiftsSpans() {
        val r = Typing.apply(listOf(p("a bold", listOf(TextSpan(2, 6, bold = true)))), 0, "XXa bold", 2, newId = ids)
        assertFalse(r.structural)
        assertEquals(listOf(TextSpan(4, 8, bold = true)), r.blocks.single().spans)
    }

    @Test fun pendingMarksStyleTypedText() {
        val r = Typing.apply(listOf(p("ab")), 0, "abc", 3, pendingMarks = Marks(bold = true), newId = ids)
        assertEquals(listOf(TextSpan(2, 3, bold = true)), r.blocks.single().spans)
    }

    @Test fun enterSplitsTheBlock() {
        val r = Typing.apply(listOf(p("hello world", listOf(TextSpan(6, 11, italic = true)))), 0, "hello\n world", 6, newId = ids)
        assertTrue(r.structural)
        assertEquals(listOf("hello", " world"), r.blocks.map { it.text })
        assertEquals(listOf(TextSpan(1, 6, italic = true)), r.blocks[1].spans)
        assertEquals(1, r.focusIndex)
        assertEquals(0, r.caret)
    }

    @Test fun pastingSeveralLinesCreatesSeveralBlocks() {
        val r = Typing.apply(listOf(p("x")), 0, "xa\nb\nc", 6, newId = ids)
        assertEquals(listOf("xa", "b", "c"), r.blocks.map { it.text })
        assertEquals(2, r.focusIndex)
        assertEquals(1, r.caret)
    }

    @Test fun enterOnEmptyListItemOutdentsThenLeavesList() {
        val nested = TypedBlock("l", BlockType.BULLET, "", level = 1)
        val out1 = Typing.apply(listOf(nested), 0, "\n", 1, newId = ids)
        assertEquals(1, out1.blocks.size)
        assertEquals(0, out1.blocks[0].level)
        assertEquals(BlockType.BULLET, out1.blocks[0].type)
        val out2 = Typing.apply(out1.blocks, 0, "\n", 1, newId = ids)
        assertEquals(BlockType.PARAGRAPH, out2.blocks[0].type)
    }

    @Test fun enterInListContinuesTheList() {
        val item = TypedBlock("l", BlockType.NUMBERED, "first", level = 1)
        val r = Typing.apply(listOf(item), 0, "first\n", 6, newId = ids)
        assertEquals(listOf(BlockType.NUMBERED, BlockType.NUMBERED), r.blocks.map { it.type })
        assertEquals(1, r.blocks[1].level)
    }

    @Test fun codeBlocksKeepNewlinesAndExitOnEmptyLastLine() {
        val code = TypedBlock("c", BlockType.CODE, "val x = 1")
        val inner = Typing.apply(listOf(code), 0, "val x = 1\n", 10, newId = ids)
        assertFalse(inner.structural)
        assertEquals("val x = 1\n", inner.blocks.single().text)
        val exit = Typing.apply(inner.blocks, 0, "val x = 1\n\n", 11, newId = ids)
        assertTrue(exit.structural)
        assertEquals(listOf("val x = 1", ""), exit.blocks.map { it.text })
        assertEquals(BlockType.PARAGRAPH, exit.blocks[1].type)
        assertEquals(1, exit.focusIndex)
    }

    @Test fun markdownShortcutsConvertParagraphs() {
        val h = Typing.apply(listOf(p("##")), 0, "## ", 3, newId = ids)
        assertEquals(BlockType.HEADING, h.blocks.single().type)
        assertEquals(2, h.blocks.single().level)
        assertEquals("", h.blocks.single().text)
        val todo = Typing.apply(listOf(p("[]buy milk")), 0, "[] buy milk", 3, newId = ids)
        assertEquals(BlockType.CHECKLIST, todo.blocks.single().type)
        assertEquals("buy milk", todo.blocks.single().text)
        assertEquals(0, todo.caret)
        val code = Typing.apply(listOf(p("```py")), 0, "```py ", 6, newId = ids)
        assertEquals(BlockType.CODE, code.blocks.single().type)
        assertEquals("py", code.blocks.single().language)
        val divider = Typing.apply(listOf(p("---")), 0, "--- ", 4, newId = ids)
        assertEquals(listOf(BlockType.DIVIDER, BlockType.PARAGRAPH), divider.blocks.map { it.type })
        assertEquals(1, divider.focusIndex)
    }

    @Test fun shortcutsDoNotFireInsideOtherBlocksOrMidText() {
        val heading = TypedBlock("h", BlockType.HEADING, "-", level = 1)
        assertEquals(BlockType.HEADING, Typing.apply(listOf(heading), 0, "- ", 2, newId = ids).blocks.single().type)
        assertEquals(BlockType.PARAGRAPH, Typing.apply(listOf(p("a-")), 0, "a- ", 3, newId = ids).blocks.single().type)
    }
}
