package com.inkwell.notes.ocr.text

import com.inkwell.notes.core.model.TextSource

/** Minimal view of an index row for ordering. */
data class OrderRow(
    val rowId: Long,
    val pageId: String?,
    val recordingId: String?,
    val source: TextSource,
    val bounds: FloatArray?,
    val timestampMs: Long?,
)

/**
 * Reading order of a document's indexed text: document metadata (title, tags) first, then pages
 * by index, and within a page text without a position (PDF text layer, typed documents) in
 * insertion order followed by placed text top to bottom, left to right; transcripts come last,
 * by recording start then time.
 */
object ReadingOrder {
    /** Page id of the single pseudo-page that holds a typed document's text. */
    const val TYPED_PAGE = "typed"
    /** Boxes whose tops differ by less than this (points) are read as one line, left to right. */
    const val LINE_TOLERANCE = 8f

    private val META = setOf(TextSource.TITLE, TextSource.TAG, TextSource.FOLDER)

    /**
     * Sorts [rows]. Rows on pages missing from [pageIndex] (deleted pages) are dropped;
     * [TYPED_PAGE] rows are kept before real pages.
     */
    fun <T> sort(rows: List<T>, view: (T) -> OrderRow, pageIndex: Map<String, Int>, recordingStart: Map<String, Long>): List<T> {
        val meta = ArrayList<T>()
        val byPage = HashMap<Int, MutableList<T>>()
        val transcripts = ArrayList<T>()
        for (r in rows) {
            val v = view(r)
            when {
                v.source in META && v.pageId == null && v.recordingId == null -> meta += r
                v.recordingId != null || v.source == TextSource.TRANSCRIPT -> transcripts += r
                v.pageId == TYPED_PAGE -> byPage.getOrPut(-1) { ArrayList() } += r
                v.pageId != null -> {
                    val idx = pageIndex[v.pageId] ?: continue
                    byPage.getOrPut(idx) { ArrayList() } += r
                }
                else -> meta += r
            }
        }
        val out = ArrayList<T>(rows.size)
        out += meta.sortedWith(compareBy<T>({ metaRank(view(it).source) }, { view(it).rowId }))
        for (idx in byPage.keys.sorted()) out += sortPage(byPage.getValue(idx), view)
        out += transcripts.sortedWith(
            compareBy<T>(
                { recordingStart[view(it).recordingId] ?: Long.MAX_VALUE },
                { view(it).recordingId ?: "" },
                { view(it).timestampMs ?: Long.MAX_VALUE },
                { view(it).rowId },
            ),
        )
        return out
    }

    private fun metaRank(s: TextSource) = when (s) {
        TextSource.TITLE -> 0
        TextSource.TAG -> 1
        else -> 2
    }

    /** Unplaced rows (insertion order), then placed rows by line (top) and left edge. */
    fun <T> sortPage(rows: List<T>, view: (T) -> OrderRow): List<T> {
        val unplaced = rows.filter { view(it).bounds == null }.sortedBy { view(it).rowId }
        val placed = rows.filter { view(it).bounds != null }.sortedWith(compareBy({ view(it).bounds!![1] }, { view(it).bounds!![0] }))
        val out = ArrayList<T>(rows.size)
        out += unplaced
        var i = 0
        while (i < placed.size) {
            val top = view(placed[i]).bounds!![1]
            var j = i + 1
            while (j < placed.size && view(placed[j]).bounds!![1] - top < LINE_TOLERANCE) j++
            out += placed.subList(i, j).sortedWith(compareBy({ view(it).bounds!![0] }, { view(it).rowId }))
            i = j
        }
        return out
    }
}
