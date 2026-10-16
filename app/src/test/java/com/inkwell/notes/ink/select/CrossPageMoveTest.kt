package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dropping a dragged selection on another page (the content side of SelectionController.moveToPage). */
class CrossPageMoveTest {
    private val sticky = StickyItem("sticky", 100f, 100f, 100f, 100f, z = 1)          // stays
    private val text = TextItem("text", 400f, 100f, 100f, 50f, "t", z = 2)           // moves
    private val ink = TestInk.line("ink", 400f, 300f, 500f, 300f, z = 3)             // moves
    private val sel = ConnectorItem("sel", fromItemId = "sticky", toItemId = "text", fromX = 0f, fromY = 0f, toX = 0f, toY = 0f, z = 4) // selected
    private val along = ConnectorItem("along", fromItemId = "text", toItemId = "ink", z = 5)                    // unselected, both ends move
    private val stay = ConnectorItem("stay", fromItemId = "sticky", toItemId = "ink", z = 6)                    // unselected, one end moves
    private val before = PageContent(strokes = listOf(ink), texts = listOf(text), stickies = listOf(sticky), connectors = listOf(sel, along, stay))
    private val target = PageContent(strokes = listOf(TestInk.line("other", 0f, 0f, 10f, 0f, z = 7)))

    private val drag = Affine.translate(0f, 600f)
    /** Source page sits above the target in a vertical layout: page y 600 on the source is y 0 on the target (gap ignored). */
    private val toTarget = Affine.translate(0f, -600f)

    private fun compute(): CrossPageMove.Result {
        val items = listOf<Any>(text, ink, sel)
        val baked = items.map { TransformBaker.bake(it, drag) }
        return CrossPageMove.compute(before, target, items, baked, listOf(along, stay), drag, toTarget, firstZ = 8)
    }

    @Test fun selectedAndFullyAttachedConnectorsMoveAndTheRestStays() {
        val r = compute()
        assertEquals(setOf("sticky", "stay"), ContentOps.allItems(r.source).map { Items.id(it) }.toSet())
        assertEquals(setOf("other", "text", "ink", "sel", "along"), ContentOps.allItems(r.target).map { Items.id(it) }.toSet())
        // Positions: the drag moved everything 600 down, the page change 600 back up.
        val movedText = r.target.texts.single()
        assertEquals(400f, movedText.x, 1e-3f); assertEquals(100f, movedText.y, 1e-3f)
        // Stacked above the target's content in their relative order.
        val zs = r.moved.map { Items.z(it) }
        assertEquals(zs.sorted(), zs)
        assertTrue(zs.min() > 7)
        assertEquals(listOf("text", "ink", "sel", "along"), r.moved.map { Items.id(it) })
    }

    @Test fun selectedConnectorAttachedToAnItemLeftBehindIsDetachedAtItsLivePosition() {
        // Before the drag the connector ran from the sticky's right edge midpoint (200, 150) to the text box.
        val live = ItemGeometry.connectorEndpoints(sel, before, FloatArray(4))
        assertEquals(200f, live[0], 1e-3f); assertEquals(150f, live[1], 1e-3f)
        val r = compute()
        val moved = r.target.connectors.single { it.id == "sel" }
        // No reference to an item on another page; the free end is where the sticky's edge was,
        // carried along with the selection and mapped onto the target page.
        assertNull(moved.fromItemId)
        assertEquals("text", moved.toItemId)
        assertEquals(200f, moved.fromX, 1e-3f)
        assertEquals(150f, moved.fromY, 1e-3f)
        val e = ItemGeometry.connectorEndpoints(moved, r.target, FloatArray(4))
        assertEquals(200f, e[0], 1e-3f); assertEquals(150f, e[1], 1e-3f)
        // The other end still follows the text box.
        assertEquals(400f, e[2], 1e-3f); assertEquals(125f, e[3], 1e-3f)
    }

    @Test fun unselectedConnectorWithOneMovingEndStaysWithAFrozenEnd() {
        val r = compute()
        val kept = r.source.connectors.single { it.id == "stay" }
        assertEquals("sticky", kept.fromItemId)
        assertNull(kept.toItemId)
        // Frozen where the ink's edge was before the drag: the stroke's bounds (400..500, 300) seen from the sticky.
        val wasAt = ItemGeometry.connectorEndpoints(stay, before, FloatArray(4))
        assertEquals(wasAt[2], kept.toX, 1e-3f); assertEquals(wasAt[3], kept.toY, 1e-3f)
        // And the one that went along still binds both of its items on the target.
        val moved = r.target.connectors.single { it.id == "along" }
        assertEquals("text", moved.fromItemId); assertEquals("ink", moved.toItemId)
        assertNotNull(ContentOps.find(r.target, "text")); assertNotNull(ContentOps.find(r.target, "ink"))
    }

    @Test fun movingBetweenPagesOfDifferentRotationTurnsItemsToKeepTheirLook() {
        // Source page rotated 90 degrees clockwise (600 x 800), target unrotated at the same spot on screen.
        val src = FloatArray(6); com.inkwell.notes.ink.viewport.PageRotation.affine(600f, 800f, 90, src)
        val toTgt = Affine.of(src) // source page -> displayed == target page coordinates
        val items = listOf<Any>(text, ink)
        val r = CrossPageMove.compute(before, PageContent.EMPTY, items, items, emptyList(), Affine.IDENTITY, toTgt, 1)
        val t = r.target.texts.single()
        assertEquals(90f, t.rotation, 1e-3f)
        val s = r.target.strokes.single() as Stroke
        // (400, 300) displayed on a 90-degree page is (800 - 300, 400) = (500, 400).
        assertEquals(500f, s.points[0], 1e-3f); assertEquals(400f, s.points[1], 1e-3f)
        assertEquals(ink.width, s.width, 1e-5f)
    }
}
