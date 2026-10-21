package com.inkwell.notes.pdf

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers, for this process, which documents' source PDFs ran out of memory during text
 * extraction, per version of the file (length and modification time). Failures to load the
 * file count against the whole file; failures to read one page (a heavy content stream) count
 * against that page only, so the file's other pages are still read. After [GIVE_UP_AFTER]
 * failures the extractor stops trying (the file, or the page), and the indexer OCRs those pages
 * instead of waiting for a text layer that this device cannot read.
 */
internal class PdfTextOutOfMemory {
    private class Record(val length: Long, val modified: Long, val count: Int)

    private val records = ConcurrentHashMap<String, Record>()

    private fun key(docId: String, pdfPageIndex: Int) = if (pdfPageIndex < 0) docId else "$docId\u0000$pdfPageIndex"

    /**
     * Counts one more failure for this version of the file (loading it, or with [pdfPageIndex]
     * reading that page); returns the new count.
     */
    fun record(docId: String, length: Long, modified: Long, pdfPageIndex: Int = WHOLE_FILE): Int =
        records.compute(key(docId, pdfPageIndex)) { _, r ->
            if (r != null && r.length == length && r.modified == modified) Record(length, modified, r.count + 1)
            else Record(length, modified, 1)
        }!!.count

    /** Failures counted for this version of the file, or of its page (0 once the file changed). */
    fun count(docId: String, length: Long, modified: Long, pdfPageIndex: Int = WHOLE_FILE): Int {
        val r = records[key(docId, pdfPageIndex)] ?: return 0
        return if (r.length == length && r.modified == modified) r.count else 0
    }

    /**
     * True when extraction for this version of the file is not worth trying again: the file as a
     * whole, or (with [pdfPageIndex]) that page.
     */
    fun givenUp(docId: String, length: Long, modified: Long, pdfPageIndex: Int = WHOLE_FILE): Boolean =
        count(docId, length, modified) >= GIVE_UP_AFTER ||
            (pdfPageIndex >= 0 && count(docId, length, modified, pdfPageIndex) >= GIVE_UP_AFTER)

    companion object {
        /** Out-of-memory failures after which a document's (or page's) text layer is no longer read. */
        const val GIVE_UP_AFTER = 2
        /** Page index meaning the whole file (loading it). */
        const val WHOLE_FILE = -1
    }
}
