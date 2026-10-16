package com.inkwell.notes.typed

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.typed.model.Marks
import com.inkwell.notes.typed.model.SpanOps
import com.inkwell.notes.typed.model.Typing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adversarial cases for the typed editor's text-change pipeline (Enter, paste, shortcuts, spans). */
class TypingEdgeTest {
    private var n = 0
    private val ids: () -> String = { "n${n++}" }

    private fun p(text: String, spans: List<TextSpan> = emptyList()) = TypedBlock("a", BlockType.PARAGRAPH, text, spans)

    @Test fun enterAtTheStartOfACheckedItemKeepsTheTickOnTheText() {
        val done = TypedBlock("c", BlockType.CHECKLIST, "buy milk", checked = true, level = 1)
        val r = Typing.apply(listOf(done), 0, "\nbuy milk", 1, newId = ids)
        assertTrue(r.structural)
        assertEquals(listOf("", "buy milk"), r.blocks.map { it.text })
        // The new, empty item above is unticked; the item that still holds the text stays ticked.
        assertFalse(r.blocks[0].checked)
        assertTrue(r.blocks[1].checked)
        assertEquals(listOf(1, 1), r.blocks.map { it.level })
        assertEquals(1, r.focusIndex)
        assertEquals(0, r.caret)
    }

    @Test fun enterInTheMiddleOfACheckedItemLeavesTheTickOnTheFirstHalf() {
        val done = TypedBlock("c", BlockType.CHECKLIST, "buy milk", checked = true)
        val r = Typing.apply(listOf(done), 0, "buy\n milk", 4, newId = ids)
        assertTrue(r.blocks[0].checked)
        assertFalse(r.blocks[1].checked)
    }

    @Test fun enterReplacingASelectionSplitsAtTheSelectionStart() {
        val r = Typing.apply(listOf(p("hello world", listOf(TextSpan(6, 11, bold = true)))), 0, "hello\nworld", 6, newId = ids)
        assertEquals(listOf("hello", "world"), r.blocks.map { it.text })
        assertEquals(listOf(TextSpan(0, 5, bold = true)), r.blocks[1].spans)
        assertEquals(1, r.focusIndex)
        assertEquals(0, r.caret)
    }

    @Test fun pastingLinesIntoTheMiddleKeepsTheTailAndCaret() {
        val r = Typing.apply(listOf(p("xxyy", listOf(TextSpan(2, 4, italic = true)))), 0, "xxa\nbyy", 5, newId = ids)
        assertEquals(listOf("xxa", "byy"), r.blocks.map { it.text })
        assertEquals(1, r.focusIndex)
        assertEquals(1, r.caret)
        // The italic "yy" moved to the second block, after the pasted "b".
        assertEquals(listOf(TextSpan(1, 3, italic = true)), r.blocks[1].spans)
        assertTrue(r.blocks[0].spans.isEmpty())
    }

    @Test fun pastingTextEndingWithNewlineLeavesAnEmptyBlockFocused() {
        val r = Typing.apply(listOf(p("")), 0, "one\ntwo\n", 8, newId = ids)
        assertEquals(listOf("one", "two", ""), r.blocks.map { it.text })
        assertEquals(2, r.focusIndex)
        assertEquals(0, r.caret)
    }

    @Test fun enterOnAnEmptyHeadingBecomesAParagraphInsteadOfLeavingAnEmptyHeading() {
        val h = TypedBlock("h", BlockType.HEADING, "", level = 2)
        val r = Typing.apply(listOf(h), 0, "\n", 1, newId = ids)
        assertTrue(r.structural)
        assertTrue(r.blocks.none { it.type == BlockType.HEADING && it.text.isEmpty() })
        assertEquals(BlockType.PARAGRAPH, r.blocks[r.focusIndex].type)
        assertEquals(0, r.caret)
    }

    @Test fun shortcutTypedBeforeExistingTextConvertsAndKeepsTheText() {
        val r = Typing.apply(listOf(p("-hello", listOf(TextSpan(1, 6, bold = true)))), 0, "- hello", 2, newId = ids)
        assertEquals(BlockType.BULLET, r.blocks.single().type)
        assertEquals("hello", r.blocks.single().text)
        assertEquals(listOf(TextSpan(0, 5, bold = true)), r.blocks.single().spans)
        assertEquals(0, r.caret)
    }

    @Test fun pendingMarksDoNotLeakIntoAShortcutConversion() {
        val r = Typing.apply(listOf(p("#")), 0, "# ", 2, pendingMarks = Marks(bold = true), newId = ids)
        assertEquals(BlockType.HEADING, r.blocks.single().type)
        assertTrue(r.blocks.single().spans.isEmpty())
    }

    @Test fun autocorrectReplacementKeepsSurroundingFormatting() {
        // "teh " -> "the " with bold on the whole word.
        val r = Typing.apply(listOf(p("teh ", listOf(TextSpan(0, 3, bold = true)))), 0, "the ", 4, newId = ids)
        assertFalse(r.structural)
        assertEquals("the ", r.blocks.single().text)
        assertEquals(listOf(TextSpan(0, 3, bold = true)), r.blocks.single().spans)
    }

    @Test fun deletingAcrossTwoSpansMergesCleanly() {
        val spans = listOf(TextSpan(0, 3, bold = true), TextSpan(3, 6, italic = true))
        val r = Typing.apply(listOf(p("abcdef", spans)), 0, "af", 1, newId = ids)
        assertEquals(listOf(TextSpan(0, 1, bold = true), TextSpan(1, 2, italic = true)), r.blocks.single().spans)
    }

    @Test fun codeBlockNewlineInTheMiddleStaysInsideTheBlock() {
        val code = TypedBlock("c", BlockType.CODE, "ab")
        val r = Typing.apply(listOf(code), 0, "a\nb", 2, newId = ids)
        assertFalse(r.structural)
        assertEquals("a\nb", r.blocks.single().text)
    }

    @Test fun mergeAfterShortcutRoundTripsSpans() {
        val first = TypedBlock("a", BlockType.BULLET, "ab", listOf(TextSpan(0, 2, bold = true)))
        val second = TypedBlock("b", BlockType.CODE, "cd")
        val merged = BlockOps.merge(listOf(first, second), 1)!!
        assertEquals("abcd", merged.blocks.single().text)
        // Code text carries no spans; the bullet's own formatting is kept.
        assertEquals(listOf(TextSpan(0, 2, bold = true)), merged.blocks.single().spans)
        assertEquals(SpanOps.normalize(merged.blocks.single().spans, 4), merged.blocks.single().spans)
    }
}
