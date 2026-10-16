package com.inkwell.notes.ocr.index

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.storage.db.EmbeddingEntity
import com.inkwell.notes.ocr.text.Vectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.PriorityQueue
import kotlin.coroutines.coroutineContext

/**
 * Optional semantic search: embeddings of indexed passages (computed in the background, in
 * batches, only while the user has semantic search on and an embedding model configured) and
 * nearest-neighbour lookup by cosine similarity, streamed from the database page by page.
 */
internal class SemanticIndex(private val graph: AppGraph) {

    /** True when the user turned semantic search on and an embedding model is reachable. */
    fun enabled(): Boolean = try {
        val ai = graph.settings.current.ai
        ai.enabled && ai.semanticSearch && graph.ai.isAvailable(AiTask.EMBEDDINGS)
    } catch (e: Exception) {
        false
    }

    /** Identifier stored with vectors, so switching models never mixes vector spaces. */
    fun model(): String? = try {
        graph.ai.modelFor(AiTask.EMBEDDINGS)?.toString()
    } catch (e: Exception) {
        null
    }

    /** Embeds [text]; throws when the provider fails. Null when no model is configured. */
    suspend fun embed(text: String): FloatArray? = graph.ai.embed(listOf(text.take(MAX_EMBED_CHARS)))?.firstOrNull()

    /**
     * The [k] stored passages most similar to [query] (row id to cosine), at least [minSimilarity],
     * limited to [documents] when given.
     */
    suspend fun nearest(model: String, query: FloatArray, k: Int, minSimilarity: Float, documents: Set<String>?): List<Pair<Long, Float>> =
        withContext(Dispatchers.IO) {
            val qn = Vectors.norm(query)
            if (qn == 0.0) return@withContext emptyList()
            val heap = PriorityQueue<Pair<Long, Float>>(k + 1, compareBy { it.second })
            val seen = HashMap<Long, Float>()
            var after = 0L
            while (true) {
                coroutineContext.ensureActive()
                val page = graph.db.embeddings().page(model, after, PAGE)
                if (page.isEmpty()) break
                for (e in page) {
                    if (documents != null && e.documentId !in documents) continue
                    val sim = Vectors.cosineEncoded(query, qn, e.vector)
                    if (sim < minSimilarity) continue
                    val prev = seen[e.textRowId]
                    if (prev != null && prev >= sim) continue
                    seen[e.textRowId] = sim
                    heap.add(e.textRowId to sim)
                    if (heap.size > k * 2) heap.poll()
                }
                after = page.last().rowId
                if (page.size < PAGE) break
            }
            heap.toList()
                .groupBy { it.first }
                .map { (id, sims) -> id to sims.maxOf { it.second } }
                .sortedByDescending { it.second }
                .take(k)
        }

    /** Vectors of one document's rows (row id to encoded vector). */
    suspend fun vectorsFor(model: String, docId: String): Map<Long, ByteArray> = withContext(Dispatchers.IO) {
        graph.db.embeddings().forDocument(model, docId).associate { it.textRowId to it.vector }
    }

    /**
     * Embeds passages that have no vector yet, [BATCH] at a time, up to [maxBatches] batches.
     * Stops quietly when embeddings are unavailable (offline, no key, budget reached).
     * Returns how many passages were embedded.
     */
    suspend fun backfill(maxBatches: Int = MAX_BATCHES_PER_RUN): Int {
        if (!enabled()) return 0
        val model = model() ?: return 0
        var total = 0
        try {
            for (i in 0 until maxBatches) {
                coroutineContext.ensureActive()
                val rows = withContext(Dispatchers.IO) { graph.db.textIndex().withoutEmbedding(model, BATCH) }
                if (rows.isEmpty()) break
                val vectors = graph.ai.embed(rows.map { it.text.take(MAX_EMBED_CHARS) }) ?: break
                if (vectors.size != rows.size) break
                withContext(Dispatchers.IO) {
                    graph.db.embeddings().insertAll(
                        rows.zip(vectors).map { (r, v) ->
                            EmbeddingEntity(textRowId = r.rowId, documentId = r.documentId, model = model, vector = Vectors.encode(v))
                        },
                    )
                }
                total += rows.size
                if (rows.size < BATCH) break
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Embedding backfill stopped: ${e.javaClass.simpleName}")
        } finally {
            if (total > 0) withContext(Dispatchers.IO) { runCatching { graph.db.embeddings().deleteOrphans() } }
        }
        return total
    }

    companion object {
        private const val TAG = "SemanticIndex"
        const val BATCH = 64
        const val MAX_BATCHES_PER_RUN = 16
        const val MAX_EMBED_CHARS = 2_000
        private const val PAGE = 512
    }
}
