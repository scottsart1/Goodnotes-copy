package com.inkwell.notes.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfNamesTest {

    @Test fun stripsThePdfExtension() {
        assertEquals("Lecture 3 - Markets", PdfNames.titleFromDisplayName("Lecture 3 - Markets.pdf"))
        assertEquals("Report", PdfNames.titleFromDisplayName("Report.PDF"))
        assertEquals("notes", PdfNames.titleFromDisplayName("notes.pdf.pdf"))
    }

    @Test fun keepsOtherDotsAndUnderscores() {
        assertEquals("Financial_Markets v2.1", PdfNames.titleFromDisplayName("Financial_Markets v2.1.pdf"))
        assertEquals("20261015_Financial Markets", PdfNames.titleFromDisplayName("20261015_Financial Markets.pdf"))
    }

    @Test fun dropsFoldersAndIllegalCharacters() {
        assertEquals("Q3 Plan", PdfNames.titleFromDisplayName("/storage/emulated/0/Download/Q3 Plan.pdf"))
        assertEquals("a b", PdfNames.titleFromDisplayName("a:b.pdf"))
        assertEquals("Budget 2026", PdfNames.titleFromDisplayName("  Budget\t\n2026 .pdf "))
    }

    @Test fun meaninglessNamesBecomeNull() {
        assertNull(PdfNames.titleFromDisplayName(null))
        assertNull(PdfNames.titleFromDisplayName(""))
        assertNull(PdfNames.titleFromDisplayName(".pdf"))
        assertNull(PdfNames.titleFromDisplayName("document.pdf"))
        assertNull(PdfNames.titleFromDisplayName("Download.pdf"))
        assertNull(PdfNames.titleFromDisplayName("1729081234567.pdf"))
        assertNull(PdfNames.titleFromDisplayName("3f2b8c1e-9a4d-4e7f-b2c1-0a9d8e7f6c5b.pdf"))
    }

    @Test fun shortNumbersAreStillTitles() {
        assertEquals("2026", PdfNames.titleFromDisplayName("2026.pdf"))
        assertEquals("20261015", PdfNames.titleFromDisplayName("20261015.pdf"))
    }

    @Test fun imageNamesLoseTheirExtension() {
        assertEquals("Whiteboard photo", PdfNames.titleFromImageName("Whiteboard photo.HEIC"))
        assertNull(PdfNames.titleFromImageName("1729081234567.jpg"))
    }

    @Test fun longNamesAreCutAtAWordBoundary() {
        val long = (1..60).joinToString(" ") { "word$it" }
        val title = PdfNames.titleFromDisplayName("$long.pdf")!!
        assertTrue(title.length <= PdfNames.MAX_TITLE_CHARS)
        assertTrue(long.startsWith(title))
        assertFalse(title.endsWith(" "))
    }

    @Test fun detectsScannerOutput() {
        assertTrue(PdfNames.isScannerOutput("file:///data/user/0/com.inkwell.notes/cache/mlkit_docscan_ui_client/1729081234567.pdf"))
        assertFalse(PdfNames.isScannerOutput("content://com.android.providers.downloads.documents/document/42"))
    }

    @Test fun pageLabels() {
        assertEquals("page 3", PdfNames.pageLabel(listOf(2)))
        assertEquals("pages 3-5", PdfNames.pageLabel(listOf(2, 3, 4)))
        assertEquals("pages 3-5", PdfNames.pageLabel(listOf(4, 2, 3, 3)))
        assertEquals("pages 1, 3-4", PdfNames.pageLabel(listOf(0, 2, 3)))
        assertEquals("pages 1, 3, 5", PdfNames.pageLabel(listOf(0, 2, 4)))
        assertEquals("4 pages", PdfNames.pageLabel(listOf(0, 2, 4, 6)))
    }

    @Test fun extractTitleUsesSourceTitleAndRange() {
        assertEquals("20261015_Financial Markets (pages 4-7)", PdfNames.extractTitle("20261015_Financial Markets", listOf(3, 4, 5, 6)))
        assertEquals("Paper (page 1)", PdfNames.extractTitle("Paper", listOf(0)))
        val long = "x".repeat(200)
        assertTrue(PdfNames.extractTitle(long, listOf(0, 1)).length <= PdfNames.MAX_TITLE_CHARS)
        assertTrue(PdfNames.extractTitle(long, listOf(0, 1)).endsWith(" (pages 1-2)"))
    }

    @Test fun mergedTitles() {
        assertEquals("A + B", PdfNames.mergedTitle(listOf("A", "B")))
        assertEquals("A + 2 more", PdfNames.mergedTitle(listOf("A", "B", "C")))
        assertEquals("A + 1 more", PdfNames.mergedTitle(listOf("A", "a")))
        assertEquals("A (merged)", PdfNames.mergedTitle(listOf("A")))
        assertEquals("Merged document", PdfNames.mergedTitle(emptyList()))
        val long = PdfNames.mergedTitle(listOf("y".repeat(150), "z".repeat(150)))
        assertTrue(long.length <= PdfNames.MAX_TITLE_CHARS)
        assertTrue(long.endsWith(" + 1 more"))
    }
}
