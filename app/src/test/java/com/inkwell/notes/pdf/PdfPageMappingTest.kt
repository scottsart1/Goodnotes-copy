package com.inkwell.notes.pdf

import com.inkwell.notes.pdf.PdfPageMapping.Ref
import com.inkwell.notes.pdf.PdfPageMapping.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageMappingTest {

    // ---- insert

    @Test fun insertionIndexClampsAndDefaultsToEnd() {
        assertEquals(5, PdfPageMapping.insertionIndex(null, 5))
        assertEquals(0, PdfPageMapping.insertionIndex(-3, 5))
        assertEquals(5, PdfPageMapping.insertionIndex(9, 5))
        assertEquals(2, PdfPageMapping.insertionIndex(2, 5))
        assertEquals(0, PdfPageMapping.insertionIndex(null, 0))
    }

    @Test fun insertAppendsToPdfAndPlacesPagesAtTheRequestedPosition() {
        // Pages: pdf 0, pdf 1, paper, pdf 2 (the source PDF has 3 pages). Insert 2 pages after page 1.
        val existing = listOf(0, 1, null, 2)
        val plan = PdfPageMapping.insertPlan(existingPdfCount = 3, insertedCount = 2, atIndex = 1, pageCount = existing.size)
        assertEquals(3, plan.firstPdfIndex)
        assertEquals(1, plan.atPageIndex)
        assertEquals(listOf(3, 4), plan.pdfIndices)
        assertEquals(listOf(0, 3, 4, 1, null, 2), PdfPageMapping.applyInsert(existing, plan))
    }

    @Test fun insertKeepsEveryExistingPdfIndex() {
        val existing = listOf(4, null, 0, 3, 1, 2)
        val plan = PdfPageMapping.insertPlan(existingPdfCount = 5, insertedCount = 3, atIndex = null, pageCount = existing.size)
        val after = PdfPageMapping.applyInsert(existing, plan)
        assertEquals(existing, after.take(existing.size))
        assertEquals(listOf(5, 6, 7), after.drop(existing.size))
    }

    @Test fun insertIntoNotebookWithoutPdfStartsAtZero() {
        val existing = listOf<Int?>(null, null)
        val plan = PdfPageMapping.insertPlan(existingPdfCount = 0, insertedCount = 2, atIndex = 0, pageCount = 2)
        assertEquals(listOf(0, 1, null, null), PdfPageMapping.applyInsert(existing, plan))
    }

    @Test fun insertAtOutOfRangePositionGoesToTheEnd() {
        val plan = PdfPageMapping.insertPlan(existingPdfCount = 2, insertedCount = 1, atIndex = 42, pageCount = 2)
        assertEquals(2, plan.atPageIndex)
        assertEquals(listOf(0, 1, 2), PdfPageMapping.applyInsert(listOf(0, 1), plan))
    }

    // ---- extract

    @Test fun extractKeepsOnlyReferencedPagesInOrderOfAppearance() {
        val refs = listOf(Ref("a", 5), null, Ref("a", 2), Ref("a", 3))
        val c = PdfPageMapping.compact(refs)
        assertEquals(listOf(Ref("a", 5), Ref("a", 2), Ref("a", 3)), c.keep)
        assertEquals(listOf(0, null, 1, 2), c.remapped)
    }

    @Test fun duplicatedPagesShareOneCopyOfTheirPdfPage() {
        val refs = listOf(Ref("a", 5), Ref("a", 5), Ref("a", 1), Ref("a", 5))
        val c = PdfPageMapping.compact(refs)
        assertEquals(listOf(Ref("a", 5), Ref("a", 1)), c.keep)
        assertEquals(listOf(0, 0, 1, 0), c.remapped)
    }

    @Test fun extractingOnlyPaperPagesNeedsNoPdf() {
        val c = PdfPageMapping.compact(listOf<Ref<String>?>(null, null))
        assertTrue(c.keep.isEmpty())
        assertEquals(listOf(null, null), c.remapped)
    }

    @Test fun negativeIndicesAreTreatedAsPaper() {
        val c = PdfPageMapping.compact(listOf(Ref("a", -1), Ref("a", 0)))
        assertEquals(listOf(null, 0), c.remapped)
    }

    @Test fun mergeRenumbersPagesOfSeveralPdfs() {
        // Document A: pdf 0, pdf 1. Document B: pdf 0, paper, pdf 2.
        val refs = listOf(Ref("A", 0), Ref("A", 1), Ref("B", 0), null, Ref("B", 2))
        val c = PdfPageMapping.compact(refs)
        assertEquals(listOf(Ref("A", 0), Ref("A", 1), Ref("B", 0), Ref("B", 2)), c.keep)
        assertEquals(listOf(0, 1, 2, null, 3), c.remapped)
    }

    @Test fun everyRemappedIndexPointsAtTheSameSourcePage() {
        val refs = listOf(Ref("A", 7), null, Ref("B", 1), Ref("A", 7), Ref("A", 3), Ref("B", 1))
        val c = PdfPageMapping.compact(refs)
        refs.forEachIndexed { i, ref ->
            val idx = c.remapped[i]
            if (ref == null) assertEquals(null, idx) else assertEquals(ref, c.keep[idx!!])
        }
    }

    @Test fun selectionDropsInvalidAndDuplicateIndices() {
        assertEquals(listOf(3, 1, 0), PdfPageMapping.selection(listOf(3, 1, 3, -1, 9, 0), pageCount = 5))
        assertTrue(PdfPageMapping.selection(listOf(5, 6), pageCount = 5).isEmpty())
    }

    // ---- segments

    @Test fun consecutivePdfPagesBecomeOneRun() {
        val segments = PdfPageMapping.segments(listOf(0, 1, 2, null, 3, 4))
        assertEquals(
            listOf(Segment.PdfRun(0, 3, 0), Segment.Paper(3), Segment.PdfRun(4, 2, 3)),
            segments,
        )
    }

    @Test fun nonConsecutiveIndicesStartNewRuns() {
        val segments = PdfPageMapping.segments(listOf(0, 0, 1, 3))
        assertEquals(
            listOf(Segment.PdfRun(0, 1, 0), Segment.PdfRun(1, 2, 0), Segment.PdfRun(3, 1, 3)),
            segments,
        )
    }

    @Test fun segmentsCoverEveryPageExactlyOnce() {
        val remapped = listOf(null, 0, 1, null, null, 2, 0, 1, 2)
        val covered = PdfPageMapping.segments(remapped).flatMap { s -> (s.start until s.start + s.count).toList() }
        assertEquals(remapped.indices.toList(), covered)
        // And the runs reproduce the mapping.
        val rebuilt = arrayOfNulls<Int>(remapped.size)
        PdfPageMapping.segments(remapped).forEach { s ->
            if (s is Segment.PdfRun) for (i in 0 until s.count) rebuilt[s.start + i] = s.firstPdfIndex + i
        }
        assertEquals(remapped, rebuilt.toList())
    }

    @Test fun emptyListHasNoSegments() {
        assertTrue(PdfPageMapping.segments(emptyList()).isEmpty())
    }

    // ---- split

    @Test fun splitMovesPagesFromTheSplitPointToTheEnd() {
        assertEquals(3..6, PdfPageMapping.splitRange(pageCount = 7, atPageIndex = 3))
        assertEquals(6..6, PdfPageMapping.splitRange(pageCount = 7, atPageIndex = 6))
        assertEquals(1..1, PdfPageMapping.splitRange(pageCount = 2, atPageIndex = 1))
    }

    @Test fun splitRejectsPositionsThatLeaveAnEmptyPart() {
        for ((count, at) in listOf(7 to 0, 7 to 7, 7 to -1, 1 to 1, 0 to 0)) {
            try {
                PdfPageMapping.splitRange(count, at)
                throw AssertionError("split $at of $count should fail")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test fun splitExtractedPagesKeepTheirPdfPagesAfterCompaction() {
        // Original: pdf 0..5 with a paper page at 3; split at 2 moves [2, paper, 3, 4].
        val original = listOf(0, 1, 2, null, 3, 4)
        val moved = PdfPageMapping.splitRange(original.size, 2).map { original[it] }
        val c = PdfPageMapping.compact(moved.map { idx -> idx?.let { Ref("doc", it) } })
        assertEquals(listOf(Ref("doc", 2), Ref("doc", 3), Ref("doc", 4)), c.keep)
        assertEquals(listOf(0, null, 1, 2), c.remapped)
    }
}
