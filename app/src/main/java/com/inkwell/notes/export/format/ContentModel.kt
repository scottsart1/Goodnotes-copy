package com.inkwell.notes.export.format

/**
 * Format-neutral text content of a document, built once and rendered to Markdown, plain
 * text and Word. Headings carry a semantic [ExportBlock.Heading.level]: 1 is a section
 * (a page, a transcript, a top-level typed heading), 2 and 3 are sub-headings.
 */
data class ExportDocument(
    val title: String,
    val sections: List<ExportSection>,
)

data class ExportSection(
    /** Section heading ("Page 3", "Transcript — Lecture"), or null for untitled content. */
    val heading: String?,
    val blocks: List<ExportBlock>,
    val kind: SectionKind = SectionKind.CONTENT,
)

enum class SectionKind { CONTENT, PAGE, TRANSCRIPT }

/** A styled piece of inline text. */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
) {
    val isPlain: Boolean get() = !bold && !italic && !underline && !strike && !code && link == null
}

enum class ListKind { BULLET, NUMBERED, CHECKLIST }

sealed interface ExportBlock {
    data class Heading(val level: Int, val runs: List<InlineRun>) : ExportBlock
    data class Paragraph(val runs: List<InlineRun>) : ExportBlock
    data class ListItem(
        val kind: ListKind,
        val depth: Int,
        val runs: List<InlineRun>,
        val checked: Boolean = false,
        /** 1-based number within its list (NUMBERED). */
        val number: Int = 1,
        /** Items of one uninterrupted list share an id (Word restarts numbering per list). */
        val listId: Int = 0,
    ) : ExportBlock
    data class Quote(val runs: List<InlineRun>) : ExportBlock
    data class Code(val text: String, val language: String? = null) : ExportBlock
    /** First row is the header. */
    data class Table(val rows: List<List<String>>) : ExportBlock
    /** An image asset of the document (file name in its assets folder). */
    data class Image(val assetId: String, val caption: String) : ExportBlock
    data object Divider : ExportBlock
    /** A rendered picture of a page (Word export of pages with ink). */
    data class PageImage(val pageIndex: Int) : ExportBlock
    /** One transcript line. */
    data class Timestamped(val startMs: Long, val text: String, val speaker: String? = null) : ExportBlock
}

fun runsOf(text: String): List<InlineRun> = if (text.isEmpty()) emptyList() else listOf(InlineRun(text))

fun List<InlineRun>.plainText(): String = joinToString("") { it.text }

/** "mm:ss", or "h:mm:ss" from one hour on. */
fun formatTimestamp(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s)
    else "%02d:%02d".format(java.util.Locale.ROOT, m, s)
}
