package com.inkwell.notes.editor

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.editor.logic.DebouncedSaveScheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** The newest content of one page that still has to reach the disk. */
class PendingPageSave(val page: PageMeta, val content: PageContent)

/**
 * Autosave for one document, shared by every editor showing it. An editor that is reopened
 * (back navigation, side by side, a tab) while the previous one is still saving reads the
 * unsaved content from here instead of a stale file, so no edit is ever lost or overwritten.
 */
class DocSaver internal constructor(private val graph: AppGraph, val docId: String) {
    private val errorListeners = CopyOnWriteArraySet<(Throwable, Boolean) -> Unit>()

    val scheduler = DebouncedSaveScheduler<String, PendingPageSave>(
        scope = graph.appScope,
        delayMs = SAVE_DEBOUNCE_MS,
        save = { _, p -> persist(p) },
        onError = { pageId, error, willRetry ->
            Log.e(TAG, "Saving page $pageId failed (retry=$willRetry): ${error.javaClass.simpleName}")
            errorListeners.forEach { it(error, willRetry) }
        },
    )

    fun addErrorListener(l: (Throwable, Boolean) -> Unit) { errorListeners += l }
    fun removeErrorListener(l: (Throwable, Boolean) -> Unit) { errorListeners -= l }

    private suspend fun persist(p: PendingPageSave) {
        // A page deleted while its save was pending must not be recreated on disk.
        if (graph.library.getPage(p.page.id) == null) return
        graph.pageStore.save(docId, p.page.id, p.content)
        runCatching { graph.library.pageContentChanged(docId, p.page.id) }.onFailure { Log.w(TAG, "pageContentChanged failed", it) }
        runCatching { graph.indexing.pageChanged(docId, p.page.id) }.onFailure { Log.w(TAG, "indexing notify failed", it) }
        runCatching { graph.sync.onDocumentChanged(docId) }.onFailure { Log.w(TAG, "sync notify failed", it) }
    }

    companion object {
        private const val TAG = "DocSaver"
        const val SAVE_DEBOUNCE_MS = 400L
        private val savers = ConcurrentHashMap<String, DocSaver>()

        fun forDocument(graph: AppGraph, docId: String): DocSaver = savers.getOrPut(docId) { DocSaver(graph, docId) }
    }
}
