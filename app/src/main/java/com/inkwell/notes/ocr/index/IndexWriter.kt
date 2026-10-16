package com.inkwell.notes.ocr.index

import androidx.room.withTransaction
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.db.AppDatabase
import com.inkwell.notes.core.storage.db.TextIndexEntity
import com.inkwell.notes.ocr.text.Bounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One piece of text to store, with where it is and how it was obtained. */
internal data class IndexEntry(
    val text: String,
    val bounds: FloatArray? = null,
    val confidence: Float? = null,
    val engine: String,
    val timestampMs: Long? = null,
)

/**
 * Writes the text index. Every replacement is one transaction (old rows of that page and
 * source go, new rows come in, embeddings of replaced rows are dropped), so search never sees
 * a half-updated page.
 */
internal class IndexWriter(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun replacePage(docId: String, pageId: String, source: TextSource, entries: List<IndexEntry>) =
        replacePageSources(docId, pageId, mapOf(source to entries))

    /** Replaces several sources of one page in a single transaction. */
    suspend fun replacePageSources(docId: String, pageId: String, sources: Map<TextSource, List<IndexEntry>>) = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext
        val now = clock()
        val rows = sources.mapValues { (source, entries) -> entries.mapNotNull { pageRow(docId, pageId, source, it, now) } }
        db.withTransaction {
            if (db.documents().get(docId) == null) {
                db.textIndex().deleteDocument(docId)
                return@withTransaction
            }
            for ((source, list) in rows) db.textIndex().replace(docId, pageId, source.name, list)
            db.embeddings().deleteStaleForDocument(docId)
        }
    }

    private fun pageRow(docId: String, pageId: String, source: TextSource, e: IndexEntry, now: Long): TextIndexEntity? {
        val text = clean(e.text) ?: return null
        return TextIndexEntity(
            documentId = docId,
            pageId = pageId,
            recordingId = null,
            source = source,
            text = text,
            confidence = e.confidence?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            timestampMs = e.timestampMs,
            bounds = Bounds.format(e.bounds),
            engine = e.engine,
            updatedAt = now,
        )
    }

    /** Title and tags; folder names are matched live at search time. */
    suspend fun replaceMeta(doc: DocumentMeta) = withContext(Dispatchers.IO) {
        val now = clock()
        val rows = ArrayList<TextIndexEntity>()
        clean(doc.title)?.let { rows += metaRow(doc.id, TextSource.TITLE, it, now) }
        doc.tags.mapNotNull { clean(it) }.distinctBy { it.lowercase() }.forEach { rows += metaRow(doc.id, TextSource.TAG, it, now) }
        db.withTransaction {
            if (db.documents().get(doc.id) == null) return@withTransaction
            db.textIndex().deleteFor(doc.id, null, TextSource.TITLE.name)
            db.textIndex().deleteFor(doc.id, null, TextSource.TAG.name)
            if (rows.isNotEmpty()) db.textIndex().insertAll(rows)
            db.embeddings().deleteStaleForDocument(doc.id)
        }
    }

    suspend fun replaceTranscript(docId: String, recordingId: String, segments: List<TranscriptSegment>, engine: String) =
        withContext(Dispatchers.IO) {
            val now = clock()
            val rows = segments.sortedBy { it.startMs }.mapNotNull { s ->
                val text = clean(s.text) ?: return@mapNotNull null
                TextIndexEntity(
                    documentId = docId,
                    pageId = null,
                    recordingId = recordingId,
                    source = TextSource.TRANSCRIPT,
                    text = text,
                    confidence = null,
                    timestampMs = s.startMs.coerceAtLeast(0),
                    bounds = null,
                    engine = engine,
                    updatedAt = now,
                )
            }
            db.withTransaction {
                db.textIndex().deleteForRecording(recordingId)
                if (rows.isNotEmpty() && db.documents().get(docId) != null) db.textIndex().insertAll(rows)
                db.embeddings().deleteStaleForDocument(docId)
            }
        }

    suspend fun removeDocument(docId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.textIndex().deleteDocument(docId)
            db.embeddings().deleteDocument(docId)
        }
    }

    /** Drops rows and vectors of documents, pages and recordings that no longer exist. */
    suspend fun deleteOrphans() = withContext(Dispatchers.IO) {
        db.textIndex().deleteOrphans()
        db.embeddings().deleteOrphans()
    }

    private fun metaRow(docId: String, source: TextSource, text: String, now: Long) = TextIndexEntity(
        documentId = docId, pageId = null, recordingId = null, source = source, text = text, confidence = null,
        timestampMs = null, bounds = null, engine = "meta", updatedAt = now,
    )

    companion object {
        /** Longest text stored in one row; longer passages are split by the producers. */
        const val MAX_ROW_CHARS = 20_000

        fun clean(text: String): String? {
            val t = text.replace('\u0000', ' ').trim()
            if (t.isEmpty()) return null
            return if (t.length > MAX_ROW_CHARS) t.substring(0, MAX_ROW_CHARS) else t
        }
    }
}
