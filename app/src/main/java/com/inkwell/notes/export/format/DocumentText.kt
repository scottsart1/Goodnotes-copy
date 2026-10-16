package com.inkwell.notes.export.format

import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TypedDocument

/** One row of the text index, as the exporters need it. */
data class TextRow(
    val pageId: String?,
    val source: TextSource,
    val text: String,
    /** left, top, right, bottom in page points; null when unknown. */
    val bounds: FloatArray?,
    val confidence: Float? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is TextRow && other.pageId == pageId && other.source == source && other.text == text &&
            other.bounds.contentEqualsNullable(bounds) && other.confidence == confidence

    override fun hashCode(): Int = (pageId?.hashCode() ?: 0) * 31 + text.hashCode()

    companion object {
        /** Parses the index's "left,top,right,bottom" column; null when absent or malformed. */
        fun parseBounds(s: String?): FloatArray? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(',')
            if (parts.size != 4) return null
            val v = FloatArray(4)
            for (i in 0 until 4) {
                val f = parts[i].trim().toFloatOrNull() ?: return null
                if (f.isNaN() || f.isInfinite()) return null
                v[i] = f
            }
            if (v[2] <= v[0] || v[3] <= v[1]) return null
            return v
        }

        private fun FloatArray?.contentEqualsNullable(other: FloatArray?): Boolean =
            if (this == null) other == null else other != null && this.contentEquals(other)
    }
}

/** What one page contributes to the text exports. */
data class PageTextInput(
    val pageIndex: Int,
    val texts: List<TextItem> = emptyList(),
    val stickies: List<StickyItem> = emptyList(),
    val connectorLabels: List<String> = emptyList(),
    val rows: List<TextRow> = emptyList(),
    /** Embedded PDF text used when the index has no PDF/OCR text for the page yet. */
    val pdfText: String? = null,
    /** True when the page has handwriting, shapes or images (Word adds a picture of it). */
    val hasInk: Boolean = false,
)

data class TranscriptInput(val recordingTitle: String, val segments: List<TranscriptSegment>)

/** Orders a page's text into reading order. */
object PageTextAssembler {
    private class Piece(val top: Float, val left: Float, val text: String)

    fun blocks(input: PageTextInput): List<ExportBlock> {
        val positioned = ArrayList<Piece>()
        val leading = ArrayList<String>()
        val trailing = ArrayList<String>()

        val hasPdfRows = input.rows.any { it.source == TextSource.PDF_TEXT && it.text.isNotBlank() }
        val baseSources = if (hasPdfRows) setOf(TextSource.PDF_TEXT) else setOf(TextSource.OCR)
        val baseRows = input.rows.filter { it.source in baseSources && it.text.isNotBlank() }
        if (baseRows.isEmpty() && !input.pdfText.isNullOrBlank()) leading += input.pdfText.trim()
        for (row in baseRows) {
            val b = row.bounds
            if (b != null) positioned += Piece(b[1], b[0], row.text.trim()) else leading += row.text.trim()
        }
        for (row in input.rows) {
            if (row.source != TextSource.HANDWRITING || row.text.isBlank()) continue
            val b = row.bounds
            if (b != null) positioned += Piece(b[1], b[0], row.text.trim()) else trailing += row.text.trim()
        }
        for (t in input.texts) if (t.text.isNotBlank()) positioned += Piece(t.y, t.x, t.text.trim())
        for (s in input.stickies) if (s.text.isNotBlank()) positioned += Piece(s.y, s.x, s.text.trim())

        // Items whose tops are within one line band read left to right.
        val sorted = positioned.sortedWith(compareBy<Piece>({ (it.top / LINE_BAND).toInt() }, { it.left }, { it.top }))
        val out = ArrayList<ExportBlock>()
        leading.forEach { out += ExportBlock.Paragraph(runsOf(it)) }
        sorted.forEach { out += ExportBlock.Paragraph(runsOf(it.text)) }
        trailing.forEach { out += ExportBlock.Paragraph(runsOf(it)) }
        input.connectorLabels.filter { it.isNotBlank() }.forEach { out += ExportBlock.Paragraph(runsOf(it.trim())) }
        return out
    }

    private const val LINE_BAND = 14f
}

/** Builds the [ExportDocument] behind Markdown, plain text and Word exports. */
object DocumentTextBuilder {
    fun build(
        title: String,
        typed: TypedDocument?,
        pages: List<PageTextInput>,
        transcripts: List<TranscriptInput>,
        whiteboard: Boolean = false,
        includePageImages: Boolean = false,
        pageLabel: (Int) -> String = { "Page ${it + 1}" },
        transcriptLabel: (String) -> String = { "Transcript — $it" },
    ): ExportDocument {
        val sections = ArrayList<ExportSection>()
        if (typed != null) {
            val blocks = TypedBlocks.convert(typed)
            if (blocks.isNotEmpty()) sections += ExportSection(null, blocks, SectionKind.CONTENT)
        }
        for (p in pages) {
            val blocks = PageTextAssembler.blocks(p).toMutableList()
            if (includePageImages && p.hasInk) blocks += ExportBlock.PageImage(p.pageIndex)
            if (blocks.isEmpty()) continue
            sections += if (whiteboard) ExportSection(null, blocks, SectionKind.CONTENT)
            else ExportSection(pageLabel(p.pageIndex), blocks, SectionKind.PAGE)
        }
        for (t in transcripts) {
            val lines = t.segments.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
                .map { ExportBlock.Timestamped(it.startMs, it.text, it.speaker) }
            if (lines.isNotEmpty()) sections += ExportSection(transcriptLabel(t.recordingTitle.ifBlank { "Recording" }), lines, SectionKind.TRANSCRIPT)
        }
        return ExportDocument(title, sections)
    }
}
