package com.inkwell.notes.typed

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.typed.model.TypedCodec
import com.inkwell.notes.whiteboard.logic.DebouncedSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Outcome of reading typed.json. */
sealed interface TypedLoad {
    data class Loaded(val document: TypedDocument, val isNew: Boolean) : TypedLoad
    /** The file exists but neither it nor its backup can be parsed. Never overwrite it. */
    data object Unreadable : TypedLoad
}

/**
 * Loading and debounced, atomic saving of one typed document (files/docs/<id>/typed.json),
 * shared by every screen showing it so a reopened editor sees unsaved edits. After each
 * save the library entry is touched, the search index updated and sync notified.
 */
class TypedStore private constructor(private val graph: AppGraph, val docId: String) {
    private val listeners = CopyOnWriteArraySet<(Throwable?, Boolean) -> Unit>()

    val saver = DebouncedSaver<String, TypedDocument>(
        scope = graph.appScope,
        delayMs = SAVE_DEBOUNCE_MS,
        save = { _, doc -> persist(doc) },
        onResult = { _, error, willRetry ->
            if (error != null) Log.e(TAG, "Saving typed document failed (retry=$willRetry): ${error.javaClass.simpleName}")
            listeners.forEach { it(error, willRetry) }
        },
    )

    fun addListener(l: (Throwable?, Boolean) -> Unit) { listeners += l }
    fun removeListener(l: (Throwable?, Boolean) -> Unit) { listeners -= l }

    suspend fun load(): TypedLoad {
        saver.pending(docId)?.let { return TypedLoad.Loaded(it, isNew = false) }
        return withContext(Dispatchers.IO) {
            val file = graph.files.typedFile(docId)
            if (!file.exists() || file.length() == 0L) {
                val bak = FileStore.backupOf(file)
                val recovered = if (bak.exists()) TypedCodec.decodeOrNull(runCatching { bak.readText() }.getOrNull()) else null
                return@withContext if (recovered != null) TypedLoad.Loaded(recovered.ensureNotEmpty(), false)
                else TypedLoad.Loaded(TypedCodec.emptyDocument(), isNew = true)
            }
            val primary = TypedCodec.decodeOrNull(runCatching { file.readText() }.getOrNull())
            if (primary != null) return@withContext TypedLoad.Loaded(primary.ensureNotEmpty(), false)
            val backup = FileStore.backupOf(file).takeIf { it.exists() }?.let { TypedCodec.decodeOrNull(runCatching { it.readText() }.getOrNull()) }
            if (backup != null) {
                Log.w(TAG, "Recovered typed document from backup")
                TypedLoad.Loaded(backup.ensureNotEmpty(), false)
            } else {
                TypedLoad.Unreadable
            }
        }
    }

    fun submit(doc: TypedDocument) = saver.submit(docId, doc)

    suspend fun flush() = saver.flush()

    private suspend fun persist(doc: TypedDocument) {
        // A document deleted while a save was pending must not be recreated.
        val exists = runCatching { graph.library.getDocument(docId) != null }.getOrDefault(true)
        if (!exists) return
        val json = TypedCodec.encode(doc)
        withContext(Dispatchers.IO) {
            FileStore.writeAtomically(graph.files.typedFile(docId)) { it.write(json.encodeToByteArray()) }
        }
        runCatching { graph.library.touch(docId) }.onFailure { Log.w(TAG, "touch failed", it) }
        runCatching { graph.search.replacePageText(docId, PAGE_ID, TextSource.TYPED, searchBlocks(doc), ENGINE) }
            .onFailure { Log.w(TAG, "indexing failed", it) }
        runCatching { graph.sync.onDocumentChanged(docId) }.onFailure { Log.w(TAG, "sync notify failed", it) }
    }

    private fun TypedDocument.ensureNotEmpty(): TypedDocument = if (blocks.isEmpty()) TypedCodec.emptyDocument() else this

    companion object {
        private const val TAG = "TypedStore"
        const val SAVE_DEBOUNCE_MS = 500L
        /** Pseudo page id under which typed text is indexed. */
        const val PAGE_ID = "typed"
        const val ENGINE = "typed"
        private val stores = ConcurrentHashMap<String, TypedStore>()

        fun forDocument(graph: AppGraph, docId: String): TypedStore = stores.getOrPut(docId) { TypedStore(graph, docId) }

        /** One indexed passage per non-empty block, in reading order (bounds carry the block index). */
        fun searchBlocks(doc: TypedDocument): List<RecognizedBlock> =
            doc.blocks.mapIndexedNotNull { i, b ->
                val text = BlockOps.blockText(b)
                if (text.isBlank()) null else RecognizedBlock(text, floatArrayOf(0f, i.toFloat(), 0f, i.toFloat()), 1f)
            }
    }
}
