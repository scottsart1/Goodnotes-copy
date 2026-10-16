package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.StorageInfo
import kotlinx.coroutines.flow.Flow

/**
 * The library: folders, documents and pages. Implemented by [LibraryRepositoryImpl].
 * Every mutation updates modifiedAt and keeps the "YYYYMMDD_Topic" naming rules.
 */
interface LibraryRepository {
    // ---- Folders
    fun observeFolders(): Flow<List<Folder>>
    suspend fun getFolder(id: String): Folder?
    suspend fun createFolder(name: String, parentId: String?, color: Int = 0, icon: String = "folder"): Folder
    suspend fun updateFolder(folder: Folder)
    suspend fun moveFolder(id: String, newParentId: String?)
    /** Moves the folder, its sub-folders and documents to the trash. */
    suspend fun trashFolder(id: String)
    suspend fun restoreFolder(id: String)
    /** "Economics / Term 1" style path for display. */
    suspend fun folderPath(folderId: String?): String

    // ---- Documents
    fun observeDocuments(filter: LibraryFilter): Flow<List<DocumentMeta>>
    fun observeDocument(id: String): Flow<DocumentMeta?>
    suspend fun getDocument(id: String): DocumentMeta?
    /**
     * Creates a document named "YYYYMMDD_Untitled" (unique), or keeps [originalName] for
     * imports. NOTEBOOK gets one page from [template]; WHITEBOARD one infinite page;
     * TYPED and PDF start with no pages (PDF pages are added by the PDF importer).
     */
    suspend fun createDocument(
        kind: DocKind,
        folderId: String? = null,
        template: PageTemplate? = null,
        originalName: String? = null,
    ): DocumentMeta
    /** Manual rename: marks the document MANUAL so auto-naming never overwrites it. */
    suspend fun renameDocument(id: String, newTitle: String)
    /** Applies an inferred topic, keeping the creation-date prefix. Returns the final title. */
    suspend fun applyAutoName(id: String, topic: String): String
    suspend fun updateDocument(doc: DocumentMeta)
    suspend fun moveDocument(id: String, folderId: String?)
    suspend fun duplicateDocument(id: String): DocumentMeta
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun setTags(id: String, tags: List<String>)
    suspend fun trashDocument(id: String)
    suspend fun restoreDocument(id: String)
    /** Deletes the database rows and every file of the document. Irreversible. */
    suspend fun deletePermanently(id: String)
    suspend fun emptyTrash()
    suspend fun updateViewState(id: String, pageIndex: Int, zoom: Float, scrollX: Float, scrollY: Float)
    suspend fun touch(id: String)
    suspend fun setThumbnail(id: String, path: String?)

    // ---- Pages
    fun observePages(docId: String): Flow<List<PageMeta>>
    suspend fun getPages(docId: String): List<PageMeta>
    suspend fun getPage(pageId: String): PageMeta?
    /** Inserts a page at [atIndex] (null = end) using [template] or the previous page's size/paper. */
    suspend fun addPage(docId: String, atIndex: Int? = null, template: PageTemplate? = null): PageMeta
    /** Adds pages that show source-PDF pages (used by the PDF importer). */
    suspend fun addPdfPages(docId: String, pdfPageSizesPt: List<Pair<Float, Float>>, firstPdfIndex: Int = 0, atIndex: Int? = null): List<PageMeta>
    suspend fun deletePage(pageId: String)
    suspend fun duplicatePage(pageId: String): PageMeta
    suspend fun movePage(pageId: String, toIndex: Int)
    suspend fun reorderPages(docId: String, orderedPageIds: List<String>)
    suspend fun copyPageToDocument(pageId: String, targetDocId: String, atIndex: Int? = null): PageMeta
    suspend fun rotatePage(pageId: String, degreesClockwise: Int)
    suspend fun setPaper(pageId: String, paper: PaperSpec)
    suspend fun setPaperForAll(docId: String, paper: PaperSpec)
    suspend fun setPageSize(pageId: String, widthPt: Float, heightPt: Float)
    suspend fun setBookmark(pageId: String, bookmarked: Boolean, title: String? = null)
    /** Marks page content as changed (bumps page + document modifiedAt). */
    suspend fun pageContentChanged(docId: String, pageId: String)

    // ---- Outline
    fun observeOutline(docId: String): Flow<List<OutlineEntry>>
    suspend fun upsertOutlineEntry(entry: OutlineEntry)
    suspend fun deleteOutlineEntry(id: String)

    suspend fun storageInfo(): StorageInfo
}
