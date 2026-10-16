package com.inkwell.notes.library

import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class LibraryTextTest {
    private val zone = ZoneId.of("UTC")
    private val now = 1_792_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun metaLineForANotebook() {
        val d = doc("1").copy(pageCount = 12, modifiedAt = now - 2 * hour)
        assertEquals("Notebook · 12 pages · Edited 2h ago", LibraryText.metaLine(d, now, zone, Locale.US))
    }

    @Test fun metaLineSingularPageAndPdf() {
        val d = doc("1", kind = DocKind.PDF).copy(pageCount = 1, modifiedAt = now)
        assertEquals("PDF · 1 page · Edited just now", LibraryText.metaLine(d, now, zone, Locale.US))
    }

    @Test fun whiteboardsAndTypedDocumentsHaveNoPageCount() {
        assertNull(LibraryText.pagesLabel(DocKind.WHITEBOARD, 1))
        assertNull(LibraryText.pagesLabel(DocKind.TYPED, 0))
        val w = doc("1", kind = DocKind.WHITEBOARD).copy(pageCount = 1, modifiedAt = now - 30 * 60_000)
        assertEquals("Whiteboard · Edited 30 min ago", LibraryText.metaLine(w, now, zone, Locale.US))
        val t = doc("1", kind = DocKind.TYPED).copy(modifiedAt = now - day - hour * 8)
        assertTrue(LibraryText.metaLine(t, now, zone, Locale.US).startsWith("Typed document · Edited "))
    }

    @Test fun autoNameChipOnlyWhileWaitingAndUntitled() {
        val pending = doc("1", title = "20261015_Untitled").copy(autoNameStatus = AutoNameStatus.PENDING)
        assertTrue(LibraryText.showsAutoNameChip(pending, autoNamingEnabled = true))
        assertTrue(LibraryText.showsAutoNameChip(pending.copy(title = "20261015_Untitled (2)"), true))
        assertFalse(LibraryText.showsAutoNameChip(pending, autoNamingEnabled = false))
        assertFalse(LibraryText.showsAutoNameChip(pending.copy(title = "20261015_Financial Markets"), true))
        assertFalse(LibraryText.showsAutoNameChip(pending.copy(autoNameStatus = AutoNameStatus.QUEUED), true))
        assertFalse(LibraryText.showsAutoNameChip(pending.copy(autoNameStatus = AutoNameStatus.MANUAL), true))
    }

    @Test fun formatBytes() {
        assertEquals("0 B", LibraryText.formatBytes(0))
        assertEquals("0 B", LibraryText.formatBytes(-5))
        assertEquals("512 B", LibraryText.formatBytes(512))
        assertEquals("1 KB", LibraryText.formatBytes(1024))
        assertEquals("740 KB", LibraryText.formatBytes(740L * 1024 + 300))
        assertEquals("1.0 MB", LibraryText.formatBytes(1024L * 1024, Locale.US))
        assertEquals("12.4 MB", LibraryText.formatBytes((12.4 * 1024 * 1024).toLong(), Locale.US))
        assertEquals("1.5 GB", LibraryText.formatBytes(1536L * 1024 * 1024, Locale.US))
    }

    @Test fun storageSummary() {
        assertEquals("1 document · 1.0 MB on this tablet", LibraryText.storageSummary(1, 1024L * 1024, Locale.US))
        assertEquals("42 documents · 0 B on this tablet", LibraryText.storageSummary(42, 0, Locale.US))
    }

    @Test fun trashDaysLeft() {
        assertEquals(30, LibraryText.trashDaysLeft(now, now))
        assertEquals(30, LibraryText.trashDaysLeft(now - hour, now))
        assertEquals(29, LibraryText.trashDaysLeft(now - day, now))
        assertEquals(1, LibraryText.trashDaysLeft(now - 29 * day - hour, now))
        assertEquals(0, LibraryText.trashDaysLeft(now - 30 * day, now))
        assertEquals(0, LibraryText.trashDaysLeft(now - 45 * day, now))
        assertEquals(30, LibraryText.trashDaysLeft(now + day, now))
        assertEquals("Deletes today", LibraryText.trashDaysLeftLabel(now - 31 * day, now))
        assertEquals("1 day left", LibraryText.trashDaysLeftLabel(now - 29 * day - hour, now))
        assertEquals("Deleted 3 days ago · 27 days left", LibraryText.trashLine(now - 3 * day, now, zone, Locale.US))
    }

    @Test fun counts() {
        assertEquals("1 item", LibraryText.itemCount(1))
        assertEquals("0 items", LibraryText.itemCount(0))
        assertEquals("Folder · 3 items", LibraryText.folderLine(3))
        assertEquals("2 documents", LibraryText.documentCount(2))
    }
}
