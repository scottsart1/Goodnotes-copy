package com.inkwell.notes.ocr.text

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TypedDocument

/** Searchable passages of a typed document, in order. */
object TypedText {
    /** Blocks are merged into passages up to this size so search rows stay meaningful. */
    private const val PASSAGE_TARGET = 600

    /** Text of every block that carries text; tables as " | "-joined rows, images by caption. */
    fun blockTexts(doc: TypedDocument): List<String> = doc.blocks.mapNotNull { b ->
        val text = when (b.type) {
            BlockType.DIVIDER -> ""
            BlockType.TABLE -> b.rows.joinToString("\n") { row -> row.joinToString(" | ") { it.trim() } }
            else -> b.text
        }.trim()
        text.takeIf { it.isNotEmpty() }
    }

    /**
     * Passages for the index: a heading starts a new passage, consecutive blocks are joined up
     * to ~[PASSAGE_TARGET] characters, oversized blocks are split.
     */
    fun passages(doc: TypedDocument): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { out += current.toString(); current.setLength(0) }
        }
        for (b in doc.blocks) {
            val text = when (b.type) {
                BlockType.DIVIDER -> { flush(); continue }
                BlockType.TABLE -> b.rows.joinToString("\n") { row -> row.joinToString(" | ") { it.trim() } }
                else -> b.text
            }.trim()
            if (text.isEmpty()) continue
            if (b.type == BlockType.HEADING) flush()
            if (text.length > PASSAGE_TARGET * 2) {
                flush()
                out += TextSplitter.split(text, PASSAGE_TARGET, PASSAGE_TARGET + 300)
                continue
            }
            if (current.isNotEmpty() && current.length + 1 + text.length > PASSAGE_TARGET) flush()
            if (current.isNotEmpty()) current.append('\n')
            current.append(text)
        }
        flush()
        return out
    }
}
