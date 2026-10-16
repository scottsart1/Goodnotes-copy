package com.inkwell.notes.pdf

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

/**
 * Structural PDF work with pdfbox-android: unlocking, repairing, appending and building
 * page subsets. Every function blocks; callers run them on [kotlinx.coroutines.Dispatchers.IO].
 * Documents spill to temp files in [tempDir] beyond [MAIN_MEMORY_BYTES], so large PDFs do not
 * exhaust the heap.
 */
internal class PdfBoxOps(
    private val initialise: () -> Unit,
    private val tempDir: () -> File,
) {
    /** Uses the app's assets for pdfbox fonts and glyph lists. */
    constructor(context: Context, tempDir: () -> File) : this({ PDFBoxResourceLoader.init(context.applicationContext) }, tempDir)

    @Volatile private var initialised = false

    /** PDFBoxResourceLoader must be initialised once before fonts or text are touched. */
    fun ensureInit() {
        if (initialised) return
        synchronized(this) {
            if (!initialised) {
                initialise()
                initialised = true
            }
        }
    }

    private fun memory(): MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).setTempDir(tempDir())

    /**
     * Opens [file]. Encrypted files open with the empty user password (owner-password-only
     * files); a real password makes this throw
     * [com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException].
     */
    fun open(file: File): PDDocument {
        ensureInit()
        val doc = PDDocument.load(file, "", memory())
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    fun newDocument(): PDDocument {
        ensureInit()
        return PDDocument(memory())
    }

    /**
     * Rewrites [file] into [out] with all security removed. pdfbox's lenient parser also
     * rebuilds broken cross-reference tables, so this doubles as a repair pass.
     */
    fun rewrite(file: File, out: File) {
        open(file).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    /**
     * Writes [existing] followed by every page of [incoming] to [out]. Returns the number of
     * pages [existing] had, which is the pdfPageIndex of the first appended page.
     */
    fun append(existing: File, incoming: File, out: File): Int = open(incoming).use { src ->
        val added = src.numberOfPages
        try {
            appendInto(existing, out, added) { dest ->
                PDFMergerUtility().apply { isIgnoreAcroFormErrors = true }.appendDocument(dest, src)
            }
        } catch (e: Exception) {
            // A full disk fails the same way twice; anything else gets a plain page copy.
            if (PdfErrors.isOutOfSpace(e)) throw e
            Log.w(TAG, "Full merge failed (${e.javaClass.simpleName}); copying pages only")
            appendInto(existing, out, added) { dest -> importPages(dest, List(added) { src.getPage(it) }) }
        }
    }

    private inline fun appendInto(existing: File, out: File, added: Int, block: (PDDocument) -> Unit): Int =
        open(existing).use { dest ->
            val before = dest.numberOfPages
            block(dest)
            if (dest.numberOfPages != before + added) throw IOException("Unexpected page count after merge")
            dest.isAllSecurityToBeRemoved = true
            out.delete()
            dest.save(out)
            before
        }

    /**
     * Writes a new PDF to [out] whose page i is page `pages[i].second` of file `pages[i].first`.
     * Each source file is opened once and kept open until the result is saved (pdfbox shares
     * resources between them until then).
     */
    fun subset(pages: List<Pair<File, Int>>, out: File) {
        val open = LinkedHashMap<File, PDDocument>()
        try {
            newDocument().use { dest ->
                val sources = pages.map { (file, index) ->
                    val src = open.getOrPut(file) { open(file) }
                    if (index !in 0 until src.numberOfPages) throw IOException("Page $index out of range")
                    src.getPage(index)
                }
                importPages(dest, sources)
                out.delete()
                dest.save(out)
            }
        } finally {
            open.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Copies pages with their inherited attributes, then points links between copied pages at
     * the copies. Links to pages that were not copied are cut, so the rest of the source
     * document is not dragged into the new file through them.
     */
    private fun importPages(dest: PDDocument, pages: List<PDPage>) {
        val copies = IdentityHashMap<COSDictionary, PDPage>()
        val imported = pages.map { page ->
            val copy = dest.importPage(page)
            // pdfbox-android's importPage only logs a warning for resources inherited from the
            // page tree; without them the text and images of such pages would disappear.
            val resources = page.resources
            if (resources != null && !page.cosObject.containsKey(COSName.RESOURCES)) copy.resources = resources
            copies[page.cosObject] = copy
            copy
        }
        for (copy in imported) relink(copy, copies)
    }

    private fun relink(page: PDPage, copies: Map<COSDictionary, PDPage>) {
        try {
            for (annotation in page.annotations) {
                if (annotation is PDAnnotationLink) {
                    val destination = annotation.destination ?: (annotation.action as? PDActionGoTo)?.destination
                    if (destination is PDPageDestination) {
                        val target = destination.page
                        if (target != null) destination.setPage(copies[target.cosObject])
                    }
                }
                annotation.setPage(page)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Annotations of a page could not be processed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "PdfBoxOps"
        const val MAIN_MEMORY_BYTES = 48L * 1024 * 1024
    }
}
