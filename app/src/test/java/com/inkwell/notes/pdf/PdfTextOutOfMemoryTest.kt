package com.inkwell.notes.pdf

import com.inkwell.notes.core.storage.UnreadablePageException
import com.inkwell.notes.ocr.work.PdfTextFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A source PDF whose text extraction keeps running out of memory is given up on (its pages are
 * OCRed instead of retried forever), and split/extract refuse to copy an unreadable page as blank.
 */
class PdfTextOutOfMemoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun failuresAreCountedPerVersionOfTheFile() {
        val oom = PdfTextOutOfMemory()
        assertEquals(0, oom.count("d", 100, 1))
        assertEquals(1, oom.record("d", 100, 1))
        assertFalse(oom.givenUp("d", 100, 1))
        assertEquals(2, oom.record("d", 100, 1))
        assertTrue(oom.givenUp("d", 100, 1))
        // Another document, or a replaced file, starts from zero.
        assertFalse(oom.givenUp("other", 100, 1))
        assertFalse(oom.givenUp("d", 200, 1))
        assertFalse(oom.givenUp("d", 100, 2))
        assertEquals(1, oom.record("d", 200, 1))
        assertFalse(oom.givenUp("d", 100, 1))
    }

    @Test fun extractorStopsLoadingAFileThatKeepsRunningOutOfMemory() = runBlocking<Unit> {
        val dir = tmp.newFolder("work")
        val source = File(dir, "source.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var loads = 0
        val ops = PdfBoxOps(initialise = { loads++; throw OutOfMemoryError("test") }, tempDir = { dir })
        val scope = CoroutineScope(Job())
        try {
            val extractor = PdfTextExtractor(ops, sourceFile = { source }, scope = scope)
            assertNull(extractor.pageTextOrNull("d", 0))
            assertFalse(extractor.givenUp("d"))
            assertNull(extractor.pageTextOrNull("d", 1))
            assertTrue(extractor.givenUp("d"))
            // Given up: the file is not loaded again.
            assertNull(extractor.pageTextOrNull("d", 2))
            assertEquals(2, loads)
            // A replaced source file is tried again.
            source.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertFalse(extractor.givenUp("d"))
            assertNull(extractor.pageTextOrNull("d", 0))
            assertEquals(3, loads)
        } finally {
            scope.cancel()
        }
    }

    @Test fun pagesWithNothingToKeepOrAGivenUpFileAreOcredInstead() {
        // Indexed before and the failure may pass: keep the earlier rows and retry later.
        assertFalse(PdfTextFallback.ocrInstead(indexedBefore = true, givenUp = false))
        // Never indexed: nothing to keep, so OCR now instead of leaving the page unsearchable.
        assertTrue(PdfTextFallback.ocrInstead(indexedBefore = false, givenUp = false))
        // The file keeps running out of memory: retrying would never succeed.
        assertTrue(PdfTextFallback.ocrInstead(indexedBefore = true, givenUp = true))
        assertTrue(PdfTextFallback.ocrInstead(indexedBefore = false, givenUp = true))
    }

    @Test fun unreadablePagesAbortCopiesWithTheirOwnError() {
        assertEquals(PdfError.PAGE_UNREADABLE, PdfErrors.writeError(UnreadablePageException("p1")))
        assertEquals(PdfError.SAVE_FAILED, PdfErrors.writeError(IOException("Broken pipe")))
        assertEquals(PdfError.NO_SPACE, PdfErrors.writeError(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(PdfError.TOO_LARGE, PdfErrors.writeError(OutOfMemoryError()))
    }
}
