package com.inkwell.notes.typed.model

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TypedBlock
import java.util.UUID

/** Result of a structural edit: the new blocks plus where the caret should go. */
data class BlockEditResult(val blocks: List<TypedBlock>, val focusIndex: Int, val caret: Int)

/** One heading in the outline panel. */
data class OutlineItem(val blockId: String, val index: Int, val level: Int, val text: String)

/** Pure operations on the block list of a typed document. */
object BlockOps {
    const val MAX_INDENT = 6

    fun newId(): String = UUID.randomUUID().toString()

    fun isTextual(type: BlockType): Boolean = type != BlockType.TABLE && type != BlockType.IMAGE && type != BlockType.DIVIDER

    fun isList(type: BlockType): Boolean = type == BlockType.BULLET || type == BlockType.NUMBERED || type == BlockType.CHECKLIST

    /** Supports inline formatting spans (code blocks and non-text blocks are plain). */
    fun supportsSpans(type: BlockType): Boolean = isTextual(type) && type != BlockType.CODE

    fun paragraph(id: String = newId(), text: String = ""): TypedBlock = TypedBlock(id, BlockType.PARAGRAPH, text)

    /**
     * Splits the block at [index] at text offset [at] (Enter). The head keeps its type; the
     * tail continues lists, and becomes a paragraph after a heading or quote when it is empty.
     * The caret goes to the start of the tail.
     */
    fun split(blocks: List<TypedBlock>, index: Int, at: Int, newId: String = newId()): BlockEditResult {
        val b = blocks.getOrNull(index) ?: return BlockEditResult(blocks, index, at)
        val pos = at.coerceIn(0, b.text.length)
        val headText = b.text.substring(0, pos)
        val tailText = b.text.substring(pos)
        val headSpans = SpanOps.slice(b.spans, b.text.length, 0, pos)
        val tailSpans = SpanOps.slice(b.spans, b.text.length, pos, b.text.length)
        val tailType = when (b.type) {
            BlockType.HEADING, BlockType.QUOTE -> if (tailText.isEmpty()) BlockType.PARAGRAPH else b.type
            BlockType.BULLET, BlockType.NUMBERED, BlockType.CHECKLIST, BlockType.CODE -> b.type
            else -> BlockType.PARAGRAPH
        }
        val head = b.copy(text = headText, spans = headSpans)
        val tail = TypedBlock(
            id = newId,
            type = tailType,
            text = tailText,
            spans = tailSpans,
            level = if (tailType == b.type) b.level else 0,
            checked = false,
            language = if (tailType == BlockType.CODE) b.language else null,
        )
        val out = blocks.toMutableList()
        out[index] = head
        out.add(index + 1, tail)
        return BlockEditResult(out, index + 1, 0)
    }

    /**
     * Backspace at the start of block [index]. A styled text block first turns into a
     * paragraph; a paragraph merges into the previous text block; a preceding divider is
     * removed; an empty block after an image or table is deleted. Null when nothing applies.
     */
    fun backspaceAtStart(blocks: List<TypedBlock>, index: Int): BlockEditResult? {
        val cur = blocks.getOrNull(index) ?: return null
        if (!isTextual(cur.type)) return null
        if (cur.type != BlockType.PARAGRAPH) {
            if (isList(cur.type) && cur.level > 0) {
                return BlockEditResult(blocks.replaceAt(index, cur.copy(level = cur.level - 1)), index, 0)
            }
            val asParagraph = cur.copy(type = BlockType.PARAGRAPH, level = 0, checked = false, language = null,
                spans = if (cur.type == BlockType.CODE) emptyList() else cur.spans)
            return BlockEditResult(blocks.replaceAt(index, asParagraph), index, 0)
        }
        if (index == 0) return null
        val prev = blocks[index - 1]
        return when {
            prev.type == BlockType.DIVIDER -> BlockEditResult(blocks.removeAt(index - 1), index - 1, 0)
            !isTextual(prev.type) -> if (cur.text.isEmpty()) BlockEditResult(blocks.removeAt(index), index - 1, -1) else null
            else -> merge(blocks, index)
        }
    }

