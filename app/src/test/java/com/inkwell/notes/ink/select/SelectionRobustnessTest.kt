package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Degenerate selections and transforms must never produce non-finite item data. */
class SelectionRobustnessTest {
    @Test fun edgeHandleOnABoxWithoutExtentIsANoOp() {
        val flat = Bounds(100f, 100f, 100f, 200f)
        assertSame(Affine.IDENTITY, SelectionMath.scaleTransform(Handle.RIGHT, flat, 100f, 150f, 150f, 150f, 4f))
        assertSame(Affine.IDENTITY, SelectionMath.scaleTransform(Handle.LEFT, flat, 100f, 150f, 50f, 150f, 4f))
        val thin = Bounds(100f, 100f, 300f, 100f)
        assertSame(Affine.IDENTITY, SelectionMath.scaleTransform(Handle.TOP, thin, 200f, 100f, 200f, 50f, 4f))
        // Corners still scale uniformly along the axis that has extent.
        val c = SelectionMath.scaleTransform(Handle.BOTTOM_RIGHT, flat, 100f, 200f, 100f, 300f, 4f)
        assertTrue(c.a.isFinite() && c.tx.isFinite() && c.d.isFinite())
        assertEquals(2f, c.d, 1e-4f)
    }

    @Test fun bakingEveryItemTypeThroughExtremeTransformsStaysFinite() {
        val items = listOf<Any>(
            TestInk.line("s", 0f, 0f, 100f, 0f),
            ShapeItem("r", ShapeKind.ROUNDED_RECTANGLE, floatArrayOf(0f, 0f, 50f, 20f), 0, rotation = 30f),
            ShapeItem("e", ShapeKind.ELLIPSE, floatArrayOf(0f, 0f, 0f, 0f), 0),
            ShapeItem("l", ShapeKind.ARROW, floatArrayOf(0f, 0f, 10f, 10f), 0),
            TextItem("t", 0f, 0f, 0f, 0f, "x"),
            ImageItem("i", "a", 10f, 10f, 20f, 20f, rotation = 45f),
            StickyItem("k", 0f, 0f, 1f, 1f),
        )
        val transforms = listOf(
            Affine.scale(1e-6f, 1e-6f, 0f, 0f),
            Affine.scale(1e6f, 1f, 50f, 50f),
            Affine.rotate(3.14159f, 0f, 0f).then(Affine.scale(0.001f, 1000f, 0f, 0f)),
            Affine.translate(1e7f, -1e7f),
        )
        for (m in transforms) for (item in items) {
            val b = TransformBaker.bake(item, m)
            val bounds = ItemGeometry.bounds(b, Bounds(), PageContent())
            assertTrue("$item through $m gave $bounds", bounds.isEmpty || (bounds.left.isFinite() && bounds.right.isFinite() && bounds.top.isFinite() && bounds.bottom.isFinite()))
            when (b) {
                is Stroke -> { assertTrue(b.width in 0.1f..200f); assertTrue(b.points.all { it.isFinite() }) }
                is ShapeItem -> { assertTrue(b.strokeWidth in 0.1f..200f); assertTrue(b.rotation.isFinite()); assertTrue(b.points.all { it.isFinite() }) }
                is TextItem -> { assertTrue(b.fontSizePt in 2f..400f); assertTrue(b.width >= 0.5f && b.height >= 0.5f) }
                is ImageItem -> assertTrue(b.width >= 0.5f && b.height >= 0.5f && b.rotation.isFinite())
                is StickyItem -> assertTrue(b.width >= 0.5f && b.height >= 0.5f)
            }
        }
    }

    @Test fun clipboardCopyOfAGroupKeepsTheGroupAndPasteRemapsIt() {
        val a = TestInk.line("a", 10f, 10f, 50f, 10f).copy(groupId = "g")
        val b = TestInk.rect("b", 100f, 100f, 200f, 200f).copy(groupId = "g")
        val page = PageContent(strokes = listOf(a), shapes = listOf(b))
        val frag = SelectionClipboard.copy(page, setOf("a", "b"))!!
        assertEquals("g", frag.strokes.single().groupId)
        assertEquals("g", frag.shapes.single().groupId)
        // The fragment starts at the origin (stroke width padding included).
        val fb = SelectionClipboard.boundsOf(frag)!!
        assertEquals(0f, fb.left, 1e-4f); assertEquals(0f, fb.top, 1e-4f)
        // Items of the fragment keep their relative placement (rect corner minus first ink sample).
        val sb = ItemGeometry.bounds(frag.strokes.single(), Bounds())
        val rb = ItemGeometry.bounds(frag.shapes.single(), Bounds())
        assertEquals(90f, frag.shapes.single().points[0] - frag.strokes.single().points[0], 1e-3f)
        assertEquals(90f, frag.shapes.single().points[1] - frag.strokes.single().points[1], 1e-3f)
        // Translating a fragment moves every item by the same vector.
        val moved = SelectionClipboard.translate(frag, 5f, 7f)
        assertEquals(sb.left + 5f, ItemGeometry.bounds(moved.strokes.single(), Bounds()).left, 1e-3f)
        assertEquals(rb.top + 7f, ItemGeometry.bounds(moved.shapes.single(), Bounds()).top, 1e-3f)
        // Group ids are shared between the copies, as paste expects when it regenerates them.
        assertEquals(1, ContentOps.allItems(moved).mapNotNull { Items.groupId(it) }.distinct().size)
    }
}
