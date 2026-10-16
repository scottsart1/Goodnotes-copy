package com.inkwell.notes.export.format

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument

/** Converts a [TypedDocument] into format-neutral [ExportBlock]s. */
object TypedBlocks {

    fun convert(doc: TypedDocument): List<ExportBlock> {
        val out = ArrayList<ExportBlock>(doc.blocks.size)
        var listId = 0
        var inList = false
        // Running numbers per indent depth of the current numbered list.
        val counters = IntArray(MAX_DEPTH + 1)
        for (b in doc.blocks) {
            val isList = b.type == BlockType.BULLET || b.type == BlockType.NUMBERED || b.type == BlockType.CHECKLIST
            if (isList && !inList) {
                listId++
                counters.fill(0)
            }
            inList = isList
            when (b.type) {
                BlockType.PARAGRAPH -> if (b.text.isNotBlank()) out += ExportBlock.Paragraph(runs(b))
                BlockType.HEADING -> if (b.text.isNotBlank()) out += ExportBlock.Heading(b.level.coerceIn(1, 3), runs(b))
                BlockType.BULLET, BlockType.CHECKLIST, BlockType.NUMBERED -> {
                    val depth = b.level.coerceIn(0, MAX_DEPTH)
                    // Deeper numbering restarts whenever a shallower item appears.
                    for (d in depth + 1..MAX_DEPTH) counters[d] = 0
                    val number = if (b.type == BlockType.NUMBERED) {
                        ++counters[depth]
                    } else {
                        counters[depth] = 0
                        1
                    }
                    val kind = when (b.type) {
                        BlockType.NUMBERED -> ListKind.NUMBERED
                        BlockType.CHECKLIST -> ListKind.CHECKLIST
                        else -> ListKind.BULLET
                    }
                    out += ExportBlock.ListItem(kind, depth, runs(b), checked = b.checked, number = number, listId = listId)
                }
                BlockType.QUOTE -> if (b.text.isNotBlank()) out += ExportBlock.Quote(runs(b))
                BlockType.CODE -> out += ExportBlock.Code(b.text, b.language?.takeIf { it.isNotBlank() })
                BlockType.TABLE -> {
                    val rows = b.rows.filter { r -> r.isNotEmpty() }
                    if (rows.isNotEmpty()) out += ExportBlock.Table(rows)
                }
                BlockType.IMAGE -> {
                    val asset = b.assetId
                    if (!asset.isNullOrBlank()) out += ExportBlock.Image(asset, b.text)
                    else if (b.text.isNotBlank()) out += ExportBlock.Paragraph(runsOf(b.text))
                }
                BlockType.DIVIDER -> out += ExportBlock.Divider
            }
        }
        return out
    }

    private fun runs(b: TypedBlock): List<InlineRun> = fromSpans(b.text, b.spans)

    /**
     * Splits [text] at every span boundary and merges the formatting of all spans that cover
     * each piece. Spans are clamped to the text; a later span's link wins over an earlier one.
     */
    fun fromSpans(text: String, spans: List<TextSpan>): List<InlineRun> {
        if (text.isEmpty()) return emptyList()
        val valid = spans.mapNotNull { s ->
            val a = s.start.coerceIn(0, text.length)
            val b = s.end.coerceIn(0, text.length)
            if (b > a) s.copy(start = a, end = b) else null
        }
        if (valid.isEmpty()) return listOf(InlineRun(text))
        val cuts = sortedSetOf(0, text.length)
        valid.forEach { cuts += it.start; cuts += it.end }
        val points = cuts.toList()
        val out = ArrayList<InlineRun>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (b <= a) continue
            val covering = valid.filter { it.start <= a && it.end >= b }
            val run = InlineRun(
                text = text.substring(a, b),
                bold = covering.any { it.bold },
                italic = covering.any { it.italic },
                underline = covering.any { it.underline },
                strike = covering.any { it.strike },
                code = covering.any { it.code },
                link = covering.lastOrNull { !it.link.isNullOrBlank() }?.link,
            )
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == run.copy(text = "")) {
                out[out.size - 1] = last.copy(text = last.text + run.text)
            } else {
                out += run
            }
        }
        return out
    }

    private const val MAX_DEPTH = 8
}
