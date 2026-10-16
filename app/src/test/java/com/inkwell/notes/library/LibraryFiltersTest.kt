package com.inkwell.notes.library

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFiltersTest {
    private val sort = LibrarySort.NAME_ASC

    @Test fun rootShowsTheTopLevel() {
        assertEquals(LibraryFilter(folderId = null, inFolderOnly = true, sort = sort), LibraryFilters.filterFor(LibraryLocation.Root, "", sort))
    }

    @Test fun folderShowsItsDirectItemsAndSearchesItsSubtree() {
        val browse = LibraryFilters.filterFor(LibraryLocation.InFolder("f"), "", sort)
        assertEquals("f", browse.folderId)
        assertTrue(browse.inFolderOnly)
        val search = LibraryFilters.filterFor(LibraryLocation.InFolder("f"), "  markets ", sort)
        assertEquals("f", search.folderId)
        assertFalse(search.inFolderOnly)
        assertEquals("markets", search.query)
    }

    @Test fun collectionsSpanTheLibrary() {
        fun f(c: LibraryCollection) = LibraryFilters.filterFor(LibraryLocation.Collection(c), "", sort)
        assertTrue(f(LibraryCollection.RECENT).recentOnly)
        assertTrue(f(LibraryCollection.FAVORITES).favoritesOnly)
        assertEquals(setOf(DocKind.WHITEBOARD), f(LibraryCollection.WHITEBOARDS).kinds)
        assertEquals(setOf(DocKind.TYPED), f(LibraryCollection.DOCUMENTS).kinds)
        assertEquals(setOf(DocKind.PDF), f(LibraryCollection.PDFS).kinds)
        assertTrue(f(LibraryCollection.ARCHIVE).archived)
        for (c in LibraryCollection.entries) {
            assertFalse(c.name, f(c).inFolderOnly)
            assertFalse(c.name, f(c).trashed)
            assertNull(c.name, f(c).folderId)
        }
        assertFalse(f(LibraryCollection.FAVORITES).archived)
    }

    @Test fun layoutQuestions() {
        assertTrue(LibraryFilters.showsFolders(LibraryLocation.Root))
        assertTrue(LibraryFilters.showsFolders(LibraryLocation.InFolder("f")))
        assertFalse(LibraryFilters.showsFolders(LibraryLocation.Collection(LibraryCollection.FAVORITES)))
        assertTrue(LibraryFilters.showsPinnedRow(LibraryLocation.Root, ""))
        assertFalse(LibraryFilters.showsPinnedRow(LibraryLocation.Root, "x"))
        assertFalse(LibraryFilters.showsPinnedRow(LibraryLocation.InFolder("f"), ""))
        assertFalse(LibraryFilters.allowsSort(LibraryLocation.Collection(LibraryCollection.RECENT)))
        assertTrue(LibraryFilters.allowsSort(LibraryLocation.Collection(LibraryCollection.ARCHIVE)))
        assertEquals("f", LibraryFilters.creationFolder(LibraryLocation.InFolder("f")))
        assertNull(LibraryFilters.creationFolder(LibraryLocation.Collection(LibraryCollection.PDFS)))
        assertEquals(DocKind.WHITEBOARD, LibraryFilters.emptyStateKind(LibraryLocation.Collection(LibraryCollection.WHITEBOARDS)))
        assertNull(LibraryFilters.emptyStateKind(LibraryLocation.Collection(LibraryCollection.ARCHIVE)))
    }

    @Test fun locationOfPrefersTheCollection() {
        assertEquals(LibraryLocation.Root, LibraryViewModel.locationOf(null, null))
        assertEquals(LibraryLocation.InFolder("f"), LibraryViewModel.locationOf("f", null))
        assertEquals(LibraryLocation.Collection(LibraryCollection.RECENT), LibraryViewModel.locationOf("f", LibraryCollection.RECENT))
    }
}
