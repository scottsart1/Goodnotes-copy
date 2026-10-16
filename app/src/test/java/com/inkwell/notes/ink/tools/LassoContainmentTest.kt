package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.LassoFilter
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.model.ItemIndex
import com.inkwell.notes.ink.recognize.SyntheticInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class LassoContainmentTest {
    private val square = LassoSelector.rectPolygon(0f, 0f, 100f, 100f)

    private fun select(c: PageContent, poly: FloatArray = square, filter: LassoFilter = LassoFilter()): Set<String> =
        LassoSelector.select(c, ItemIndex.build(c, IdentityHashMap()), poly, filter)

    @Test fun strokesNeedSixtyPercentOfTheirSamplesInside() {
        // 21 evenly spaced samples each.
        val in55 = TestInk.line("s55", -44f, 50f, 56f, 50f)   // 12/21 inside = 57%
        val in65 = TestInk.line("s65", -34f, 30f, 66f, 30f)   // 14/21 = 67%
        val c = PageContent(strokes = listOf(in55, in65))
        assertEquals(setOf("s65"), select(c))
    }

    @Test fun boxesAreSelectedByTheirCentre() {
        val textIn = TextItem("tIn", 62f, 60f, 80f, 30f, "x")          // centre (102, 75): outside, box mostly inside
        val textCentre = TextItem("tC", 40f, 40f, 100f, 30f, "x")      // centre (90, 55): inside, box mostly outside
        val image = ImageItem("img", "a", -60f, 20f, 100f, 40f)        // centre (-10, 40): outside
        val sticky = StickyItem("st", 50f, 50f, 40f, 40f)              // centre (70, 70): inside
        val rotated = TextItem("rot", 70f, 10f, 100f, 20f, "x", rotation = 90f) // centre (120, 20): outside
        val c = PageContent(texts = listOf(textIn, textCentre, rotated), images = listOf(image), stickies = listOf(sticky))
        assertEquals(setOf("tC", "st"), select(c))
    }

    @Test fun shapesAreSampledAlongTheirWholeOutline() {
        // A rectangle whose left half is inside: half of its outline is not enough.
        val r = ShapeItem("r", ShapeKind.RECTANGLE, floatArrayOf(10f, 10f, 190f, 90f), 0)
        val inside = ShapeItem("in", ShapeKind.ELLIPSE, floatArrayOf(10f, 10f, 80f, 60f), 0)
        val c = PageContent(shapes = listOf(r, inside))
        assertEquals(setOf("in"), select(c))
    }

    @Test fun filterLimitsTypes() {
        val ink = TestInk.line("ink", 10f, 10f, 90f, 10f)
        val hl = TestInk.line("hl", 10f, 20f, 90f, 20f, pen = PenType.HIGHLIGHTER)
        val shape = ShapeItem("sh", ShapeKind.LINE, floatArrayOf(10f, 40f, 90f, 40f), 0)
        val text = TextItem("tx", 20f, 50f, 40f, 20f, "x")
        val img = ImageItem("im", "a", 20f, 70f, 20f, 20f)
        val conn = ConnectorItem("cn", fromX = 10f, fromY = 95f, toX = 90f, toY = 95f)
        val c = PageContent(strokes = listOf(ink, hl), shapes = listOf(shape), texts = listOf(text), images = listOf(img), connectors = listOf(conn))
        assertEquals(setOf("ink", "hl", "sh", "tx", "im", "cn"), select(c))
        assertEquals(setOf("ink"), select(c, filter = LassoFilter(highlighter = false, shapes = false, images = false, text = false)))
        assertEquals(setOf("tx", "im"), select(c, filter = LassoFilter(handwriting = false, highlighter = false, shapes = false)))
    }

    @Test fun freeformLoopSelectsWhatItSurrounds() {
        val loop = SyntheticInk(3).ellipse(100f, 100f, 90f, 60f)
        val inside = TestInk.line("in", 60f, 100f, 140f, 100f)
        val outside = TestInk.line("out", 60f, 200f, 140f, 200f)
        val crossing = TestInk.line("cross", 100f, 90f, 100f, 250f) // under half inside
        val c = PageContent(strokes = listOf(inside, outside, crossing))
        val sel = select(c, loop)
        assertTrue("in" in sel)
        assertTrue("out" !in sel)
        assertTrue("cross" !in sel)
    }

    @Test fun groupsAreSelectedWhole() {
        val a = TestInk.line("a", 10f, 10f, 50f, 10f).copy(groupId = "g")
        val b = ShapeItem("b", ShapeKind.RECTANGLE, floatArrayOf(300f, 300f, 400f, 400f), 0, groupId = "g")
        val c = PageContent(strokes = listOf(a), shapes = listOf(b))
        assertEquals(setOf("a", "b"), select(c))
        // The gesture variant does not expand groups.
        assertEquals(setOf("a"), LassoSelector.inside(c, ItemIndex.build(c, IdentityHashMap()), square, LassoFilter()))
    }
}
