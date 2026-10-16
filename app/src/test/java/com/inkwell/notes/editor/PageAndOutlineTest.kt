package com.inkwell.notes.editor

import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.editor.logic.AiOutline
import com.inkwell.notes.editor.logic.AiOutlineItem
import com.inkwell.notes.editor.logic.OutlineTree
import com.inkwell.notes.editor.logic.PageRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAndOutlineTest {
    private fun page(i: Int) = PageMeta("p$i", "d", i, 595f, 842f)
    private val pages = (0 until 6).map(::page)

    @Test
    fun `page ranges parse and format`() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3, 5", 6))
        assertEquals(listOf(0, 1, 2), PageRanges.parse("3–1".reversed(), 6))
        assertEquals(listOf(5), PageRanges.parse(" 6 ", 6))
        assertNull(PageRanges.parse("0", 6))
        assertNull(PageRanges.parse("7", 6))
        assertNull(PageRanges.parse("4-2", 6))
        assertNull(PageRanges.parse("a", 6))
        assertNull(PageRanges.parse("", 6))
        assertEquals("1–3, 5", PageRanges.format(listOf(2, 0, 1, 4)))
        assertEquals("2", PageRanges.format(listOf(1, 1)))
        assertEquals("", PageRanges.format(emptyList()))
    }

    @Test
    fun `page position labels and clamping`() {
        assertEquals(3 to 7, PageRanges.displayPosition(2, 7))
        assertEquals(7 to 7, PageRanges.displayPosition(42, 7))
        assertEquals(0 to 0, PageRanges.displayPosition(0, 0))
        assertEquals(0, PageRanges.clampIndex(-3, 5))
    }

    @Test
    fun `insert before and after`() {
        assertEquals(2, PageRanges.insertionIndex(2, after = false, count = 5))
        assertEquals(3, PageRanges.insertionIndex(2, after = true, count = 5))
        assertEquals(5, PageRanges.insertionIndex(9, after = true, count = 5))
        assertEquals(0, PageRanges.insertionIndex(0, after = true, count = 0))
    }

    @Test
    fun `move reorders and detects changes`() {
        val ids = pages.map { it.id }
        val moved = PageRanges.move(ids, 0, 3)
        assertEquals(listOf("p1", "p2", "p3", "p0", "p4", "p5"), moved)
        assertTrue(PageRanges.isReorder(pages, moved))
        assertFalse(PageRanges.isReorder(pages, ids))
        assertFalse(PageRanges.isReorder(pages, ids.dropLast(1)))
        assertEquals(ids, PageRanges.move(ids, 2, 2))
        assertEquals(ids, PageRanges.move(ids, -1, 2))
    }

    @Test
    fun `selection helpers keep document order and protect the last page`() {
        val sel = setOf("p4", "p1")
        assertEquals(listOf("p1", "p4"), PageRanges.orderedSelection(pages, sel).map { it.id })
        assertEquals(listOf(1, 4), PageRanges.indicesOf(pages, sel))
        assertEquals(setOf("p1"), PageRanges.toggle(sel, "p4"))
        assertTrue(PageRanges.canDelete(5, 6))
        assertFalse(PageRanges.canDelete(6, 6))
        assertFalse(PageRanges.canDelete(0, 6))
        assertEquals(2, PageRanges.indexAfterDelete(4, listOf(0, 1), 6))
        assertEquals(3, PageRanges.indexAfterDelete(5, listOf(5, 4), 6))
        assertEquals(1, PageRanges.indexAfterDelete(1, listOf(1), 6))
    }

    @Test
    fun `outline tree nests children and survives broken parents`() {
        val entries = listOf(
            OutlineEntry("b", "d", "Child", "p2", parentId = "a", sortOrder = 1),
            OutlineEntry("a", "d", "Root", "p1", sortOrder = 0),
            OutlineEntry("c", "d", "Orphan", "p3", parentId = "missing", sortOrder = 2),
            OutlineEntry("x", "d", "Loop1", "p4", parentId = "y", sortOrder = 3),
            OutlineEntry("y", "d", "Loop2", "p5", parentId = "x", sortOrder = 4),
        )
        val nodes = OutlineTree.flatten(entries, pages.associate { it.id to it.index })
        assertEquals(listOf("a", "b", "c", "x", "y"), nodes.map { it.entry.id })
        assertEquals(listOf(0, 1, 0, 0, 0), nodes.map { it.depth })
        assertEquals(2, nodes[1].pageIndex)
    }

    @Test
    fun `ai outline replies are parsed leniently`() {
        val reply = """
            Here is the outline:
            ```json
            [
              {"title": "Introduction", "pageIndex": 0, "level": 1},
              {"title": "  Supply   and demand ", "pageIndex": "2", "level": 2},
              {"title": "Bad page", "pageIndex": 40, "level": 1},
              {"title": "", "pageIndex": 1},
              {"title": "One based", "page": 4, "level": 9}
            ]
            ```
        """.trimIndent()
        val items = AiOutline.parse(reply, pageCount = 6)
        assertEquals(
            listOf(
                AiOutlineItem("Introduction", 0, 1),
                AiOutlineItem("Supply and demand", 2, 2),
                AiOutlineItem("One based", 3, 3),
            ),
            items,
        )
        assertEquals(1, AiOutline.parse("""{"outline":[{"title":"A","pageIndex":1}]}""", 6).size)
        assertTrue(AiOutline.parse("Sorry, I cannot help.", 6).isEmpty())
    }

    @Test
    fun `ai outline items become nested entries without touching pages`() {
        var n = 0
        val items = listOf(
            AiOutlineItem("Part A", 0, 1),
            AiOutlineItem("A.1", 1, 2),
            AiOutlineItem("A.1.a", 1, 3),
            AiOutlineItem("A.2", 2, 2),
            AiOutlineItem("Part B", 4, 1),
            AiOutlineItem("Gone", 99, 1),
        )
        val entries = AiOutline.toEntries(items, pages, "d", startSortOrder = 10) { "e${n++}" }
        assertEquals(listOf("Part A", "A.1", "A.1.a", "A.2", "Part B"), entries.map { it.title })
        assertEquals(listOf(null, "e0", "e1", "e0", null), entries.map { it.parentId })
        assertEquals(listOf("p0", "p1", "p1", "p2", "p4"), entries.map { it.pageId })
        assertEquals((10..14).toList(), entries.map { it.sortOrder })
    }

    @Test
    fun `outline prompt marks pages and respects limits`() {
        val prompt = AiOutline.buildPrompt(listOf(1 to "second   page", 0 to "first", 2 to "   "))
        assertTrue(prompt.startsWith("### pageIndex 0\nfirst"))
        assertTrue(prompt.contains("### pageIndex 1\nsecond page"))
        assertFalse(prompt.contains("pageIndex 2"))
        val long = AiOutline.buildPrompt((0 until 100).map { it to "x".repeat(5000) })
        assertTrue(long.length <= AiOutline.MAX_TOTAL_CHARS)
    }
}
