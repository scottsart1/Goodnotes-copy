package com.inkwell.notes.ocr.index

import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.storage.db.AppDatabase
import com.inkwell.notes.core.storage.db.TextIndexEntity
import com.inkwell.notes.ocr.IndexedPassage
import com.inkwell.notes.ocr.text.Bounds
import com.inkwell.notes.ocr.text.OrderRow
import com.inkwell.notes.ocr.text.ReadingOrder
import com.inkwell.notes.ocr.text.SourcedPassage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A document's indexed text in reading order (see [ReadingOrder]). */
internal class DocumentTextLoader(private val db: AppDatabase) {

    suspend fun load(docId: String): List<SourcedPassage> = withContext(Dispatchers.IO) {
        val rows = db.textIndex().forDocument(docId)
        if (rows.isEmpty()) return@withContext emptyList()
        val pages = db.pages().forDocument(docId).associate { it.id to it.pageIndex }
        val recordings = db.recordings().forDocument(docId).associate { it.id to it.startedAt }
        val live = rows.filter { r -> r.recordingId == null || r.recordingId in recordings }
            .map { it to orderRow(it) }
        ReadingOrder.sort(live, { it.second }, pages, recordings).map { (r, _) ->
            SourcedPassage(
                r.rowId,
                IndexedPassage(
                    documentId = docId,
                    pageId = r.pageId,
                    pageIndex = r.pageId?.let { pages[it] },
                    recordingId = r.recordingId,
                    timestampMs = r.timestampMs,
                    source = r.source,
                    text = r.text,
                ),
            )
        }
    }

    /** Content only: no title, tag or folder rows. */
    suspend fun content(docId: String): List<SourcedPassage> = load(docId).filter { it.passage.source !in META }

    private fun orderRow(r: TextIndexEntity) = OrderRow(r.rowId, r.pageId, r.recordingId, r.source, Bounds.parse(r.bounds), r.timestampMs)

    companion object {
        val META = setOf(TextSource.TITLE, TextSource.TAG, TextSource.FOLDER)
    }
}
