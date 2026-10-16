package com.inkwell.notes.ocr.index

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.ocr.IndexedPassage
import com.inkwell.notes.ocr.text.Chunk
import com.inkwell.notes.ocr.text.Chunker
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.Vectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Passages most relevant to a question, for AI context: ~600-character chunks in reading
 * order scored by keywords (and by embedding similarity where vectors exist), the best chunks
 * plus their neighbours within the character budget. Without any match the opening passages
 * are used, so the model still sees how the document begins.
 */
internal class Retriever(
    private val graph: AppGraph,
    private val loader: DocumentTextLoader,
    private val semantic: SemanticIndex,
) {
    suspend fun retrieve(docId: String?, question: String, maxChars: Int): List<IndexedPassage> = withContext(Dispatchers.IO) {
        if (maxChars <= 0) return@withContext emptyList()
        val terms = Chunker.queryTerms(question)
        val docIds = if (docId != null) listOf(docId) else candidateDocuments(terms)
        if (docIds.isEmpty()) return@withContext emptyList()
        val chunks = docIds.flatMap { Chunker.chunk(loader.content(it)) }
        if (chunks.isEmpty()) return@withContext emptyList()
        val keyword = Chunker.keywordScores(chunks, terms)
        val sims = similarities(chunks, docIds, question)
        val scores = Chunker.combine(keyword, sims)
        val chosen = if (docId == null && scores.none { it > 0.0 }) emptyList() else Chunker.select(chunks, scores, maxChars)
        chosen.map { it.toPassage() }
    }

    /** Documents (not in the trash) with the most matches for any of [terms]. */
    private suspend fun candidateDocuments(terms: List<String>): List<String> {
        if (terms.isEmpty()) return emptyList()
        val counts = HashMap<String, Int>()
        for (t in terms.take(MAX_TERMS)) {
            // The terms are fully lower-cased for in-memory matching; the index folds ASCII
            // only, so every case variant of a non-ASCII term is queried.
            val rows = ArrayList<com.inkwell.notes.core.storage.db.TextSearchRow>()
            for (match in FtsQuery.variants(listOf(t))) {
                try {
                    rows += graph.db.textIndex().searchLive(match, PER_TERM_ROWS)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    break
                }
            }
            // Documents matching more of the question's words rank first; repeats count a little.
            rows.distinctBy { it.rowId }.groupingBy { it.documentId }.eachCount()
                .forEach { (d, n) -> counts[d] = (counts[d] ?: 0) + minOf(n, 5) }
        }
        return counts.entries.sortedByDescending { it.value }.take(MAX_DOCS).map { it.key }
    }

    /** Max cosine of each chunk's rows to the question, when vectors exist; null otherwise. */
    private suspend fun similarities(chunks: List<Chunk>, docIds: List<String>, question: String): List<Float?>? {
        if (!semantic.enabled()) return null
        val model = semantic.model() ?: return null
        return try {
            val vectors = HashMap<Long, ByteArray>()
            for (d in docIds) vectors += semantic.vectorsFor(model, d)
            if (vectors.isEmpty()) return null
            val q = semantic.embed(question) ?: return null
            val qn = Vectors.norm(q)
            chunks.map { c -> c.rowIds.mapNotNull { id -> vectors[id]?.let { Vectors.cosineEncoded(q, qn, it) } }.maxOrNull() }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Semantic retrieval unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "Retriever"
        private const val MAX_TERMS = 8
        private const val PER_TERM_ROWS = 200
        private const val MAX_DOCS = 6
    }
}
