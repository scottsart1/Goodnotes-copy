package com.inkwell.notes.pdf

import com.inkwell.notes.core.naming.TitleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage: an imported PDF's title passes through [PdfNames.titleFromDisplayName] and then,
 * inside the library, through [TitleRules.fromImportedName]. The two steps must agree so an
 * imported name is preserved exactly (product rule: imported names are kept).
 */
class PdfTitleChainTest {
    private fun imported(displayName: String?): String? =
        PdfNames.titleFromDisplayName(displayName)?.let { TitleRules.fromImportedName(it) }

    @Test
    fun ordinaryFileNamesSurviveBothSteps() {
        assertEquals("Lecture 3 - Markets", imported("Lecture 3 - Markets.pdf"))
        assertEquals("v1.2 release notes", imported("v1.2 release notes.PDF"))
        assertEquals("2024.10.16 minutes", imported("/storage/emulated/0/Download/2024.10.16 minutes.pdf"))
        assertEquals("Économie – Chapitre 1", imported("Économie – Chapitre 1.pdf"))
    }

    @Test
    fun meaninglessAndScannerNamesFallBackToTheDateName() {
        assertNull(imported("document.pdf"))
        assertNull(imported("1729081234567.pdf"))
        assertNull(imported("3f2504e0-4f89-11d3-9a0c-0305e82c3301.pdf"))
        assertNull(imported(null))
        assertEquals(true, PdfNames.isScannerOutput("file:///data/user/0/com.inkwell.notes/cache/mlkit_docscan_ui_client/x.pdf"))
    }

    @Test
    fun extractedAndMergedTitlesStayWithinTheLimitAndAreSanitised() {
        val long = "x".repeat(PdfNames.MAX_TITLE_CHARS + 50)
        assertTrue(PdfNames.extractTitle(long, listOf(0, 1)).length <= PdfNames.MAX_TITLE_CHARS)
        assertTrue(PdfNames.mergedTitle(listOf(long, long)).length <= PdfNames.MAX_TITLE_CHARS)
        assertEquals("Lecture (pages 4-10)", PdfNames.extractTitle("Lecture", (3..9).toList()))
        assertEquals("Merged document", PdfNames.mergedTitle(listOf("", "   ")))
        assertEquals("A + B", PdfNames.mergedTitle(listOf("A", "B")))
        assertEquals("A + 1 more", PdfNames.mergedTitle(listOf("A", "a")))
        assertEquals("A (merged)", PdfNames.mergedTitle(listOf("A")))
    }

    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
