package com.inkwell.notes.library

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.LibrarySort

/** Cross-library collections listed in the rail. */
enum class LibraryCollection { RECENT, FAVORITES, WHITEBOARDS, DOCUMENTS, PDFS, ARCHIVE }

/** What the library pane is showing. */
sealed interface LibraryLocation {
    /** The top level: root folders and documents that are in no folder. */
    data object Root : LibraryLocation

    data class InFolder(val folderId: String) : LibraryLocation

    data class Collection(val collection: LibraryCollection) : LibraryLocation
}

/**
 * Maps a [LibraryLocation] (+ live search text and sort) to the repository's [LibraryFilter],
 * and answers the small layout questions that depend on it. Pure, unit-tested.
 */
object LibraryFilters {

    fun filterFor(location: LibraryLocation, query: String, sort: LibrarySort): LibraryFilter {
        val q = query.trim()
        return when (location) {
            LibraryLocation.Root -> LibraryFilter(folderId = null, inFolderOnly = true, query = q, sort = sort)
            // While searching inside a folder, search its whole subtree.
            is LibraryLocation.InFolder ->
                LibraryFilter(folderId = location.folderId, inFolderOnly = q.isEmpty(), query = q, sort = sort)
            is LibraryLocation.Collection -> when (location.collection) {
                LibraryCollection.RECENT -> LibraryFilter(inFolderOnly = false, recentOnly = true, query = q, sort = sort)
                LibraryCollection.FAVORITES -> LibraryFilter(inFolderOnly = false, favoritesOnly = true, query = q, sort = sort)
                LibraryCollection.WHITEBOARDS -> LibraryFilter(inFolderOnly = false, kinds = setOf(DocKind.WHITEBOARD), query = q, sort = sort)
                LibraryCollection.DOCUMENTS -> LibraryFilter(inFolderOnly = false, kinds = setOf(DocKind.TYPED), query = q, sort = sort)
                LibraryCollection.PDFS -> LibraryFilter(inFolderOnly = false, kinds = setOf(DocKind.PDF), query = q, sort = sort)
                LibraryCollection.ARCHIVE -> LibraryFilter(inFolderOnly = false, archived = true, query = q, sort = sort)
            }
        }
    }

    /** Folder cards appear while browsing the hierarchy, not in collections. */
    fun showsFolders(location: LibraryLocation): Boolean =
        location is LibraryLocation.Root || location is LibraryLocation.InFolder

    /** The pinned row heads the top level when not searching. */
    fun showsPinnedRow(location: LibraryLocation, query: String): Boolean =
        location is LibraryLocation.Root && query.isBlank()

    /** Recent has its own order (most recently used first). */
    fun allowsSort(location: LibraryLocation): Boolean =
        !(location is LibraryLocation.Collection && location.collection == LibraryCollection.RECENT)

    /** Where "New" puts documents created from this view. */
    fun creationFolder(location: LibraryLocation): String? = (location as? LibraryLocation.InFolder)?.folderId

    /** Kind created by the empty-state action of a collection, if it has one. */
    fun emptyStateKind(location: LibraryLocation): DocKind? = when (location) {
        LibraryLocation.Root, is LibraryLocation.InFolder -> DocKind.NOTEBOOK
        is LibraryLocation.Collection -> when (location.collection) {
            LibraryCollection.WHITEBOARDS -> DocKind.WHITEBOARD
            LibraryCollection.DOCUMENTS -> DocKind.TYPED
            else -> null
        }
    }
}
