package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryRepositorySmokeTest {
    private lateinit var h: RepositoryTestHarness

    @Before fun setUp() { h = RepositoryTestHarness() }
    @After fun tearDown() { h.close() }

    @Test fun createNotebookHasOnePageAndUntitledName() = runTest {
        val doc = h.repo.createDocument(DocKind.NOTEBOOK)
        assertTrue(doc.title, doc.title.endsWith("_Untitled"))
        assertEquals(AutoNameStatus.PENDING, doc.autoNameStatus)
        assertEquals(1, doc.pageCount)
        assertEquals(1, h.repo.getPages(doc.id).size)
        val second = h.repo.createDocument(DocKind.NOTEBOOK)
        assertTrue(second.title, second.title.endsWith("_Untitled (2)"))
    }
}
