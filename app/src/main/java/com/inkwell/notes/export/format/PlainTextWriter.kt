package com.inkwell.notes.export.format

/** Renders an [ExportDocument] as plain text: the Markdown structure without its syntax. */
object PlainTextWriter {

    fun write(doc: ExportDocument): String {
        val out = StringBuilder()
        out.append(doc.title.ifBlank { "Untitled" }.replace('\n', ' ')).append("\n")
        for (section in doc.sections) {
            if (section.heading == null && section.blocks.isEmpty()) continue
            if (section.heading != null) out.append("\n").append(section.heading.replace('\n', ' ')).append("\n")
            var previous: ExportBlock? = null
            for (block in section.blocks) {
                val tight = (block is ExportBlock.ListItem && previous is ExportBlock.ListItem) ||
                    (block is ExportBlock.Timestamped && previous is ExportBlock.Timestamped)
                if (!tight) out.append("\n")
                writeBlock(out, block)
                previous = block
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    private fun writeBlock(out: StringBuilder, block: ExportBlock) {
        when (block) {
            is ExportBlock.Heading -> out.append(block.runs.plainText().replace('\n', ' ')).append("\n")
            is ExportBlock.Paragraph -> out.append(block.runs.plainText()).append("\n")
            is ExportBlock.ListItem -> {
                val indent = "  ".repeat(block.depth)
                val marker = when (block.kind) {
                    ListKind.BULLET -> "• "
                    ListKind.NUMBERED -> "${block.number}. "
                    ListKind.CHECKLIST -> if (block.checked) "[x] " else "[ ] "
                }
                val body = block.runs.plainText().replace("\n", "\n" + indent + " ".repeat(marker.length))
                out.append(indent).append(marker).append(body).append("\n")
            }
            is ExportBlock.Quote -> block.runs.plainText().split('\n').forEach { out.append("    ").append(it).append("\n") }
            is ExportBlock.Code -> block.text.trimEnd('\n').split('\n').forEach { out.append("    ").append(it).append("\n") }
            is ExportBlock.Table -> block.rows.forEach { row ->
                out.append(row.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').trim() }).append("\n")
            }
            is ExportBlock.Image -> out.append(if (block.caption.isBlank()) "[Image]" else "[Image: ${block.caption.trim()}]").append("\n")
            ExportBlock.Divider -> out.append("----------\n")
            is ExportBlock.PageImage -> Unit
            is ExportBlock.Timestamped -> {
                out.append('[').append(formatTimestamp(block.startMs)).append("] ")
                if (!block.speaker.isNullOrBlank()) out.append(block.speaker.trim()).append(": ")
                out.append(block.text.trim().replace('\n', ' ')).append("\n")
            }
        }
    }
}
