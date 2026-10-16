package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.LibrarySort
import java.util.Locale

/**
 * The library's filter + sort as a pure function over document metadata, so every view
 * (library, folders, favourites, recent, archive, trash, search-as-you-type) behaves the same
 * and is unit-tested.
 *
 * Rules:
 * - trashed = true shows only trashed documents (archived or not); otherwise trashed ones are hidden.
 * - Outside the trash, archived = true shows only archived documents, false hides them.
 * - folderId != null: inFolderOnly shows that folder's direct documents, otherwise the whole subtree.
 * - folderId == null: inFolderOnly shows the library root (documents in no live folder) unless the
 *   view is a cross-library collection (favourites, recent, kinds, archive, trash or a query),
 *   which spans every folder; inFolderOnly = false always spans every folder.
 * - query: case-insensitive substring of the title or a tag; also matches when every
 *   whitespace-separated term occurs in the title or tags.
 * - recentOnly: opened or modified within [RECENT_WINDOW_MS], newest first, at most [RECENT_LIMIT].
 * - Otherwise pinned documents first (except in the trash), then [LibraryFilter.sort];
 *   names sort naturally and case-insensitively; ties fall back to the title, then the id.
 */
object LibraryQuery {
    const val RECENT_WINDOW_MS: Long = 14L * 24 * 60 * 60 * 1000
    const val RECENT_LIMIT = 50

    fun apply(
        docs: List<DocumentMeta>,
        filter: LibraryFilter,
        folders: Collection<Folder>,
        now: Long,
        locale: Locale = Locale.getDefault(),
    ): List<DocumentMeta> {
        val liveFolderIds = folders.asSequence().filter { it.trashedAt == null }.mapTo(HashSet()) { it.id }
        val scope = folderScope(filter, folders)
        val query = filter.query.trim().lowercase(locale)
        val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val recentSince = now - RECENT_WINDOW_MS

        val matched = docs.filter { d ->
            if (filter.trashed) {
                if (d.trashedAt == null) return@filter false
            } else {
                if (d.trashedAt != null) return@filter false
                if (d.archived != filter.archived) return@filter false
            }
            when (scope) {
                FolderScope.Everywhere -> Unit
                FolderScope.Root -> if (d.folderId != null && d.folderId in liveFolderIds) return@filter false
                is FolderScope.Within -> if (d.folderId == null || d.folderId !in scope.ids) return@filter false
            }
            if (filter.favoritesOnly && !d.favorite) return@filter false
            if (filter.kinds.isNotEmpty() && d.kind !in filter.kinds) return@filter false
            if (filter.recentOnly && recency(d) < recentSince) return@filter false
            if (query.isNotEmpty() && !matchesQuery(d, query, terms, locale)) return@filter false
            true
        }

        return if (filter.recentOnly) {
            matched.sortedWith(compareByDescending<DocumentMeta> { recency(it) }.thenBy { it.id }).take(RECENT_LIMIT)
        } else {
            matched.sortedWith(comparator(filter.sort, pinnedFirst = !filter.trashed, locale = locale))
        }
    }

    fun comparator(sort: LibrarySort, pinnedFirst: Boolean, locale: Locale = Locale.getDefault()): Comparator<DocumentMeta> {
        val natural = NaturalOrder(locale)
        val byTitle = Comparator<DocumentMeta> { a, b -> natural.compare(a.title, b.title) }
        val key: Comparator<DocumentMeta> = when (sort) {
            LibrarySort.NAME_ASC -> byTitle
            LibrarySort.NAME_DESC -> byTitle.reversed()
            LibrarySort.CREATED_DESC -> compareByDescending<DocumentMeta> { it.createdAt }.then(byTitle)
            LibrarySort.CREATED_ASC -> compareBy<DocumentMeta> { it.createdAt }.then(byTitle)
            LibrarySort.MODIFIED_DESC -> compareByDescending<DocumentMeta> { it.modifiedAt }.then(byTitle)
            LibrarySort.MODIFIED_ASC -> compareBy<DocumentMeta> { it.modifiedAt }.then(byTitle)
        }.thenBy { it.id }
        return if (pinnedFirst) compareByDescending<DocumentMeta> { it.pinned }.then(key) else key
    }

    /** True for views that span the whole library rather than one folder level. */
    fun isCollectionView(filter: LibraryFilter): Boolean =
        filter.favoritesOnly || filter.recentOnly || filter.kinds.isNotEmpty() || filter.archived ||
            filter.trashed || filter.query.isNotBlank()

    private fun recency(d: DocumentMeta): Long = maxOf(d.openedAt, d.modifiedAt)

    private fun matchesQuery(d: DocumentMeta, query: String, terms: List<String>, locale: Locale): Boolean {
        val title = d.title.lowercase(locale)
        val tags = d.tags.map { it.lowercase(locale) }
        if (title.contains(query) || tags.any { it.contains(query) }) return true
        if (terms.size < 2) return false
        return terms.all { t -> title.contains(t) || tags.any { it.contains(t) } }
    }

    private sealed interface FolderScope {
        data object Everywhere : FolderScope
        data object Root : FolderScope
        data class Within(val ids: Set<String>) : FolderScope
    }

    private fun folderScope(filter: LibraryFilter, folders: Collection<Folder>): FolderScope {
        val folderId = filter.folderId
        return when {
            folderId != null && filter.inFolderOnly -> FolderScope.Within(setOf(folderId))
            folderId != null -> FolderScope.Within(FolderTree(folders).subtree(folderId))
            filter.inFolderOnly && !isCollectionView(filter) -> FolderScope.Root
            else -> FolderScope.Everywhere
        }
    }
}
