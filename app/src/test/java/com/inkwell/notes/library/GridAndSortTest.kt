package com.inkwell.notes.library

import com.inkwell.notes.core.model.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GridAndSortTest {

    @Test fun railModeBreakpoints() {
        assertEquals(RailMode.FULL, LibraryLayout.railMode(1480f))
        assertEquals(RailMode.FULL, LibraryLayout.railMode(900f))
        assertEquals(RailMode.COMPACT, LibraryLayout.railMode(899f))
        assertEquals(RailMode.COMPACT, LibraryLayout.railMode(740f))
        assertEquals(RailMode.COMPACT, LibraryLayout.railMode(600f))
        assertEquals(RailMode.DRAWER, LibraryLayout.railMode(599f))
        assertEquals(RailMode.DRAWER, LibraryLayout.railMode(360f))
        assertEquals(232f, LibraryLayout.railWidth(RailMode.FULL))
        assertEquals(72f, LibraryLayout.railWidth(RailMode.COMPACT))
        assertEquals(0f, LibraryLayout.railWidth(RailMode.DRAWER))
    }

    @Test fun gridColumnsFitMinimumCardWidth() {
        // Tab S11 Ultra landscape: 1480dp - 232dp rail - 1dp divider - 2 x 16dp padding.
        assertEquals(5, LibraryLayout.gridColumns(1215f, 200f, 8f))
        assertEquals(1, LibraryLayout.gridColumns(207f, 200f, 8f))
        assertEquals(2, LibraryLayout.gridColumns(408f, 200f, 8f))
        assertEquals(1, LibraryLayout.gridColumns(407f, 200f, 8f))
        assertEquals(2, LibraryLayout.gridColumns(328f, LibraryLayout.minCardWidth(360f), 8f))
    }

    @Test fun gridColumnsNeverDropBelowOne() {
        assertEquals(1, LibraryLayout.gridColumns(0f, 200f, 8f))
        assertEquals(1, LibraryLayout.gridColumns(-10f, 200f, 8f))
        assertEquals(1, LibraryLayout.gridColumns(100f, 200f, 8f))
        assertEquals(1, LibraryLayout.gridColumns(500f, 0f, 8f))
    }

    @Test fun topBarRowsAndPadding() {
        assertTrue(LibraryLayout.singleRowTopBar(1247f))
        assertTrue(LibraryLayout.singleRowTopBar(720f))
        assertFalse(LibraryLayout.singleRowTopBar(668f))
        assertEquals(24f, LibraryLayout.contentPadding(1480f))
        assertEquals(16f, LibraryLayout.contentPadding(360f))
        assertEquals(200f, LibraryLayout.minCardWidth(740f))
        assertEquals(148f, LibraryLayout.minCardWidth(400f))
    }

    @Test fun naturalOrderComparesNumbersByValue() {
        val n = NaturalOrder(Locale.US)
        val sorted = listOf("Term 10", "term 2", "Term 1", "Term 02b", "Appendix").sortedWith(n)
        assertEquals(listOf("Appendix", "Term 1", "term 2", "Term 02b", "Term 10"), sorted)
        assertTrue(n.compare("20261015_Markets", "20261016_Markets") < 0)
        assertTrue(n.compare("Lecture 9", "Lecture 10") < 0)
        assertTrue(n.compare("a", "a1") < 0)
        assertEquals(0, n.compare("Same", "Same"))
    }

    @Test fun naturalOrderIsATotalOrderForEquivalentNames() {
        val n = NaturalOrder(Locale.US)
        assertTrue(n.compare("A1", "a1") != 0)
        assertEquals(-n.compare("A1", "a1"), n.compare("a1", "A1"))
        assertTrue(n.compare("x01", "x1") != 0)
    }

    @Test fun folderSortFollowsTheLibrarySort() {
        val f = listOf(
            folder("1", name = "Beta", created = 3, modified = 1),
            folder("2", name = "alpha", created = 1, modified = 3),
            folder("3", name = "Gamma", created = 2, modified = 2),
        )
        fun order(s: LibrarySort) = f.sortedWith(LibrarySorting.folderComparator(s, Locale.US)).map { it.name }
        assertEquals(listOf("alpha", "Beta", "Gamma"), order(LibrarySort.NAME_ASC))
        assertEquals(listOf("Gamma", "Beta", "alpha"), order(LibrarySort.NAME_DESC))
        assertEquals(listOf("Beta", "Gamma", "alpha"), order(LibrarySort.CREATED_DESC))
        assertEquals(listOf("alpha", "Gamma", "Beta"), order(LibrarySort.CREATED_ASC))
        assertEquals(listOf("alpha", "Gamma", "Beta"), order(LibrarySort.MODIFIED_DESC))
        assertEquals(listOf("Beta", "Gamma", "alpha"), order(LibrarySort.MODIFIED_ASC))
    }

    @Test fun explicitFolderOrderWinsOverTheSort() {
        val f = listOf(folder("1", name = "Zeta").copy(sortOrder = -1), folder("2", name = "Alpha"))
        assertEquals(listOf("Zeta", "Alpha"), f.sortedWith(LibrarySorting.folderComparator(LibrarySort.NAME_ASC, Locale.US)).map { it.name })
    }

    @Test fun sortMenuListsEveryOptionOnceWithDirections() {
        assertEquals(LibrarySort.entries.toSet(), LibrarySorting.menuOrder.toSet())
        assertEquals(LibrarySort.entries.size, LibrarySorting.menuOrder.size)
        assertTrue(LibrarySorting.isAscending(LibrarySort.NAME_ASC))
        assertFalse(LibrarySorting.isAscending(LibrarySort.MODIFIED_DESC))
        assertEquals(LibrarySorting.SortKey.CREATED, LibrarySorting.keyOf(LibrarySort.CREATED_ASC))
    }
}
