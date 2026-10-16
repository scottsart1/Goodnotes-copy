package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LibraryQueryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private val economics = Folder("econ", "Economics", null, createdAt = 0, modifiedAt = 0)
    private val term1 = Folder("term1", "Term 1", "econ", createdAt = 0, modifiedAt = 0)
    private val old = Folder("old", "Old stuff", null, createdAt = 0, modifiedAt = 0, trashedAt = now - day)
    private val folders = listOf(economics, term1, old)

    private fun doc(
        id: String,
        title: String = id,
        folderId: String? = null,
        kind: DocKind = DocKind.NOTEBOOK,
        created: Long = now - 10 * day,
        modified: Long = now - 5 * day,
        opened: Long = 0,
        favorite: Boolean = false,
        pinned: Boolean = false,
        archived: Boolean = false,
        trashedAt: Long? = null,
        tags: List<String> = emptyList(),
    ) = DocumentMeta(
        id = id, title = title, kind = kind, folderId = folderId, createdAt = created, modifiedAt = modified,
        openedAt = opened, favorite = favorite, pinned = pinned, archived = archived, trashedAt = trashedAt, tags = tags,
    )

    private fun query(docs: List<DocumentMeta>, filter: LibraryFilter) =
        LibraryQuery.apply(docs, filter, folders, now, Locale.ENGLISH).map { it.id }

    @Test fun rootShowsOnlyTopLevelDocuments() {
        val docs = listOf(doc("a"), doc("b", folderId = "econ"), doc("c", folderId = "term1"))
        assertEquals(listOf("a"), query(docs, LibraryFilter()))
    }

    @Test fun documentsInMissingOrTrashedFoldersShowAtTheRoot() {
        val docs = listOf(doc("orphan", folderId = "deleted-folder"), doc("inTrashedFolder", folderId = "old"))
        assertEquals(setOf("orphan", "inTrashedFolder"), query(docs, LibraryFilter()).toSet())
    }

    @Test fun folderDirectChildrenOrWholeSubtree() {
        val docs = listOf(doc("a"), doc("b", folderId = "econ"), doc("c", folderId = "term1"))
        assertEquals(listOf("b"), query(docs, LibraryFilter(folderId = "econ")))
        assertEquals(setOf("b", "c"), query(docs, LibraryFilter(folderId = "econ", inFolderOnly = false)).toSet())
        assertEquals(setOf("a", "b", "c"), query(docs, LibraryFilter(inFolderOnly = false)).toSet())
    }

    @Test fun collectionsSpanEveryFolder() {
        val docs = listOf(
            doc("fav", folderId = "term1", favorite = true),
            doc("plain", folderId = "econ"),
            doc("board", kind = DocKind.WHITEBOARD, folderId = "econ"),
        )
        assertEquals(listOf("fav"), query(docs, LibraryFilter(favoritesOnly = true)))
        assertEquals(listOf("board"), query(docs, LibraryFilter(kinds = setOf(DocKind.WHITEBOARD))))
        assertEquals(listOf("plain"), query(docs, LibraryFilter(query = "PLA")))
    }

    @Test fun archivedAndTrashedAreSeparateViews() {
        val docs = listOf(
            doc("live"),
            doc("archived", archived = true),
            doc("trashed", trashedAt = now - day),
            doc("archivedThenTrashed", archived = true, trashedAt = now - day),
        )
        assertEquals(listOf("live"), query(docs, LibraryFilter()))
        assertEquals(listOf("archived"), query(docs, LibraryFilter(archived = true)))
        assertEquals(setOf("trashed", "archivedThenTrashed"), query(docs, LibraryFilter(trashed = true)).toSet())
    }

    @Test fun queryMatchesTitleOrTagsCaseInsensitively() {
        val docs = listOf(
            doc("1", title = "20261015_Financial Markets"),
            doc("2", title = "Lab report", tags = listOf("Chemistry")),
            doc("3", title = "Groceries"),
        )
        assertEquals(listOf("1"), query(docs, LibraryFilter(query = "financial mar")))
        assertEquals(listOf("2"), query(docs, LibraryFilter(query = "chem")))
        // Every term matching somewhere also counts.
        assertEquals(listOf("1"), query(docs, LibraryFilter(query = "markets 20261015")))
        assertEquals(emptyList<String>(), query(docs, LibraryFilter(query = "physics")))
    }

    @Test fun recentIsOpenedOrModifiedWithinTwoWeeksByRecency() {
        val docs = listOf(
            doc("openedYesterday", opened = now - day, modified = now - 40 * day),
            doc("modifiedToday", modified = now - 1000),
            doc("stale", modified = now - 15 * day, opened = now - 20 * day),
            doc("pinnedButOld", pinned = true, modified = now - 30 * day),
        )
        assertEquals(listOf("modifiedToday", "openedYesterday"), query(docs, LibraryFilter(recentOnly = true)))
    }

    @Test fun recentIsCappedAtFifty() {
        val docs = (1..80).map { doc("d$it", modified = now - it * 1000L) }
        val result = query(docs, LibraryFilter(recentOnly = true))
        assertEquals(LibraryQuery.RECENT_LIMIT, result.size)
        assertEquals("d1", result.first())
    }

    @Test fun pinnedFirstThenSortKey() {
        val docs = listOf(
            doc("old", modified = now - 9 * day),
            doc("new", modified = now - 1 * day),
            doc("pinnedOld", modified = now - 20 * day, pinned = true),
        )
        assertEquals(listOf("pinnedOld", "new", "old"), query(docs, LibraryFilter(sort = LibrarySort.MODIFIED_DESC)))
        assertEquals(listOf("pinnedOld", "old", "new"), query(docs, LibraryFilter(sort = LibrarySort.MODIFIED_ASC)))
    }

    @Test fun trashIgnoresPins() {
        val docs = listOf(
            doc("a", modified = now - 1 * day, trashedAt = now),
            doc("b", modified = now - 9 * day, trashedAt = now, pinned = true),
        )
        assertEquals(listOf("a", "b"), query(docs, LibraryFilter(trashed = true, sort = LibrarySort.MODIFIED_DESC)))
    }

    @Test fun namesSortNaturallyAndIgnoreCase() {
        val docs = listOf(
            doc("10", title = "Lecture 10"),
            doc("2", title = "lecture 2"),
            doc("1", title = "Lecture 1"),
            doc("b", title = "beta"),
            doc("a", title = "Alpha"),
        )
        assertEquals(listOf("a", "b", "1", "2", "10"), query(docs, LibraryFilter(sort = LibrarySort.NAME_ASC)))
        assertEquals(listOf("10", "2", "1", "b", "a"), query(docs, LibraryFilter(sort = LibrarySort.NAME_DESC)))
    }

    @Test fun createdSortsWithTitleTieBreak() {
        val docs = listOf(
            doc("x", title = "B", created = now - 2 * day),
            doc("y", title = "A", created = now - 2 * day),
            doc("z", title = "C", created = now - 1 * day),
        )
        assertEquals(listOf("z", "y", "x"), query(docs, LibraryFilter(sort = LibrarySort.CREATED_DESC)))
        assertEquals(listOf("y", "x", "z"), query(docs, LibraryFilter(sort = LibrarySort.CREATED_ASC)))
    }
}
