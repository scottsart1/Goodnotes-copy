package com.inkwell.notes.export

import com.inkwell.notes.export.format.ExportChoices
import com.inkwell.notes.export.format.PageRange
import com.inkwell.notes.export.format.PdfQuality
import com.inkwell.notes.export.format.RangeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeTest {
    private fun ok(input: String, pages: Int = 10): List<Int> {
        val r = PageRange.parse(input, pages)
        assertTrue("expected ok for '$input' but was $r", r is PageRange.Result.Ok)
        return (r as PageRange.Result.Ok).indices
    }

    private fun err(input: String, pages: Int = 10): PageRange.Result.Error {
        val r = PageRange.parse(input, pages)
        assertTrue("expected error for '$input' but was $r", r is PageRange.Result.Error)
        return r as PageRange.Result.Error
    }

    @Test fun singlePagesAndRangesAreZeroBased() {
        assertEquals(listOf(0, 1, 2, 4), ok("1-3,5"))
        assertEquals(listOf(6), ok(" 7 "))
        assertEquals(listOf(0, 1, 2), ok("1 - 3"))
    }

    @Test fun duplicatesAreMergedAndSorted() {
        assertEquals(listOf(0, 1, 2, 3), ok("4, 1-3, 2, 3"))
    }

    @Test fun openRangesRunToTheEnds() {
        assertEquals(listOf(7, 8, 9), ok("8-"))
        assertEquals(listOf(0, 1, 2), ok("-3"))
    }

    @Test fun semicolonsDashesAndEmptyPartsAreAccepted() {
        assertEquals(listOf(1, 2, 4), ok("2–3; 5,,"))
        assertEquals(listOf(0, 1), ok("1—2"))
    }

    @Test fun errorsNameTheOffendingPart() {
        assertEquals(PageRange.Reason.EMPTY, err("").reason)
        assertEquals(PageRange.Reason.EMPTY, err(" , ").reason)
        assertEquals(PageRange.Reason.SYNTAX, err("1,a").reason)
        assertEquals("a", err("1,a").token)
        assertEquals(PageRange.Reason.SYNTAX, err("1-2-3").reason)
        assertEquals(PageRange.Reason.SYNTAX, err("-").reason)
        assertEquals(PageRange.Reason.OUT_OF_RANGE, err("0").reason)
        assertEquals(PageRange.Reason.OUT_OF_RANGE, err("11").reason)
        assertEquals("9-12", err("9-12").token)
        assertEquals(PageRange.Reason.REVERSED, err("5-3").reason)
        assertEquals(PageRange.Reason.EMPTY, err("1", pages = 0).reason)
    }

    @Test fun formatCompactsConsecutivePages() {
        assertEquals("1-3, 5", PageRange.format(listOf(0, 1, 2, 4)))
        assertEquals("2", PageRange.format(listOf(1)))
        assertEquals("1, 3-4, 9", PageRange.format(listOf(8, 0, 2, 3)))
        assertEquals("", PageRange.format(emptyList()))
        // Round trip.
        assertEquals(listOf(0, 1, 2, 4, 7, 8), ok(PageRange.format(listOf(0, 1, 2, 4, 7, 8))))
    }

    @Test fun resolveFiltersAndSorts() {
        assertEquals((0 until 4).toList(), PageRange.resolve(null, 4))
        assertEquals(listOf(1, 3), PageRange.resolve(listOf(3, 1, 3, 9, -1), 4))
        assertEquals(emptyList<Int>(), PageRange.resolve(listOf(9), 4))
        assertEquals(emptyList<Int>(), PageRange.resolve(null, 0))
    }

    @Test fun choicesBecomeOptions() {
        val c = ExportChoices(format = ExportFormat.PDF, range = RangeMode.CUSTOM, custom = "2-3", pdfQuality = PdfQuality.SMALL, flatten = false)
        val r = c.toOptions(pageCount = 5, selected = null, paged = true) as ExportChoices.Result.Valid
        assertEquals(listOf(1, 2), r.options.pageIndices)
        assertEquals(150, r.options.dpi)
        assertEquals(70, r.options.jpegQuality)
        assertEquals(false, r.options.flattenAnnotations)

        val invalid = c.copy(custom = "7").toOptions(5, null, true)
        assertTrue(invalid is ExportChoices.Result.Invalid)

        val selected = ExportChoices(format = ExportFormat.PNG, range = RangeMode.SELECTED, imageDpi = 300, jpegQuality = 55)
            .toOptions(5, listOf(4, 2, 9), true) as ExportChoices.Result.Valid
        assertEquals(listOf(2, 4), selected.options.pageIndices)
        assertEquals(300, selected.options.dpi)

        // Typed documents and boards ignore page choices.
        val unpaged = c.copy(custom = "7").toOptions(0, null, paged = false) as ExportChoices.Result.Valid
        assertNull(unpaged.options.pageIndices)
    }

    @Test fun rememberedChoicesNeverKeepAPageSelection() {
        val saved = ExportChoices(format = ExportFormat.MARKDOWN, range = RangeMode.SELECTED, custom = "1-2", includeTranscripts = false)
        val restored = ExportChoices.decode(ExportChoices.encode(saved))
        assertEquals(ExportFormat.MARKDOWN, restored.format)
        assertEquals(RangeMode.ALL, restored.range)
        assertEquals("", restored.custom)
        assertEquals(false, restored.includeTranscripts)
        assertEquals(RangeMode.SELECTED, ExportChoices.initial(restored, listOf(1)).range)
        assertEquals(ExportChoices(), ExportChoices.decode("not json"))
        assertEquals(ExportChoices(), ExportChoices.decode(null))
    }
}
