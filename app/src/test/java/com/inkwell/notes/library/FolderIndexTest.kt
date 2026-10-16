package com.inkwell.notes.library

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

internal fun folder(id: String, parent: String? = null, name: String = id, created: Long = 0, modified: Long = 0) =
    Folder(id = id, name = name, parentId = parent, createdAt = created, modifiedAt = modified)

internal fun doc(
    id: String,
    folderId: String? = null,
    title: String = id,
    kind: DocKind = DocKind.NOTEBOOK,
    trashedAt: Long? = null,
    modified: Long = 0,
) = DocumentMeta(id = id, title = title, kind = kind, folderId = folderId, createdAt = 0, modifiedAt = modified, trashedAt = trashedAt)

class FolderIndexTest {
    private val comparator = LibrarySorting.folderComparator(LibrarySort.NAME_ASC, Locale.US)

    // Economics
    //   Term 10
    //   Term 2
    //     Week 1
    // Biology
    private val folders = listOf(
        folder("eco", name = "Economics"),
        folder("t10", "eco", "Term 10"),
        folder("t2", "eco", "Term 2"),
        folder("w1", "t2", "Week 1"),
        folder("bio", name = "Biology"),
    )
    private val index = FolderIndex(folders, comparator)

    @Test fun rootsAndChildrenAreSortedNaturally() {
        assertEquals(listOf("bio", "eco"), index.roots.map { it.id })
        assertEquals(listOf("t2", "t10"), index.childrenOf("eco").map { it.id })
        assertTrue(index.childrenOf("bio").isEmpty())
    }

    @Test fun visibleRowsFollowExpansion() {
        val collapsed = index.visibleRows(emptySet())
        assertEquals(listOf("bio", "eco"), collapsed.map { it.folder.id })
        assertTrue(collapsed.single { it.folder.id == "eco" }.hasChildren)
        assertFalse(collapsed.single { it.folder.id == "eco" }.expanded)

        val open = index.visibleRows(setOf("eco"))
        assertEquals(listOf("bio", "eco", "t2", "t10"), open.map { it.folder.id })
        assertEquals(listOf(0, 0, 1, 1), open.map { it.depth })

        val deep = index.visibleRows(setOf("eco", "t2"))
        assertEquals(listOf("bio", "eco", "t2", "w1", "t10"), deep.map { it.folder.id })
        assertEquals(2, deep.single { it.folder.id == "w1" }.depth)
    }

    @Test fun expandingAFolderInsideACollapsedOneShowsNothingExtra() {
        val rows = index.visibleRows(setOf("t2"))
        assertEquals(listOf("bio", "eco"), rows.map { it.folder.id })
    }

    @Test fun allRowsIsFullyExpandedInDepthFirstOrder() {
        assertEquals(listOf("bio", "eco", "t2", "w1", "t10"), index.allRows().map { it.folder.id })
    }

    @Test fun breadcrumbRunsFromRootToSelf() {
        assertEquals(listOf("eco", "t2", "w1"), index.breadcrumb("w1").map { it.id })
        assertEquals(listOf("bio"), index.breadcrumb("bio").map { it.id })
        assertTrue(index.breadcrumb(null).isEmpty())
        assertTrue(index.breadcrumb("missing").isEmpty())
        assertEquals(setOf("eco", "t2"), index.ancestorsOf("w1"))
    }

    @Test fun subtreeAndParent() {
        assertEquals(setOf("eco", "t10", "t2", "w1"), index.subtree("eco"))
        assertEquals(setOf("w1"), index.subtree("w1"))
        assertTrue(index.subtree("missing").isEmpty())
        assertEquals("t2", index.parentOf("w1")?.id)
        assertNull(index.parentOf("eco"))
        assertTrue(index.isWithin("w1", "eco"))
        assertFalse(index.isWithin("eco", "w1"))
    }

    @Test fun missingParentMakesARoot() {
        val idx = FolderIndex(listOf(folder("a", parent = "gone"), folder("b")), comparator)
        assertEquals(listOf("a", "b"), idx.roots.map { it.id })
        assertNull(idx.parentOf("a"))
    }

    @Test fun selfParentIsARoot() {
        val idx = FolderIndex(listOf(folder("a", parent = "a")), comparator)
        assertEquals(listOf("a"), idx.allRows().map { it.folder.id })
        assertEquals(listOf("a"), idx.breadcrumb("a").map { it.id })
    }

    @Test fun parentCyclesStayVisibleAndTerminate() {
        val idx = FolderIndex(listOf(folder("a", "b"), folder("b", "a"), folder("c", "b"), folder("z")), comparator)
        val rows = idx.allRows().map { it.folder.id }
        assertEquals(setOf("a", "b", "c", "z"), rows.toSet())
        assertEquals(4, rows.size)
        // "a" sorts first, so it is the one detached from the cycle.
        assertNull(idx.parentOf("a"))
        assertEquals(listOf("a", "b", "c"), idx.breadcrumb("c").map { it.id })
        assertEquals(setOf("a", "b", "c"), idx.subtree("a"))
    }

    @Test fun itemCountsAddDocumentsAndSubFolders() {
        val docs = listOf(
            doc("d1", "eco"),
            doc("d2", "eco"),
            doc("d3", "w1"),
            doc("d4", null),
            doc("d5", "gone"),
            doc("d6", "eco", trashedAt = 5),
        )
        val counts = FolderIndex.itemCounts(index, docs)
        assertEquals(4, counts["eco"]) // 2 docs + 2 sub-folders
        assertEquals(1, counts["t2"]) // Week 1
        assertEquals(1, counts["w1"])
        assertNull(counts["bio"])
        assertNull(counts["gone"])
    }

    @Test fun sortByModifiedOrdersChildren() {
        val f = listOf(folder("x", modified = 10), folder("y", modified = 30), folder("z", modified = 20))
        val idx = FolderIndex(f, LibrarySorting.folderComparator(LibrarySort.MODIFIED_DESC, Locale.US))
        assertEquals(listOf("y", "z", "x"), idx.roots.map { it.id })
    }
}