    /** Appends block [index] to the previous text block and removes it. */
    fun merge(blocks: List<TypedBlock>, index: Int): BlockEditResult? {
        if (index <= 0 || index >= blocks.size) return null
        val prev = blocks[index - 1]
        val cur = blocks[index]
        if (!isTextual(prev.type) || !isTextual(cur.type)) return null
        val spans = if (supportsSpans(prev.type)) {
            SpanOps.concat(prev.spans, prev.text.length, if (supportsSpans(cur.type)) cur.spans else emptyList(), cur.text.length)
        } else emptyList()
        val merged = prev.copy(text = prev.text + cur.text, spans = spans)
        val out = blocks.toMutableList()
        out[index - 1] = merged
        out.removeAt(index)
        return BlockEditResult(out, index - 1, prev.text.length)
    }

    /** Changes the indent of a list block by [delta]; other blocks are unchanged. */
    fun indent(blocks: List<TypedBlock>, index: Int, delta: Int): List<TypedBlock> {
        val b = blocks.getOrNull(index) ?: return blocks
        if (!isList(b.type)) return blocks
        val level = (b.level + delta).coerceIn(0, MAX_INDENT)
        if (level == b.level) return blocks
        return blocks.replaceAt(index, b.copy(level = level))
    }

    /** Converts a block to another type, keeping its text where that makes sense. */
    fun turnInto(block: TypedBlock, type: BlockType, level: Int = 0, language: String? = null): TypedBlock {
        if (block.type == type && (type != BlockType.HEADING || block.level == level)) return block
        val text = when {
            isTextual(block.type) -> block.text
            block.type == BlockType.TABLE -> TableOps.toPlainText(block.rows)
            block.type == BlockType.IMAGE -> block.text
            else -> ""
        }
        return when (type) {
            BlockType.HEADING -> TypedBlock(block.id, type, text, if (isTextual(block.type) && block.type != BlockType.CODE) block.spans else emptyList(), level = level.coerceIn(1, 3))
            BlockType.BULLET, BlockType.NUMBERED, BlockType.CHECKLIST ->
                TypedBlock(block.id, type, text, if (supportsSpans(block.type)) block.spans else emptyList(), level = if (isList(block.type)) block.level else 0)
            BlockType.PARAGRAPH, BlockType.QUOTE -> TypedBlock(block.id, type, text, if (supportsSpans(block.type)) block.spans else emptyList())
            BlockType.CODE -> TypedBlock(block.id, type, text, language = language ?: block.language)
            BlockType.TABLE -> TypedBlock(block.id, type, rows = if (text.isBlank()) TableOps.newTable() else TableOps.normalize(listOf(listOf(text), listOf(""))))
            BlockType.DIVIDER -> TypedBlock(block.id, type)
            BlockType.IMAGE -> block.copy(type = type)
        }
    }

    fun duplicate(blocks: List<TypedBlock>, index: Int, newId: String = newId()): List<TypedBlock> {
        val b = blocks.getOrNull(index) ?: return blocks
        return blocks.toMutableList().apply { add(index + 1, b.copy(id = newId)) }
    }

    /** Deletes block [index]; the document always keeps at least one empty paragraph. */
    fun delete(blocks: List<TypedBlock>, index: Int): List<TypedBlock> {
        if (index !in blocks.indices) return blocks
        val out = blocks.removeAt(index)
        return out.ifEmpty { listOf(paragraph()) }
    }

    fun move(blocks: List<TypedBlock>, from: Int, to: Int): List<TypedBlock> {
        if (from !in blocks.indices || to !in blocks.indices || from == to) return blocks
        return blocks.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun insertAfter(blocks: List<TypedBlock>, index: Int, inserted: List<TypedBlock>): List<TypedBlock> {
        val at = (index + 1).coerceIn(0, blocks.size)
        return blocks.toMutableList().apply { addAll(at, inserted) }
    }

    /**
     * List numbers for NUMBERED blocks (0 for every other block). Numbering continues across
     * nested items, restarts after any non-list block or a bullet/checklist at the same level.
     */
    fun numbering(blocks: List<TypedBlock>): IntArray {
        val out = IntArray(blocks.size)
        val counters = IntArray(MAX_INDENT + 1)
        for ((i, b) in blocks.withIndex()) {
            if (!isList(b.type)) { counters.fill(0); continue }
            val level = b.level.coerceIn(0, MAX_INDENT)
            for (l in level + 1..MAX_INDENT) counters[l] = 0
            if (b.type == BlockType.NUMBERED) {
                counters[level]++
                out[i] = counters[level]
            } else {
                counters[level] = 0
            }
        }
        return out
    }

    /** "1.", "a.", "i." depending on nesting depth. */
    fun numberLabel(n: Int, level: Int): String = when (level % 3) {
        0 -> "$n."
        1 -> alpha(n) + "."
        else -> roman(n) + "."
    }

    fun bulletGlyph(level: Int): String = when (level % 3) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }

