package com.inkwell.notes.pdf

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded text of source-PDF pages (pdfbox [PDFTextStripper], reading order by position).
 * Results are cached per page. The parsed document stays open between calls, because the
 * indexer asks for every page in turn, and closes after a short idle period.
 */
internal class PdfTextExtractor(
    private val pdfBox: PdfBoxOps,
    private val sourceFile: (docId: String) -> File,
    private val scope: CoroutineScope,
) {
    private val cache = SizedLruCache<String, String>(MAX_CACHED_CHARS, MAX_CACHED_CHARS / 4) { it.length.toLong() + 16 }
    private val mutex = Mutex()

    private class OpenText(val docId: String, val file: File, val length: Long, val modified: Long, val doc: PDDocument)

    // Guarded by [mutex].
    private var current: OpenText? = null
    private var idleClose: Job? = null

    /** Bumped by [invalidate]; an extraction that started before keeps its result out of the cache. */
    private val generations = ConcurrentHashMap<String, Int>()

    suspend fun pageText(docId: String, pdfPageIndex: Int): String {
        if (pdfPageIndex < 0) return ""
        val key = PdfText.cacheKey(docId, pdfPageIndex)
        cache.get(key)?.let { return it }
        val generation = generations[docId] ?: 0
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(key)?.let { return@withLock it }
                val doc = openLocked(docId) ?: return@withLock ""
                if (pdfPageIndex >= doc.numberOfPages) return@withLock ""
                val text = try {
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = pdfPageIndex + 1
                        endPage = pdfPageIndex + 1
                        lineSeparator = "\n"
                    }
                    PdfText.normalize(stripper.getText(doc))
                } catch (e: Exception) {
                    // Broken content streams or fonts: OCR covers the page instead.
                    Log.w(TAG, "Text extraction failed for a page (${e.javaClass.simpleName})")
                    null
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "Text extraction ran out of memory")
                    closeLocked()
                    null
                }
                scheduleIdleClose()
                if (text != null && (generations[docId] ?: 0) == generation) cache.put(key, text)
                text.orEmpty()
            }
        }
    }

    /** Forgets cached text and closes the parsed document (source replaced or document closed). */
    fun invalidate(docId: String) {
        generations.merge(docId, 1, Int::plus)
        cache.removeIf { PdfText.isKeyOf(it, docId) }
        scope.launch(Dispatchers.IO) {
            mutex.withLock { if (current?.docId == docId) closeLocked() }
        }
    }

    private fun openLocked(docId: String): PDDocument? {
        val file = sourceFile(docId)
        if (!file.isFile) {
            closeLocked()
            return null
        }
        current?.let { c ->
            if (c.docId == docId && c.length == file.length() && c.modified == file.lastModified()) return c.doc
            closeLocked()
        }
        return try {
            val doc = pdfBox.open(file)
            current = OpenText(docId, file, file.length(), file.lastModified(), doc)
            doc
        } catch (e: Exception) {
            Log.w(TAG, "Opening a PDF for text failed (${e.javaClass.simpleName})")
            null
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Opening a PDF for text ran out of memory")
            null
        }
    }

    private fun closeLocked() {
        val c = current ?: return
        current = null
        runCatching { c.doc.close() }
    }

    private fun scheduleIdleClose() {
        idleClose?.cancel()
        idleClose = scope.launch(Dispatchers.IO) {
            delay(IDLE_CLOSE_MS)
            mutex.withLock { closeLocked() }
        }
    }

    companion object {
        private const val TAG = "PdfText"
        private const val IDLE_CLOSE_MS = 20_000L
        /** About 8 MB of UTF-16 text. */
        private const val MAX_CACHED_CHARS = 4L * 1024 * 1024
    }
}
