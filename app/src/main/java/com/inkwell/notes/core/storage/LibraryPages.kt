package com.inkwell.notes.core.storage

import android.util.Log
import androidx.room.withTransaction
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.naming.TitleRules
import com.inkwell.notes.core.settings.SettingsRepository
import com.inkwell.notes.core.storage.db.AppDatabase
import com.inkwell.notes.core.storage.db.PageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Page operations of [LibraryRepositoryImpl]. Every structural change runs in one database
 * transaction that renumbers the pages 0..n-1 and keeps `DocumentEntity.pageCount` and the
 * document's modifiedAt in step. Content files are written before rows are committed (and
 * removed again if the commit fails), so a page row never points at content that was not copied.
 *
 * Text-index bookkeeping: blank pages are created as already indexed; PDF pages as not yet
 * indexed (so their text is extracted); copies of up-to-date pages inherit the source's index
 * rows. Visual-only changes (rotation, paper, size) bump modifiedAt for renderers but keep an
 * up-to-date page marked indexed, so they never trigger needless recognition.
 */
internal class LibraryPages(
    private val db: AppDatabase,
    private val docFiles: DocumentFiles,
    private val settings: SettingsRepository,
    private val hooks: LibraryHooks,
    private val clock: () -> Long,
) {
    fun observePages(docId: String): Flow<List<PageMeta>> =
        db.pages().observeForDocument(docId).map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    suspend fun getPages(docId: String): List<PageMeta> = db.pages().forDocument(docId).map { it.toModel() }

    suspend fun getPage(pageId: String): PageMeta? = db.pages().get(pageId)?.toModel()

    suspend fun addPage(docId: String, atIndex: Int?, template: PageTemplate?): PageMeta {
        val now = clock()
        val page = db.withTransaction {
            val doc = db.documents().get(docId) ?: throw IllegalArgumentException("Document not found")
            val current = db.pages().forDocument(docId)
            val at = PageOrdering.insertionIndex(atIndex, current.size)
            val ref = current.getOrNull(at - 1) ?: current.getOrNull(at)
            val entity = when {
                template != null -> notebookPage(template, docId, now)
                ref != null -> blankLike(ref, now)
                doc.kind == DocKind.WHITEBOARD -> whiteboardPage(docId, now)
                else -> notebookPage(settings.current.notebookDefaults.template, docId, now)
            }
            commitOrder(docId, PageOrdering.insertAll(current, listOf(entity), at), now, newIds = setOf(entity.id))
            entity.copy(pageIndex = at)
        }
        if (page.pageIndex == 0) hooks.coverChanged(docId)
        return page.toModel()
    }

    suspend fun addPdfPages(docId: String, sizes: List<Pair<Float, Float>>, firstPdfIndex: Int, atIndex: Int?): List<PageMeta> {
        if (sizes.isEmpty()) return emptyList()
        val now = clock()
        val created = sizes.mapIndexed { i, (w, h) ->
            val (width, height) = if (w.isFinite() && h.isFinite() && w > 0f && h > 0f) {
                w.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT) to h.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT)
            } else {
                DEFAULT_WIDTH_PT to DEFAULT_HEIGHT_PT
            }
            PageEntity(
                id = newId(), documentId = docId, pageIndex = 0, widthPt = width, heightPt = height,
                paperStyle = PaperStyle.BLANK, paperColor = PDF_PAPER.color, lineColor = PDF_PAPER.lineColor,
                spacingPt = 0f, rotation = 0, bookmarked = false, bookmarkTitle = null,
                pdfPageIndex = firstPdfIndex.coerceAtLeast(0) + i, modifiedAt = now, thumbnailPath = null,
                // Not indexed yet: the indexer extracts the PDF text layer or runs OCR.
                indexedAt = 0,
            )
        }
        val (result, at) = db.withTransaction {
            db.documents().get(docId) ?: throw IllegalArgumentException("Document not found")
            val current = db.pages().forDocument(docId)
            val at = PageOrdering.insertionIndex(atIndex, current.size)
            commitOrder(docId, PageOrdering.insertAll(current, created, at), now, newIds = created.mapTo(HashSet()) { it.id })
            created.mapIndexed { i, p -> p.copy(pageIndex = at + i) } to at
        }
        if (at == 0) hooks.coverChanged(docId)
        return result.map { it.toModel() }
    }

    suspend fun deletePage(pageId: String) {
        val page = db.pages().get(pageId) ?: return
        val docId = page.documentId
        val now = clock()
        db.withTransaction {
            db.pages().delete(pageId)
            commitOrder(docId, db.pages().forDocument(docId), now)
            removeOutlineFor(docId, pageId)
            for (source in TextSource.entries) db.textIndex().deleteFor(docId, pageId, source.name)
        }
        docFiles.deletePageFiles(docId, pageId)
        if (page.pageIndex == 0) hooks.coverChanged(docId)
    }

    /** Outline entries that pointed at a deleted page go too; their children move up a level. */
    private suspend fun removeOutlineFor(docId: String, pageId: String) {
        val entries = db.outline().forDocument(docId)
        val doomed = entries.filter { it.pageId == pageId }.mapTo(HashSet()) { it.id }
        if (doomed.isEmpty()) return
        val byId = entries.associateBy { it.id }
        fun survivingParent(start: String?): String? {
            var p = start
            val seen = HashSet<String>()
            while (p != null && p in doomed && seen.add(p)) p = byId[p]?.parentId
            return if (p != null && p in doomed) null else p
        }
        for (e in entries) {
            if (e.id in doomed) continue
            if (e.parentId != null && e.parentId in doomed) db.outline().upsert(e.copy(parentId = survivingParent(e.parentId)))
        }
        doomed.forEach { db.outline().delete(it) }
    }

    suspend fun duplicatePage(pageId: String): PageMeta {
        val src = db.pages().get(pageId) ?: throw IllegalArgumentException("Page not found")
        return copyPage(src, src.documentId, atIndex = null, placeAfterSource = true)
    }

    suspend fun copyPageToDocument(pageId: String, targetDocId: String, atIndex: Int?): PageMeta {
        val src = db.pages().get(pageId) ?: throw IllegalArgumentException("Page not found")
        db.documents().get(targetDocId) ?: throw IllegalArgumentException("Document not found")
        return copyPage(src, targetDocId, atIndex, placeAfterSource = false)
    }

    private suspend fun copyPage(src: PageEntity, targetDocId: String, atIndex: Int?, placeAfterSource: Boolean): PageMeta {
        val sameDoc = src.documentId == targetDocId
        val newPageId = newId()
        val now = clock()

        // A PDF-backed page needs its PDF in the target: same file, a copy of it, or a flattened image.
        var pdfIndex = src.pdfPageIndex
        var background: ImageItem? = null
        var backgroundAsset: String? = null
        if (pdfIndex != null && !sameDoc && !docFiles.sameSourcePdf(src.documentId, targetDocId)) {
            val targetHasPdfPages = db.pages().forDocument(targetDocId).any { it.pdfPageIndex != null }
            val copied = !targetHasPdfPages && !docFiles.hasSourcePdf(targetDocId) &&
                docFiles.copySourcePdf(src.documentId, targetDocId)
            if (!copied) {
                val (wPx, hPx) = rasterSize(src.widthPt, src.heightPt)
                val png = try {
                    hooks.renderPdfPagePng(src.documentId, pdfIndex, wPx, hPx)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Log.w(TAG, "PDF page render failed: ${e.javaClass.simpleName}")
                    null
                }
                if (png != null) {
                    val assetId = "pdfpage-${newId()}.png"
                    docFiles.writeAsset(targetDocId, assetId, png)
                    backgroundAsset = assetId
                    background = ImageItem(
                        id = newId(), assetId = assetId, x = 0f, y = 0f,
                        width = src.widthPt, height = src.heightPt, createdAt = now,
                    )
                } else {
                    Log.w(TAG, "Copied a PDF page without its PDF background")
                }
                pdfIndex = null
            }
        }

        val wroteContent = docFiles.copyPageContent(
            src.documentId, src.id, targetDocId, newPageId,
            stripAudio = !sameDoc, copyAssets = !sameDoc, background = background,
        )
        val thumb = if (background == null) docFiles.copyPageThumbnail(src.documentId, src.id, targetDocId, newPageId) else null

        try {
            val (copy, fresh) = db.withTransaction {
                val current = db.pages().forDocument(targetDocId)
                val source = db.pages().get(src.id) ?: throw IllegalStateException("Page was deleted")
                val fresh = isIndexFresh(source) && background == null
                val at = if (placeAfterSource) {
                    current.indexOfFirst { it.id == source.id }.let { if (it < 0) current.size else it + 1 }
                } else {
                    PageOrdering.insertionIndex(atIndex, current.size)
                }
                val copy = source.copy(
                    id = newPageId, documentId = targetDocId, pdfPageIndex = pdfIndex, modifiedAt = now,
                    indexedAt = if (fresh) now else 0, bookmarked = false, bookmarkTitle = null, thumbnailPath = thumb,
                )
                commitOrder(targetDocId, PageOrdering.insertAll(current, listOf(copy), at), now, newIds = setOf(newPageId))
                if (fresh) copyIndexRows(src.documentId, mapOf(src.id to newPageId), targetDocId, now)
                copy.copy(pageIndex = at) to fresh
            }
            if (!fresh) hooks.pagesNeedIndexing(targetDocId, listOf(newPageId))
            if (copy.pageIndex == 0) hooks.coverChanged(targetDocId)
            return copy.toModel()
        } catch (e: Exception) {
            if (wroteContent) docFiles.deletePageFiles(targetDocId, newPageId)
            throw e
        }
    }

    suspend fun movePage(pageId: String, toIndex: Int) {
        val now = clock()
        val coverMoved = db.withTransaction {
            val page = db.pages().get(pageId) ?: return@withTransaction null
            val current = db.pages().forDocument(page.documentId)
            val from = current.indexOfFirst { it.id == pageId }
            val ordered = PageOrdering.move(current, from, toIndex)
            if (ordered === current) return@withTransaction null
            commitOrder(page.documentId, ordered, now)
            page.documentId to (current.first().id != ordered.first().id)
        }
        if (coverMoved?.second == true) hooks.coverChanged(coverMoved.first)
    }

    suspend fun reorderPages(docId: String, orderedPageIds: List<String>) {
        val now = clock()
        val firstChanged = db.withTransaction {
            val current = db.pages().forDocument(docId)
            val ids = PageOrdering.reorder(current.map { it.id }, orderedPageIds)
            if (ids == current.map { it.id }) return@withTransaction false
            val byId = current.associateBy { it.id }
            val ordered = ids.map { byId.getValue(it) }
            commitOrder(docId, ordered, now)
            current.first().id != ordered.first().id
        }
        if (firstChanged) hooks.coverChanged(docId)
    }

    suspend fun rotatePage(pageId: String, degreesClockwise: Int) {
        updatePage(pageId, visual = true) { it.copy(rotation = PageOrdering.normalizeRotation(it.rotation + degreesClockwise)) }
    }

    suspend fun setPaper(pageId: String, paper: PaperSpec) {
        updatePage(pageId, visual = true) { it.withPaper(paper) }
    }

    /** Applies [paper] to every page that is not a source-PDF page (whose paper is never visible). */
    suspend fun setPaperForAll(docId: String, paper: PaperSpec) {
        val now = clock()
        val changed = db.withTransaction {
            val updated = db.pages().forDocument(docId)
                .filter { it.pdfPageIndex == null }
                .mapNotNull { p -> p.withPaper(paper).takeIf { it != p }?.visuallyChanged(p, now) }
            if (updated.isEmpty()) return@withTransaction false
            db.pages().upsertAll(updated)
            db.documents().touch(docId, now)
            true
        }
        if (changed) hooks.coverChanged(docId)
    }

    suspend fun setPageSize(pageId: String, widthPt: Float, heightPt: Float) {
        updatePage(pageId, visual = true) { p ->
            when {
                // Both 0 (or less) = an unbounded whiteboard page.
                widthPt <= 0f && heightPt <= 0f -> p.copy(widthPt = 0f, heightPt = 0f)
                !widthPt.isFinite() || !heightPt.isFinite() || widthPt <= 0f || heightPt <= 0f -> p
                else -> p.copy(widthPt = widthPt.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT), heightPt = heightPt.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT))
            }
        }
    }

    suspend fun setBookmark(pageId: String, bookmarked: Boolean, title: String?) {
        updatePage(pageId, visual = false) { p ->
            if (!bookmarked) p.copy(bookmarked = false, bookmarkTitle = null)
            else p.copy(bookmarked = true, bookmarkTitle = title?.let { TitleRules.sanitize(it) } ?: p.bookmarkTitle)
        }
    }

    suspend fun pageContentChanged(docId: String, pageId: String) {
        val now = clock()
        db.withTransaction {
            db.pages().touch(pageId, now)
            db.documents().touch(docId, now)
        }
    }

    // ---- helpers

    /**
     * Writes [ordered] with indices 0..n-1 (only rows that are new or moved) and stores the
     * page count and modification time on the document. Must run inside a transaction.
     */
    private suspend fun commitOrder(docId: String, ordered: List<PageEntity>, now: Long, newIds: Set<String> = emptySet()) {
        val writes = ordered.mapIndexedNotNull { i, p -> if (p.pageIndex != i || p.id in newIds) p.copy(pageIndex = i) else null }
        if (writes.isNotEmpty()) db.pages().upsertAll(writes)
        val doc = db.documents().get(docId) ?: return
        db.documents().update(doc.copy(pageCount = ordered.size, modifiedAt = now))
    }

    private suspend fun updatePage(pageId: String, visual: Boolean, transform: (PageEntity) -> PageEntity) {
        val now = clock()
        val updated = db.withTransaction {
            val cur = db.pages().get(pageId) ?: return@withTransaction null
            val next = transform(cur).takeIf { it != cur } ?: return@withTransaction null
            val stored = if (visual) next.visuallyChanged(cur, now) else next
            db.pages().upsert(stored)
            if (visual) db.documents().touch(cur.documentId, now)
            stored
        }
        if (visual && updated != null && updated.pageIndex == 0) hooks.coverChanged(updated.documentId)
    }

    /** Bumps modifiedAt for renderers while keeping an up-to-date page marked as indexed. */
    private fun PageEntity.visuallyChanged(before: PageEntity, now: Long): PageEntity =
        copy(modifiedAt = now, indexedAt = if (isIndexFresh(before)) now else before.indexedAt)

    private fun isIndexFresh(p: PageEntity) = p.indexedAt > 0 && p.indexedAt >= p.modifiedAt

    private fun PageEntity.withPaper(paper: PaperSpec) =
        copy(paperStyle = paper.style, paperColor = paper.color, lineColor = paper.lineColor, spacingPt = paper.spacingPt)

    /** Copies index rows of the given pages (page-level text only; recordings are not copied). */
    suspend fun copyIndexRows(srcDoc: String, pageMap: Map<String, String>, dstDoc: String, now: Long) {
        val rows = db.textIndex().forDocument(srcDoc).filter { r ->
            r.pageId != null && r.pageId in pageMap && r.recordingId == null && r.source !in DOCUMENT_LEVEL_SOURCES
        }
        if (rows.isEmpty()) return
        db.textIndex().insertAll(rows.map { it.copy(rowId = 0, documentId = dstDoc, pageId = pageMap.getValue(it.pageId!!), updatedAt = now) })
    }

    /** A blank page that looks like [ref] as displayed (a rotated page's orientation is kept). */
    private fun blankLike(ref: PageEntity, now: Long): PageEntity {
        val quarter = ref.rotation == 90 || ref.rotation == 270
        return PageEntity(
            id = newId(), documentId = ref.documentId, pageIndex = 0,
            widthPt = if (quarter) ref.heightPt else ref.widthPt, heightPt = if (quarter) ref.widthPt else ref.heightPt,
            paperStyle = ref.paperStyle, paperColor = ref.paperColor, lineColor = ref.lineColor, spacingPt = ref.spacingPt,
            rotation = 0, bookmarked = false, bookmarkTitle = null, pdfPageIndex = null, modifiedAt = now,
            thumbnailPath = null, indexedAt = now,
        )
    }

    private fun rasterSize(widthPt: Float, heightPt: Float): Pair<Int, Int> {
        val w = if (widthPt > 0f) widthPt else DEFAULT_WIDTH_PT
        val h = if (heightPt > 0f) heightPt else DEFAULT_HEIGHT_PT
        val scale = minOf(RASTER_SCALE, RASTER_MAX_PX / maxOf(w, h))
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "LibraryPages"
        const val MIN_PAGE_PT = 36f
        const val MAX_PAGE_PT = 14_400f
        const val DEFAULT_WIDTH_PT = 595f
        const val DEFAULT_HEIGHT_PT = 842f
        private const val RASTER_SCALE = 2.5f
        private const val RASTER_MAX_PX = 2_480f
        private val PDF_PAPER = PaperSpec(style = PaperStyle.BLANK)
        private val DOCUMENT_LEVEL_SOURCES = setOf(TextSource.TITLE, TextSource.TAG, TextSource.FOLDER)

        fun newId(): String = UUID.randomUUID().toString()

        fun whiteboardPage(docId: String, now: Long): PageEntity {
            val paper = PaperSpec(style = PaperStyle.DOT_GRID)
            return PageEntity(
                id = newId(), documentId = docId, pageIndex = 0, widthPt = 0f, heightPt = 0f,
                paperStyle = paper.style, paperColor = paper.color, lineColor = paper.lineColor, spacingPt = paper.spacingPt,
                rotation = 0, bookmarked = false, bookmarkTitle = null, pdfPageIndex = null, modifiedAt = now,
                thumbnailPath = null, indexedAt = now,
            )
        }

        fun notebookPage(template: PageTemplate, docId: String, now: Long) = PageEntity(
            id = newId(), documentId = docId, pageIndex = 0, widthPt = template.widthPt(), heightPt = template.heightPt(),
            paperStyle = template.paper.style, paperColor = template.paper.color, lineColor = template.paper.lineColor,
            spacingPt = template.paper.spacingPt, rotation = 0, bookmarked = false, bookmarkTitle = null,
            pdfPageIndex = null, modifiedAt = now, thumbnailPath = null, indexedAt = now,
        )
    }
}