    private fun alpha(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            x--
            sb.insert(0, ('a' + x % 26))
            x /= 26
        }
        return sb.toString()
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var x = n
        val sb = StringBuilder()
        for (k in values.indices) while (x >= values[k]) { sb.append(symbols[k]); x -= values[k] }
        return sb.toString()
    }

    fun outline(blocks: List<TypedBlock>): List<OutlineItem> =
        blocks.withIndex()
            .filter { it.value.type == BlockType.HEADING && it.value.text.isNotBlank() }
            .map { OutlineItem(it.value.id, it.index, it.value.level.coerceIn(1, 3), it.value.text.trim()) }

    private val wordSplit = Regex("\\s+")

    /** Words in the document's readable text (all text blocks, table cells, image captions). */
    fun wordCount(blocks: List<TypedBlock>): Int = blocks.sumOf { b ->
        val texts = if (b.type == BlockType.TABLE) b.rows.flatten() else listOf(b.text)
        texts.sumOf { t -> if (t.isBlank()) 0 else t.trim().split(wordSplit).count { w -> w.any { it.isLetterOrDigit() } } }
    }

    fun characterCount(blocks: List<TypedBlock>): Int = blocks.sumOf { b ->
        if (b.type == BlockType.TABLE) b.rows.sumOf { r -> r.sumOf { it.length } } else b.text.length
    }

    /** Searchable text of one block ("" when nothing to index). */
    fun blockText(b: TypedBlock): String = when (b.type) {
        BlockType.TABLE -> TableOps.toPlainText(b.rows)
        BlockType.DIVIDER -> ""
        else -> b.text.trim()
    }

    /**
     * Readable plain text of the document (for AI context and copying): one block per
     * paragraph, with list markers and table rows.
     */
    fun plainText(blocks: List<TypedBlock>): String {
        val numbers = numbering(blocks)
        val sb = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
            val indent = "  ".repeat(b.level.coerceIn(0, MAX_INDENT))
            val line = when (b.type) {
                BlockType.PARAGRAPH -> b.text
                BlockType.HEADING -> "#".repeat(b.level.coerceIn(1, 3)) + " " + b.text
                BlockType.BULLET -> "$indent- ${b.text}"
                BlockType.NUMBERED -> "$indent${numbers[i]}. ${b.text}"
                BlockType.CHECKLIST -> "$indent- [${if (b.checked) "x" else " "}] ${b.text}"
                BlockType.QUOTE -> "> ${b.text}"
                BlockType.CODE -> "```${b.language.orEmpty()}\n${b.text}\n```"
                BlockType.TABLE -> TableOps.toMarkdown(b.rows)
                BlockType.IMAGE -> if (b.text.isBlank()) "[Image]" else "[Image: ${b.text}]"
                BlockType.DIVIDER -> "---"
            }
            if (b.type != BlockType.PARAGRAPH || line.isNotBlank()) sb.append(line).append('\n')
            val next = blocks.getOrNull(i + 1)
            val tight = next != null && isList(b.type) && isList(next.type)
            if (!tight) sb.append('\n')
        }
        return sb.toString().trim()
    }

    private fun List<TypedBlock>.replaceAt(index: Int, b: TypedBlock): List<TypedBlock> = toMutableList().also { it[index] = b }
    private fun List<TypedBlock>.removeAt(index: Int): List<TypedBlock> = toMutableList().also { it.removeAt(index) }
}
