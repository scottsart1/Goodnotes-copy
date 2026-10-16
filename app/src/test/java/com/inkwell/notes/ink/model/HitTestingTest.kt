package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.LassoFilter
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.render.RenderOrder
import com.inkwell.notes.ink.tools.LassoSelector
import com.inkwell.notes.ink.tools.ShapeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class HitTestingTest {
    private val stroke = TestInk.line("s", 0f, 100f, 200f, 100f, z = 1)
    private val image = ImageItem("img", "asset", 50f, 50f, 100f, 100f, z = 2)
    private val text = TextItem("txt", 300f, 0f, 100f, 40f, "hi", z = 3)
    private val hollow = TestInk.rect("hollow", 400f, 400f, 500f, 500f, z = 4)
    private val filled = TestInk.rect("filled", 600f, 400f, 700f, 500f, fill = 0xFFFF0000.toInt(), z = 5)
    private val sticky = StickyItem("st", 800f, 0f, z = 6)
    private val content = PageContent(strokes = listOf(stroke), shapes = listOf(hollow, filled), texts = listOf(text), images = listOf(image), stickies = listOf(sticky))
    private val index = ItemIndex.build(content, IdentityHashMap())

    @Test fun topmostItemWins() {
        // The image (z=2) is above the stroke (z=1) where they overlap.
        assertSame(image, index.hitTest(100f, 100f, 4f))
        // Outside the image only the stroke is hit.
        assertSame(stroke, index.hitTest(180f, 101f, 4f))
        assertNull(index.hitTest(180f, 130f, 4f))
        // Filters can skip items.
        assertSame(stroke, index.hitTest(100f, 100f, 4f) { it !is ImageItem })
    }

    @Test fun shapesHitOnOutlineOrFill() {
        assertSame(hollow, index.hitTest(401f, 450f, 3f))
        assertNull(index.hitTest(450f, 450f, 3f))
        assertSame(filled, index.hitTest(650f, 450f, 3f))
        assertSame(text, index.hitTest(350f, 20f, 0f))
        assertSame(sticky, index.hitTest(850f, 50f, 0f))
    }

    @Test fun rotatedBoxesHitInTheirOwnFrame() {
        val rotated = TextItem("r", 0f, 0f, 100f, 10f, "x", rotation = 90f)
        // Rotated 90 degrees around (50, 5): now spans x 45..55, y -45..55.
        assertTrue(ItemGeometry.hitPoint(rotated, 50f, -40f, 0f))
        assertFalse(ItemGeometry.hitPoint(rotated, 90f, 5f, 0f))
        val b = ItemGeometry.bounds(rotated, Bounds())
        assertEquals(45f, b.left, 1e-3f); assertEquals(-45f, b.top, 1e-3f)
    }

    @Test fun eraserSegmentHits() {
        val hits = index.hitBySegment(100f, 90f, 100f, 110f, 1f) { true }
        assertTrue(hits.contains(stroke))
        assertTrue(hits.contains(image))
        assertTrue(index.hitBySegment(450f, 450f, 451f, 451f, 1f) { true }.isEmpty())
    }

    @Test fun connectorEndpointsFollowAttachedItems() {
        val a = StickyItem("a", 0f, 0f, 100f, 100f)
        val b = StickyItem("b", 300f, 0f, 100f, 100f)
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b")
        val e = ItemGeometry.connectorEndpoints(c, PageContent(stickies = listOf(a, b), connectors = listOf(c)), FloatArray(4))
        assertEquals(100f, e[0], 0f); assertEquals(50f, e[1], 0f)
        assertEquals(300f, e[2], 0f); assertEquals(50f, e[3], 0f)
    }

    @Test fun renderOrderPutsHighlighterBeneathInkWithinStrokeRuns() {
        val ink1 = TestInk.line("ink1", 0f, 0f, 1f, 1f, z = 1)
        val hl = TestInk.line("hl", 0f, 0f, 1f, 1f, pen = PenType.HIGHLIGHTER, z = 2)
        val ink2 = TestInk.line("ink2", 0f, 0f, 1f, 1f, z = 3)
        val img = ImageItem("img", "a", 0f, 0f, 1f, 1f, z = 4)
        val hl2 = TestInk.line("hl2", 0f, 0f, 1f, 1f, pen = PenType.HIGHLIGHTER, z = 5)
        val order = RenderOrder.order(PageContent(strokes = listOf(ink1, hl, ink2, hl2), images = listOf(img))).map { Items.id(it) }
        assertEquals(listOf("hl", "ink1", "ink2", "img", "hl2"), order)
        // Images sit beneath strokes of the same z.
        val sameZ = RenderOrder.order(PageContent(strokes = listOf(TestInk.line("s", 0f, 0f, 1f, 1f, z = 1)), images = listOf(ImageItem("i", "a", 0f, 0f, 1f, 1f, z = 1)))).map { Items.id(it) }
        assertEquals(listOf("i", "s"), sameZ)
    }

    @Test fun lassoSelectsItemsMostlyInside() {
        val inside = TestInk.line("in", 10f, 10f, 90f, 10f)
        val half = TestInk.line("half", 50f, 50f, 150f, 50f)
        val mostly = TestInk.line("mostly", 10f, 80f, 120f, 80f) // 80/110 inside
        val hl = TestInk.line("hl", 20f, 30f, 80f, 30f, pen = PenType.HIGHLIGHTER)
        val c = PageContent(strokes = listOf(inside, half, mostly, hl))
        val idx = ItemIndex.build(c, IdentityHashMap())
        val square = LassoSelector.rectPolygon(0f, 0f, 100f, 100f)
        assertEquals(setOf("in", "mostly", "hl"), LassoSelector.select(c, idx, square, LassoFilter()))
        assertEquals(setOf("in", "mostly"), LassoSelector.select(c, idx, square, LassoFilter(highlighter = false)))
    }

    @Test fun lassoExpandsGroups() {
        val a = TestInk.line("a", 10f, 10f, 20f, 10f).copy(groupId = "g")
        val b = TestInk.line("b", 500f, 500f, 520f, 500f).copy(groupId = "g")
        val c = PageContent(strokes = listOf(a, b))
        val idx = ItemIndex.build(c, IdentityHashMap())
        assertEquals(setOf("a", "b"), LassoSelector.select(c, idx, LassoSelector.rectPolygon(0f, 0f, 50f, 50f), LassoFilter()))
    }

    @Test fun shapeBuilderVertices() {
        assertTrue(ShapeBuilder.points(ShapeKind.ARROW, 5f, 6f, 1f, 2f).contentEquals(floatArrayOf(5f, 6f, 1f, 2f)))
        assertTrue(ShapeBuilder.points(ShapeKind.RECTANGLE, 50f, 60f, 10f, 20f).contentEquals(floatArrayOf(10f, 20f, 50f, 60f)))
        val tri = ShapeBuilder.points(ShapeKind.TRIANGLE, 0f, 0f, 100f, 50f)
        assertTrue(tri.contentEquals(floatArrayOf(50f, 0f, 100f, 50f, 0f, 50f)))
        assertEquals(12, ShapeBuilder.points(ShapeKind.POLYGON, 0f, 0f, 100f, 100f).size)
        val star = ShapeBuilder.points(ShapeKind.STAR, 0f, 0f, 100f, 100f)
        assertEquals(20, star.size)
        assertEquals(50f, star[0], 1e-3f); assertEquals(0f, star[1], 1e-3f)
        assertTrue(ShapeBuilder.isTooSmall(ShapeKind.LINE, 0f, 0f, 1f, 1f, 4f))
        assertFalse(ShapeBuilder.isTooSmall(ShapeKind.RECTANGLE, 0f, 0f, 10f, 1f, 4f))
    }

    @Test fun itemTransformsKeepIdentityFields() {
        val moved = Items.translate(stroke, 5f, -5f) as com.inkwell.notes.core.model.Stroke
        assertEquals(5f, moved.points[0], 0f); assertEquals(95f, moved.points[1], 0f)
        assertEquals(stroke.id, moved.id)
        val hlStroke = TestInk.line("h", 0f, 0f, 1f, 1f, pen = PenType.HIGHLIGHTER).copy(color = 0x73F2D04B)
        val recolored = Items.recolor(hlStroke, 0xFF2952CC.toInt()) as com.inkwell.notes.core.model.Stroke
        assertEquals(0x732952CC, recolored.color)
        val faded = Items.withOpacity(stroke, 0.5f) as com.inkwell.notes.core.model.Stroke
        assertEquals(128, (faded.color ushr 24) and 0xFF)
        assertEquals(0.5f, (Items.withOpacity(image, 0.5f) as ImageItem).opacity, 0f)
        assertEquals(4f, (Items.withWidth(stroke, 4f) as com.inkwell.notes.core.model.Stroke).width, 0f)
    }
}
