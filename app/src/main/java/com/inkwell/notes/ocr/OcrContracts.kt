package com.inkwell.notes.ocr

import android.graphics.Bitmap
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.RecognitionResult
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ModelStatus { NOT_DOWNLOADED, DOWNLOADING, READY, UNAVAILABLE }

/** On-device OCR (ML Kit text recognition) and handwriting recognition (ML Kit digital ink), plus optional AI vision. */
interface RecognitionService {
    /** Recognises handwriting from strokes (page coordinates). Null when no model is available. */
    suspend fun recognizeStrokes(strokes: List<Stroke>, languageTag: String? = null): RecognitionResult?

    /**
     * OCR on an image. When [pageWidthPt]/[pageHeightPt] are given, block bounds are mapped
     * from bitmap pixels to page points.
     */
    suspend fun recognizeBitmap(bitmap: Bitmap, pageWidthPt: Float? = null, pageHeightPt: Float? = null): RecognitionResult

    /** Vision-model recognition for messy handwriting, formulas, tables; keeps uncertainty. */
    suspend fun recognizeWithAi(bitmap: Bitmap, hint: String? = null): RecognitionResult?

    fun handwritingModelStatus(languageTag: String): StateFlow<ModelStatus>
    suspend fun downloadHandwritingModel(languageTag: String): Boolean
}

/** A piece of indexed text, used for AI retrieval and naming. */
data class IndexedPassage(
    val documentId: String,
    val pageId: String?,
    val pageIndex: Int?,
    val recordingId: String?,
    val timestampMs: Long?,
    val source: TextSource,
    val text: String,
)

/** Local full-text index (Room FTS4) over every text source, with optional semantic search. */
interface SearchIndex {
    suspend fun search(query: String, limit: Int = 60, semantic: Boolean = false): List<SearchHit>
    suspend fun indexDocumentMeta(doc: DocumentMeta)
    suspend fun replacePageText(docId: String, pageId: String, source: TextSource, blocks: List<RecognizedBlock>, engine: String)
    suspend fun replaceTranscript(docId: String, recordingId: String, segments: List<TranscriptSegment>, engine: String)
    suspend fun removeDocument(docId: String)
    /** All indexed text of a document in reading order. */
    suspend fun documentText(docId: String): List<IndexedPassage>
    /** Most relevant passages for [query] (within [docId] when given), capped at [maxChars] total. */
    suspend fun retrieve(docId: String?, query: String, maxChars: Int = 12_000): List<IndexedPassage>
}

/**
 * Asynchronous recognition pipeline: decides whether content has text, runs local OCR /
 * handwriting recognition off the UI thread (WorkManager), stores results with confidence
 * and location, and escalates to AI only when configured.
 */
interface IndexingScheduler {
    /** Page ink/content changed; re-recognise after edits settle. */
    fun pageChanged(docId: String, pageId: String)
    /** A PDF/images were imported; extract text layer and OCR pages without one. */
    fun documentImported(docId: String)
    fun documentMetaChanged(docId: String)
    /** Re-index everything (settings action). */
    fun reindexAll()
}

/** SCAFFOLD STUB — replaced by the OCR implementation. */
class RecognitionServiceImpl(private val graph: AppGraph) : RecognitionService {
    override suspend fun recognizeStrokes(strokes: List<Stroke>, languageTag: String?): RecognitionResult? = null
    override suspend fun recognizeBitmap(bitmap: Bitmap, pageWidthPt: Float?, pageHeightPt: Float?): RecognitionResult =
        RecognitionResult("", emptyList(), null, "none")
    override suspend fun recognizeWithAi(bitmap: Bitmap, hint: String?): RecognitionResult? = null
    override fun handwritingModelStatus(languageTag: String): StateFlow<ModelStatus> = MutableStateFlow(ModelStatus.UNAVAILABLE)
    override suspend fun downloadHandwritingModel(languageTag: String): Boolean = false
}

/** SCAFFOLD STUB — replaced by the search implementation. */
class SearchIndexImpl(private val graph: AppGraph) : SearchIndex {
    override suspend fun search(query: String, limit: Int, semantic: Boolean): List<SearchHit> = emptyList()
    override suspend fun indexDocumentMeta(doc: DocumentMeta) {}
    override suspend fun replacePageText(docId: String, pageId: String, source: TextSource, blocks: List<RecognizedBlock>, engine: String) {}
    override suspend fun replaceTranscript(docId: String, recordingId: String, segments: List<TranscriptSegment>, engine: String) {}
    override suspend fun removeDocument(docId: String) {}
    override suspend fun documentText(docId: String): List<IndexedPassage> = emptyList()
    override suspend fun retrieve(docId: String?, query: String, maxChars: Int): List<IndexedPassage> = emptyList()
}

/** SCAFFOLD STUB — replaced by the OCR implementation. */
class IndexingSchedulerImpl(private val graph: AppGraph) : IndexingScheduler {
    override fun pageChanged(docId: String, pageId: String) {}
    override fun documentImported(docId: String) {}
    override fun documentMetaChanged(docId: String) {}
    override fun reindexAll() {}
}
