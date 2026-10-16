package com.inkwell.notes.typed.model

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TypedBlock

/**
 * What a text-field edit did to the document.
 *
 * [focusIndex]/[caret] say where the caret belongs afterwards; [structural] edits (split,
 * Markdown shortcut, leaving a list) become their own undo step and move focus, while plain
 * typing keeps the field's own state (IME composition included).
 */
data class TypingResult(
    val blocks: List<TypedBlock>,
    val focusIndex: Int,
    val caret: Int,
    val structural: Boolean,
)

/** Turns a text block's new field text into document changes. Pure; unit-tested. */
object Typing {

    /**
     * Applies [newText] (caret at [caretAfter]) to block [index]. Inline spans are shifted
     * exactly; Enter (a newline) splits the block; Enter on an empty list item outdents or
     * leaves the list; Enter on an empty last line of a code block leaves the code block;
     * a Markdown prefix typed at the start of a paragraph converts it.
     */
    fun apply(
        blocks: List<TypedBlock>,
        index: Int,
        newText: String,
        caretAfter: Int,
        pendingMarks: Marks? = null,
        newId: () -> String = BlockOps::newId,
    ): TypingResult {
        val block = blocks[index]
        if (newText == block.text) return TypingResult(blocks, index, caretAfter, structural = false)
        val edit = TextDiff.compute(block.text, newText, caretAfter)

        if (edit.inserted.contains('\n') && block.type != BlockType.CODE) {
            return newlines(blocks, index, block, edit, newText, caretAfter, pendingMarks, newId)
        }
        if (block.type == BlockType.CODE && edit.inserted == "\n" && edit.deletedLength == 0 &&
            edit.start == block.text.length && block.text.endsWith("\n")
        ) {
            val trimmed = block.copy(text = block.text.dropLast(1))
            val para = BlockOps.paragraph(newId())
            return TypingResult(BlockOps.insertAfter(blocks.replaceAt(index, trimmed), index, listOf(para)), index + 1, 0, structural = true)
        }

        val supports = BlockOps.supportsSpans(block.type)
        val spans = if (supports) {
            SpanOps.replace(block.spans, block.text.length, edit.start, edit.deletedLength, edit.inserted.length, pendingMarks.takeIf { edit.inserted.isNotEmpty() })
        } else emptyList()

        if (block.type == BlockType.PARAGRAPH && edit.inserted == " " && edit.deletedLength == 0) {
            val m = MarkdownShortcuts.detect(newText, caretAfter)
            if (m != null) {
                val rest = newText.substring(m.prefixLength)
                val restSpans = SpanOps.dropPrefix(spans, newText.length, m.prefixLength)
                if (m.type == BlockType.DIVIDER) {
                    val para = BlockOps.paragraph(newId(), rest).copy(spans = restSpans)
                    val out = BlockOps.insertAfter(blocks.replaceAt(index, TypedBlock(block.id, BlockType.DIVIDER)), index, listOf(para))
                    return TypingResult(out, index + 1, 0, structural = true)
                }
                val converted = TypedBlock(
                    id = block.id,
                    type = m.type,
                    text = rest,
                    spans = if (m.type == BlockType.CODE) emptyList() else restSpans,
                    level = if (m.type == BlockType.HEADING) m.level else 0,
                    checked = m.checked,
                    language = m.language,
                )
                return TypingResult(blocks.replaceAt(index, converted), index, (caretAfter - m.prefixLength).coerceIn(0, rest.length), structural = true)
            }
        }
        return TypingResult(blocks.replaceAt(index, block.copy(text = newText, spans = spans)), index, caretAfter, structural = false)
    }

    private fun newlines(
        blocks: List<TypedBlock>,
        index: Int,
        block: TypedBlock,
        edit: TextEdit,
        newText: String,
        caretAfter: Int,
        pendingMarks: Marks?,
        newId: () -> String,
    ): TypingResult {
        // Enter on an empty list item or quote: outdent, or leave the list.
        if (edit.inserted == "\n" && block.text.isEmpty() && (BlockOps.isList(block.type) || block.type == BlockType.QUOTE)) {
            val replacement = if (BlockOps.isList(block.type) && block.level > 0) block.copy(level = block.level - 1)
            else block.copy(type = BlockType.PARAGRAPH, level = 0, checked = false)
            return TypingResult(blocks.replaceAt(index, replacement), index, 0, structural = true)
        }
        val supports = BlockOps.supportsSpans(block.type)
        var current = block.copy(
            text = newText,
            spans = if (supports) SpanOps.replace(block.spans, block.text.length, edit.start, edit.deletedLength, edit.inserted.length, pendingMarks) else emptyList(),
        )
        var list = blocks.replaceAt(index, current)
        var cursor = index
        var consumed = 0
        val insertEnd = edit.start + edit.inserted.length
        var from = edit.start
        while (true) {
            val nl = newText.indexOf('\n', from)
            if (nl < 0 || nl >= insertEnd) break
            val local = nl - consumed
            val without = current.text.removeRange(local, local + 1)
            val withoutSpans = if (supports) SpanOps.replace(current.spans, current.text.length, local, 1, 0) else emptyList()
            list = list.replaceAt(cursor, current.copy(text = without, spans = withoutSpans))
            val r = BlockOps.split(list, cursor, local, newId())
            list = r.blocks
            cursor = r.focusIndex
            current = list[cursor]
            consumed = nl + 1
            from = nl + 1
        }
        return TypingResult(list, cursor, (caretAfter - consumed).coerceIn(0, current.text.length), structural = true)
    }

    private fun List<TypedBlock>.replaceAt(index: Int, b: TypedBlock): List<TypedBlock> = toMutableList().also { it[index] = b }
}
