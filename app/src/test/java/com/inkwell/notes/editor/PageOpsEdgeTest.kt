package com.inkwell.notes.editor

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.editor.logic.PageRanges
import com.inkwell.notes.editor.logic.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Index arithmetic behind the pages panel: deletes, moves, insertions and reorders. */
class PageOpsEdgeTest {
    private fun pages(n: Int) = List(n) { PageMeta("p$it", "d", it, 595f, 842f) }

    @Test
    fun `current page index after deleting pages around and including it`() {
        // 6 pages, current = 3
        assertEquals(2, PageRanges.indexAfterDelete(3, listOf(0), 6))            // one before: shifts up
        assertEquals(3, PageRanges.indexAfterDelete(3, listOf(5), 6))            // one after: unchanged
        assertEquals(3, PageRanges.indexAfterDelete(3, listOf(3), 6))            // itself: successor takes its slot
        assertEquals(1, PageRanges.indexAfterDelete(3, listOf(0, 1, 3), 6))      // two before + itself
        assertEquals(2, PageRanges.indexAfterDelete(5, listOf(3, 4, 5), 6))      // last pages: new last page
        assertEquals(0, PageRanges.indexAfterDelete(2, listOf(0, 1, 2, 3, 4), 6)) // everything but one
        assertEquals(0, PageRanges.indexAfterDelete(2, listOf(0, 1, 2, 3, 4, 5), 6)) // nothing left
        assertEquals(1, PageRanges.indexAfterDelete(3, listOf(0, 0, 1, 1), 6))   // duplicates count once
    }

    @Test
    fun `insertion index lands where the new page appears`() {
        assertEquals(0, PageRanges.insertionIndex(0, after = false, count = 0))
        assertEquals(0, PageRanges.insertionIndex(7, after = false, count = 0))
        assertEquals(5, PageRanges.insertionIndex(4, after = true, count = 5))   // after the last page = append
        assertEquals(4, PageRanges.insertionIndex(9, after = true, count = 4))   // anchor clamped first
        assertEquals(0, PageRanges.insertionIndex(-3, after = false, count = 4))
    }

    @Test
    fun `move keeps indices in the original list and ignores invalid moves`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PageRanges.move(list, 0, 2))
        assertEquals(listOf("c", "a", "b", "d"), PageRanges.move(list, 2, 0))
        assertEquals(listOf("b", "c", "d", "a"), PageRanges.move(list, 0, 3))
        assertTrue(PageRanges.move(list, 0, 4) === list)
        assertTrue(PageRanges.move(list, -1, 2) === list)
        assertTrue(PageRanges.move(list, 1, 1) === list)
    }

    @Test
    fun `reorder detection rejects partial, duplicated and foreign orders`() {
        val p = pages(3)
        assertTrue(PageRanges.isReorder(p, listOf("p2", "p0", "p1")))
        assertFalse(PageRanges.isReorder(p, listOf("p0", "p1", "p2")))
        assertFalse(PageRanges.isReorder(p, listOf("p0", "p1")))
        assertFalse(PageRanges.isReorder(p, listOf("p0", "p0", "p1")))
        assertFalse(PageRanges.isReorder(p, listOf("p0", "p1", "zz")))
    }

    @Test
    fun `page range parsing rejects garbage and keeps order`() {
        assertNull(PageRanges.parse("", 5))
        assertNull(PageRanges.parse("0", 5))
        assertNull(PageRanges.parse("6", 5))
        assertNull(PageRanges.parse("3-1", 5))
        assertNull(PageRanges.parse("1-2-3", 5))
        assertNull(PageRanges.parse("a", 5))
        assertNull(PageRanges.parse("1", 0))
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("5, 1–3, 2", 5))
        assertEquals("1–3, 5", PageRanges.format(listOf(4, 2, 0, 1, 2)))
        assertEquals("", PageRanges.format(emptyList()))
    }

    @Test
    fun `display position is one based and clamped`() {
        assertEquals(0 to 0, PageRanges.displayPosition(3, 0))
        assertEquals(1 to 4, PageRanges.displayPosition(-2, 4))
        assertEquals(4 to 4, PageRanges.displayPosition(99, 4))
    }

    @Test
    fun `pixels per point follows fit-width and zoom`() {
        // 1200px wide view at 2x density: 1200 - 96 = 1104 px for a 552pt page -> 2 px/pt.
        assertEquals(2f, Placement.estimatePxPerPt(1200f, 2f, 552f, 1f), 1e-4f)
        assertEquals(4f, Placement.estimatePxPerPt(1200f, 2f, 552f, 2f), 1e-4f)
        // Degenerate inputs fall back to density x zoom instead of dividing by zero.
        assertEquals(3f, Placement.estimatePxPerPt(0f, 2f, 552f, 1.5f), 1e-4f)
        assertEquals(2f, Placement.estimatePxPerPt(1200f, 2f, 0f, 1f), 1e-4f)
        // Very narrow views keep at least 60% of the width for the page.
        assertEquals(0.6f * 100f / 595f, Placement.estimatePxPerPt(100f, 3f, 595f, 1f), 1e-4f)
    }
}
