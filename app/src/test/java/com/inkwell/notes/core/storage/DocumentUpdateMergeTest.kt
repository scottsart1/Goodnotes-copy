package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import org.junit.Assert.assertEquals
import org.junit.Test

/** updateDocument with a copy another screen read before the document changed. */
class DocumentUpdateMergeTest {
    private val base = DocumentMeta(
        id = "d", title = "20261015_Untitled", kind = DocKind.NOTEBOOK, createdAt = 1_000, modifiedAt = 2_000,
        autoNameStatus = AutoNameStatus.PENDING, pageCount = 3,
    )

    @Test fun staleCopyCannotUndoAnAutomaticName() {
        val current = base.copy(title = "20261015_Monetary Policy", autoNameStatus = AutoNameStatus.DONE, topic = "Monetary Policy")
        val merged = LibraryRepositoryImpl.mergeUpdate(current, base.copy(coverColor = 7), pageCount = 3)
        assertEquals("20261015_Monetary Policy", merged.title)
        assertEquals(AutoNameStatus.DONE, merged.autoNameStatus)
        assertEquals("Monetary Policy", merged.topic)
        assertEquals(7, merged.coverColor)
    }

    @Test fun staleCopyCannotUndoAManualName() {
        val current = base.copy(title = "Physics revision", autoNameStatus = AutoNameStatus.MANUAL)
        val merged = LibraryRepositoryImpl.mergeUpdate(current, base, pageCount = 3)
        assertEquals("Physics revision", merged.title)
        assertEquals(AutoNameStatus.MANUAL, merged.autoNameStatus)
    }

    @Test fun deliberateRenamesStillApply() {
        val current = base.copy(title = "20261015_Monetary Policy", autoNameStatus = AutoNameStatus.DONE)
        val merged = LibraryRepositoryImpl.mergeUpdate(current, current.copy(title = "Macro", autoNameStatus = AutoNameStatus.MANUAL), 3)
        assertEquals("Macro", merged.title)
        assertEquals(AutoNameStatus.MANUAL, merged.autoNameStatus)
    }

    @Test fun pageCountTrashAndNewerEditsAreKept() {
        val current = base.copy(modifiedAt = 9_000, trashedAt = 8_000)
        val merged = LibraryRepositoryImpl.mergeUpdate(current, base.copy(lastSyncedAt = 9_500, pageCount = 1), pageCount = 5)
        assertEquals(5, merged.pageCount)
        assertEquals(8_000L, merged.trashedAt)
        assertEquals(9_000L, merged.modifiedAt)
        assertEquals(9_500L, merged.lastSyncedAt)
    }
}
