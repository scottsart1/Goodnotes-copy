package com.inkwell.notes.pdf

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers, for this process, which documents' source PDFs ran out of memory during text
 * extraction, per version of the file (length and modification time). After [GIVE_UP_AFTER]
 * failures the extractor stops loading that file, and the indexer OCRs its pages instead of
 * waiting for a text layer that this device cannot read.
 */
internal class PdfTextOutOfMemory {
    private class Record(val length: Long, val modified: Long, val count: Int)

    private val records = ConcurrentHashMap<String, Record>()

    /** Counts one more failure for this version of the file; returns the new count. */
    fun record(docId: String, length: Long, modified: Long): Int =
        records.compute(docId) { _, r ->
            if (r != null && r.length == length && r.modified == modified) Record(length, modified, r.count + 1)
            else Record(length, modified, 1)
        }!!.count

    /** Failures counted for this version of the file (0 once the file changed). */
    fun count(docId: String, length: Long, modified: Long): Int {
        val r = records[docId] ?: return 0
        return if (r.length == length && r.modified == modified) r.count else 0
    }

    /** True when extraction for this version of the file is not worth trying again. */
    fun givenUp(docId: String, length: Long, modified: Long): Boolean = count(docId, length, modified) >= GIVE_UP_AFTER

    companion object {
        /** Out-of-memory failures after which a document's text layer is no longer read. */
        const val GIVE_UP_AFTER = 2
    }
}
