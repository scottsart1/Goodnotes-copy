package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.EraserConfig
import com.inkwell.notes.core.model.EraserFilter
import com.inkwell.notes.core.model.EraserMode
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.model.ItemIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

/** The eraser tool on a fake host: size units, config at pen-down, cancel and the engine's modes. */
class EraserToolTest {
    // 2 px per point, density 2: 16 dp = 32 px = 16 pt diameter, 8 pt radius.
    private val ink = TestInk.line("ink", 0f, 50f, 100f, 50f, width = 2f)
    private val hl = TestInk.line("hl", 0f, 50f, 100f, 50f, pen = PenType.HIGHLIGHTER, width = 12f)
    private val far = TestInk.line("far", 0f, 60f, 100f, 60f, width = 2f)

    private fun host(vararg strokes: com.inkwell.notes.core.model.Stroke, eraser: EraserConfig = EraserConfig()): FakeToolHost =
        FakeToolHost(scale = 2f, density = 2f, content = PageContent(strokes = strokes.toList())).also {
            it.toolState = ToolState(tool = ToolType.ERASER, eraser = eraser)
        }

    @Test fun sizeIsScreenDpConvertedToPagePoints() {
        // Stroke "far" lies 10 pt below the eraser centre: outside an 8 pt radius (+1 pt half width), inside 12 pt.
        val h = host(ink, far, eraser = EraserConfig(EraserMode.STROKE, EraserFilter.ALL, sizeDp = 16f))
        val tool = EraserTool()
        tool.onDown(host = h, e = h.down(100f, 100f, 0L)) // page (50, 50)
        tool.onUp(h, h.move(100f, 100f, 8L))
        assertEquals(listOf("far"), h.content.strokes.map { it.id })
        assertEquals(listOf("Erase"), h.commits)
        val big = host(ink, far, eraser = EraserConfig(EraserMode.STROKE, EraserFilter.ALL, sizeDp = 24f))
        tool.onDown(big, big.down(100f, 100f, 0L))
        tool.onUp(big, big.move(100f, 100f, 8L))
        assertTrue(big.content.strokes.isEmpty())
    }

    @Test fun zoomDoesNotChangeTheOnScreenEraserSize() {
        // Zoomed in 4x: the same 16 dp eraser covers only 4 pt radius, so "far" (10 pt away) and even a
        // stroke 6 pt away survive.
        val near = TestInk.line("near", 0f, 56f, 100f, 56f, width = 2f)
        val h = host(ink, near, far, eraser = EraserConfig(EraserMode.STROKE, EraserFilter.ALL, sizeDp = 16f))
        h.scale = 8f
        val tool = EraserTool()
        tool.onDown(h, h.down(400f, 400f, 0L)) // page (50, 50)
        tool.onUp(h, h.move(400f, 400f, 8L))
        assertEquals(listOf("near", "far"), h.content.strokes.map { it.id })
    }

    @Test fun honoursTheSelectedModeAndFilterAtPenDown() {
        val h = host(hl, ink, eraser = EraserConfig(EraserMode.PRECISION, EraserFilter.HIGHLIGHTER_ONLY, sizeDp = 8f))
        val tool = EraserTool()
        tool.onDown(h, h.down(100f, 100f, 0L))
        // Changing the config mid-gesture must not change what this gesture does.
        h.toolState = h.toolState.copy(eraser = EraserConfig(EraserMode.STROKE, EraserFilter.ALL))
        tool.onMove(h, h.move(110f, 100f, 8L))
        tool.onUp(h, h.move(120f, 100f, 16L))
        val ids = h.content.strokes.map { it.id }
        assertTrue("handwriting kept: $ids", "ink" in ids)
        assertFalse("highlighter split: $ids", "hl" in ids)
        assertEquals(2, h.content.strokes.count { it.pen == PenType.HIGHLIGHTER })
    }

    @Test fun cancelRestoresEverythingErasedInTheGesture() {
        val h = host(ink, hl, far, eraser = EraserConfig(EraserMode.STROKE, EraserFilter.ALL, sizeDp = 16f))
        val tool = EraserTool()
        tool.onDown(h, h.down(100f, 100f, 0L))
        tool.onMove(h, h.move(100f, 120f, 8L))
        assertTrue(h.content.strokes.isEmpty())
        tool.onCancel(h)
        assertEquals(listOf("ink", "hl", "far"), h.content.strokes.map { it.id })
        assertTrue(h.commits.isEmpty())
        assertFalse(tool.isActive)
    }

