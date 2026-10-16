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
import com.inkwell.notes.core.settings.SettingsRepository
import com.inkwell.notes.core.storage.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** SCAFFOLD STUB — replaced by the storage implementation. */
class LibraryRepositoryImpl(
    private val db: AppDatabase,
    private val files: FileStore,
    private val pages: PageContentStore,
    private val settings: SettingsRepository,
) : LibraryRepository {
    private fun stub(): Nothing = throw NotImplementedError("LibraryRepositoryImpl")

    override fun observeFolders(): Flow<List<Folder>> = emptyFlow()
    override suspend fun getFolder(id: String): Folder? = null
    override suspend fun createFolder(name: String, parentId: String?, color: Int, icon: String): Folder = stub()
    override suspend fun updateFolder(folder: Folder) = stub()
    override suspend fun moveFolder(id: String, newParentId: String?) = stub()
    override suspend fun trashFolder(id: String) = stub()
    override suspend fun restoreFolder(id: String) = stub()
    override suspend fun folderPath(folderId: String?): String = ""
    override fun observeDocuments(filter: LibraryFilter): Flow<List<DocumentMeta>> = emptyFlow()
    override fun observeDocument(id: String): Flow<DocumentMeta?> = emptyFlow()
    override suspend fun getDocument(id: String): DocumentMeta? = null
    override suspend fun createDocument(kind: DocKind, folderId: String?, template: PageTemplate?, originalName: String?): DocumentMeta = stub()
    override suspend fun renameDocument(id: String, newTitle: String) = stub()
    override suspend fun applyAutoName(id: String, topic: String): String = stub()
    override suspend fun updateDocument(doc: DocumentMeta) = stub()
    override suspend fun moveDocument(id: String, folderId: String?) = stub()
    override suspend fun duplicateDocument(id: String): DocumentMeta = stub()
    override suspend fun setFavorite(id: String, favorite: Boolean) = stub()
    override suspend fun setPinned(id: String, pinned: Boolean) = stub()
    override suspend fun setArchived(id: String, archived: Boolean) = stub()
    override suspend fun setTags(id: String, tags: List<String>) = stub()
    override suspend fun trashDocument(id: String) = stub()
    override suspend fun restoreDocument(id: String) = stub()
    override suspend fun deletePermanently(id: String) = stub()
    override suspend fun emptyTrash() = stub()
    override suspend fun updateViewState(id: String, pageIndex: Int, zoom: Float, scrollX: Float, scrollY: Float) = stub()
    override suspend fun touch(id: String) = stub()
    override suspend fun setThumbnail(id: String, path: String?) = stub()
    override fun observePages(docId: String): Flow<List<PageMeta>> = emptyFlow()
    override suspend fun getPages(docId: String): List<PageMeta> = emptyList()
    override suspend fun getPage(pageId: String): PageMeta? = null
    override suspend fun addPage(docId: String, atIndex: Int?, template: PageTemplate?): PageMeta = stub()
    override suspend fun addPdfPages(docId: String, pdfPageSizesPt: List<Pair<Float, Float>>, firstPdfIndex: Int, atIndex: Int?): List<PageMeta> = stub()
    override suspend fun deletePage(pageId: String) = stub()
    override suspend fun duplicatePage(pageId: String): PageMeta = stub()
    override suspend fun movePage(pageId: String, toIndex: Int) = stub()
    override suspend fun reorderPages(docId: String, orderedPageIds: List<String>) = stub()
    override suspend fun copyPageToDocument(pageId: String, targetDocId: String, atIndex: Int?): PageMeta = stub()
    override suspend fun rotatePage(pageId: String, degreesClockwise: Int) = stub()
    override suspend fun setPaper(pageId: String, paper: PaperSpec) = stub()
    override suspend fun setPaperForAll(docId: String, paper: PaperSpec) = stub()
    override suspend fun setPageSize(pageId: String, widthPt: Float, heightPt: Float) = stub()
    override suspend fun setBookmark(pageId: String, bookmarked: Boolean, title: String?) = stub()
    override suspend fun pageContentChanged(docId: String, pageId: String) = stub()
    override fun observeOutline(docId: String): Flow<List<OutlineEntry>> = emptyFlow()
    override suspend fun upsertOutlineEntry(entry: OutlineEntry) = stub()
    override suspend fun deleteOutlineEntry(id: String) = stub()
    override suspend fun storageInfo(): StorageInfo = StorageInfo(0, 0, 0, 0, 0, files.root.freeSpace)
}
