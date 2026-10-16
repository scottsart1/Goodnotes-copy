package com.inkwell.notes.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashModelTest {

    @Test fun folderTrashedWithItsContentsIsOneEntry() {
        val folders = listOf(
            folder("a").copy(trashedAt = 100),
            folder("b", "a").copy(trashedAt = 100),
        )
        val docs = listOf(doc("d1", "a", trashedAt = 100), doc("d2", "b", trashedAt = 100))
        val entries = TrashModel.build(docs, folders)
        assertEquals(1, entries.size)
        val g = entries.single() as TrashEntry.FolderGroup
        assertEquals("a", g.folder.id)
        assertEquals(listOf("a", "b"), g.folderIds)
        assertEquals(listOf("d1", "d2"), g.documentIds)
        assertEquals(3, g.itemCount)
        assertEquals(100L, g.trashedAt)
    }

    @Test fun separatelyTrashedItemsStayTheirOwnEntries() {
        val folders = listOf(
            folder("a").copy(trashedAt = 200),
            folder("b", "a").copy(trashedAt = 150), // trashed earlier, on its own
            folder("live"),
        )
        val docs = listOf(
            doc("d1", "a", trashedAt = 200),
            doc("d2", "a", trashedAt = 50), // deleted before the folder
            doc("d3", "b", trashedAt = 150),
            doc("d4", "live", trashedAt = 120),
            doc("d5", null, trashedAt = 300),
        )
        val entries = TrashModel.build(docs, folders)
        assertEquals(listOf("d:d5", "f:a", "f:b", "d:d4", "d:d2"), entries.map { it.key })
        val a = entries.first { it.key == "f:a" } as TrashEntry.FolderGroup
        assertEquals(listOf("a"), a.folderIds)
        assertEquals(listOf("d1"), a.documentIds)
        val b = entries.first { it.key == "f:b" } as TrashEntry.FolderGroup
        assertEquals(listOf("d3"), b.documentIds)
    }

    @Test fun documentInADeletedFolderIsItsOwnEntry() {
        val entries = TrashModel.build(listOf(doc("d1", "gone", trashedAt = 10)), emptyList())
        assertEquals(listOf("d:d1"), entries.map { it.key })
    }

    @Test fun liveDocumentsAreIgnored() {
        val entries = TrashModel.build(listOf(doc("d1", "a")), listOf(folder("a").copy(trashedAt = 5)))
        val g = entries.single() as TrashEntry.FolderGroup
        assertTrue(g.documentIds.isEmpty())
        assertEquals(0, g.itemCount)
    }

    @Test fun parentCyclesTerminate() {
        val folders = listOf(folder("a", "b").copy(trashedAt = 7), folder("b", "a").copy(trashedAt = 7))
        val entries = TrashModel.build(listOf(doc("d", "a", trashedAt = 7)), folders)
        assertEquals(1, entries.size)
        val g = entries.single() as TrashEntry.FolderGroup
        assertEquals(setOf("a", "b"), g.folderIds.toSet())
        assertEquals(g.folder.id, g.folderIds.first())
        assertEquals(listOf("d"), g.documentIds)
    }

    @Test fun itemCountForEmptyTrashConfirmation() {
        val folders = listOf(folder("a").copy(trashedAt = 9), folder("b", "a").copy(trashedAt = 9))
        val docs = listOf(doc("d1", "b", trashedAt = 9), doc("d2", null, trashedAt = 3))
        assertEquals(4, TrashModel.itemCount(TrashModel.build(docs, folders)))
    }
}