    @Test fun aGestureThatErasesNothingLeavesNoUndoStep() {
        val h = host(ink, eraser = EraserConfig(EraserMode.STROKE, EraserFilter.ALL, sizeDp = 8f))
        val tool = EraserTool()
        tool.onDown(h, h.down(100f, 400f, 0L))
        tool.onUp(h, h.move(120f, 400f, 8L))
        assertTrue(h.commits.isEmpty())
    }

    @Test fun sweepErasesEverythingAlongThePathNotJustAtSamples() {
        // Two vertical strokes between consecutive eraser samples 40 pt apart.
        val v1 = TestInk.line("v1", 30f, 0f, 30f, 100f)
        val v2 = TestInk.line("v2", 60f, 0f, 60f, 100f)
        val h = host(v1, v2, eraser = EraserConfig(EraserMode.STROKE, EraserFilter.ALL, sizeDp = 4f))
        val tool = EraserTool()
        tool.onDown(h, h.down(20f, 100f, 0L))
        tool.onUp(h, h.move(180f, 100f, 8L))
        assertTrue(h.content.strokes.isEmpty())
    }

    // ---- Engine-level mode behaviour

    private var n = 0
    private val engine = EraserEngine { "piece-${n++}" }

    private fun erase(content: PageContent, mode: EraserMode, x: Float, y: Float, r: Float = 2f): PageContent =
        engine.apply(content, ItemIndex.build(content, IdentityHashMap()), mode, EraserFilter.ALL, x, y, x, y, r, Bounds())

    /** A hand-drawn box whose last sample returns exactly to its first (the usual way people close a box). */
    private val closedBox = TestInk.stroke(
        "box", 0f, 0f, 25f, 0f, 50f, 0f, 50f, 25f, 50f, 50f, 25f, 50f, 0f, 50f, 0f, 25f, 0f, 0f,
    )

    @Test fun segmentModeOnAClosedBoxRemovesOneSide() {
        val out = erase(PageContent(strokes = listOf(closedBox)), EraserMode.SEGMENT, 25f, 0f)
        val xs = out.strokes.flatMap { TestInk.xs(it) }
        assertFalse("box vanished", out.strokes.isEmpty())
        // The top side is gone; the other three remain (every remaining sample is on x = 0, x = 50 or y = 50).
        for (s in out.strokes) for (i in 0 until s.pointCount) {
            val x = s.points[i * 5]; val y = s.points[i * 5 + 1]
            assertTrue("($x,$y) is on the erased top side", y > 0.5f || x < 0.5f || x > 49.5f)
        }
        assertTrue(xs.any { it > 49f })
    }

    @Test fun segmentModeOnABoxThatOvershootsItsStartRemovesOneSide() {
        // The closing stroke runs 4 pt past the start, crossing the first side.
        val box = TestInk.stroke("box", 0f, 0f, 25f, 0f, 50f, 0f, 50f, 25f, 50f, 50f, 25f, 50f, 0f, 50f, 0f, 25f, 0f, -4f)
        val out = erase(PageContent(strokes = listOf(box)), EraserMode.SEGMENT, 50f, 25f)
        var remaining = 0f
        for (s in out.strokes) remaining += com.inkwell.notes.ink.geometry.Geo.polylineLength(s.points, 5)
        // Perimeter 204 minus one 50 pt side (give or take the overshoot stub).
        assertTrue("remaining $remaining", remaining > 140f && remaining < 160f)
    }

    @Test fun segmentModeOnABoxCrossedByOneLineStillRemovesJustTheTouchedSide() {
        val line = TestInk.line("line", 25f, -20f, 25f, 20f)
        val out = erase(PageContent(strokes = listOf(closedBox, line)), EraserMode.SEGMENT, 50f, 25f)
        assertTrue(out.strokes.any { it.id == "line" })
        var remaining = 0f
        for (s in out.strokes.filter { it.id != "line" }) remaining += com.inkwell.notes.ink.geometry.Geo.polylineLength(s.points, 5)
        assertEquals(150f, remaining, 1f)
    }
}
