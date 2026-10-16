package com.inkwell.notes.export

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.export.format.OutlineNode
import com.inkwell.notes.export.format.OutlinePlan
import com.inkwell.notes.export.format.ZOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineAndOrderTest {
    private val pages = (0 until 6).map { PageMeta("p$it", "d", it, 595f, 842f) }

    @Test fun outlineFollowsEntriesAndExportedPages() {
        val entries = listOf(
            OutlineEntry("a", "d", "Part A", "p0", sortOrder = 0),
            OutlineEntry("a1", "d", "A.1", "p1", parentId = "a", sortOrder = 0),
            OutlineEntry("b", "d", "Part B", "p3", sortOrder = 1),
            OutlineEntry("b1", "d", "B.1", "p4", parentId = "b", sortOrder = 1),
            OutlineEntry("b0", "d", "B.0", "p5", parentId = "b", sortOrder = 0),
        )
        // Export pages 1, 4 and 5 only (0-based), so Part A and Part B themselves are not exported.
        val nodes = OutlinePlan.build(entries, pages, listOf(1, 4, 5))
        assertEquals(listOf(OutlineNode("A.1", 0), OutlineNode("B.0", 2), OutlineNode("B.1", 1)), nodes)

        val all = OutlinePlan.build(entries, pages, (0 until 6).toList())
        assertEquals(listOf("Part A", "Part B"), all.map { it.title })
        assertEquals(listOf("B.0", "B.1"), all[1].children.map { it.title })
        assertEquals(3, all[1].exportIndex)
    }

    @Test fun bookmarksAreAddedAfterTheOutline() {
        val marked = pages.toMutableList()
        marked[2] = marked[2].copy(bookmarked = true, bookmarkTitle = "Key graph")
        marked[0] = marked[0].copy(bookmarked = true)
        val nodes = OutlinePlan.build(listOf(OutlineEntry("a", "d", "Start", "p0")), marked, (0 until 6).toList()) { "Page ${it + 1}" }
        // Page 1 already has an outline entry and no own title, so only the titled bookmark is added.
        assertEquals(listOf(OutlineNode("Start", 0), OutlineNode("Key graph", 2)), nodes)
        val noOutline = OutlinePlan.build(emptyList(), marked, listOf(0, 2)) { "Page ${it + 1}" }
        assertEquals(listOf(OutlineNode("Page 1", 0), OutlineNode("Key graph", 1)), noOutline)
    }

    @Test fun cyclesAndBlankTitlesDoNotBreakTheOutline() {
        val entries = listOf(
            OutlineEntry("x", "d", "X", "p0", parentId = "y"),
            OutlineEntry("y", "d", "Y", "p1", parentId = "x"),
            OutlineEntry("z", "d", "   ", "p2"),
        )
        val nodes = OutlinePlan.build(entries, pages, (0 until 6).toList())
        assertEquals(setOf("X", "Y"), nodes.map { it.title }.toSet())
    }

    private fun stroke(id: String, z: Int, pen: PenType = PenType.FOUNTAIN) =
        Stroke(id, pen, 0, 2f, floatArrayOf(10f, 10f, 0.5f, 0f, 0f, 30f, 40f, 0.5f, 0f, 10f), 0, z = z)

    @Test fun paintOrderMatchesTheCanvas() {
        val content = PageContent(
            strokes = listOf(stroke("ink0", 0), stroke("hl0", 0, PenType.HIGHLIGHTER), stroke("ink1", 1)),
            shapes = listOf(ShapeItem("shape0", ShapeKind.RECTANGLE, floatArrayOf(0f, 0f, 5f, 5f), 0, z = 0)),
            texts = listOf(TextItem("text1", 0f, 0f, 1f, 1f, "t", z = 1)),
            images = listOf(ImageItem("img0", "a", 0f, 0f, 1f, 1f, z = 0), ImageItem("img2", "a", 0f, 0f, 1f, 1f, z = 2)),
            stickies = listOf(StickyItem("st1", 0f, 0f, z = 1)),
            connectors = listOf(ConnectorItem("c0", z = 0)),
        )
        val ids = ZOrder.ordered(content).map {
            when (it) {
                is Stroke -> it.id
                is ShapeItem -> it.id
                is TextItem -> it.id
                is ImageItem -> it.id
                is StickyItem -> it.id
                is ConnectorItem -> it.id
                else -> "?"
            }
        }
        assertEquals(listOf("hl0", "img0", "c0", "ink0", "shape0", "st1", "ink1", "text1", "img2"), ids)
        assertTrue(ZOrder.hasInk(content))
        assertFalse(ZOrder.hasInk(PageContent(texts = content.texts, stickies = content.stickies)))
    }

    @Test fun boundsCoverStrokeWidthsAndRotatedBoxes() {
        assertNull(ZOrder.contentBounds(PageContent.EMPTY))
        val b = ZOrder.contentBounds(PageContent(strokes = listOf(stroke("s", 0))))!!
        assertEquals(listOf(8f, 8f, 32f, 42f), b.toList())
        val rotated = ZOrder.contentBounds(PageContent(texts = listOf(TextItem("t", 0f, 0f, 20f, 10f, "x", rotation = 90f))))!!
        assertEquals(5f, rotated[0], 1e-3f)
        assertEquals(-5f, rotated[1], 1e-3f)
        assertEquals(15f, rotated[2], 1e-3f)
        assertEquals(15f, rotated[3], 1e-3f)
    }
}
