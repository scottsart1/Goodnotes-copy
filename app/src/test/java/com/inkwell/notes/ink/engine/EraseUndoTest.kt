package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.EraserFilter
import com.inkwell.notes.core.model.EraserMode
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.ItemIndex
import com.inkwell.notes.ink.tools.EraserEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

/**
 * Undo/redo of erase gestures across several pages: a live erase session produces one command
 * whose diffs restore the exact original strokes (same instances, same positions) and whose redo
 * re-applies the pieces.
 */
class EraseUndoTest {
    private var n = 0
    private val engine = EraserEngine { "piece-${n++}" }

    private class Target(val pages: MutableMap<String, PageContent>) : CommandTarget {
        val changed = HashMap<String, Bounds?>()
        override fun contentOf(pageId: String): PageContent? = pages[pageId]
        override fun setContent(pageId: String, content: PageContent, changed: Bounds?) { pages[pageId] = content; this.changed[pageId] = changed }
    }

    private fun erase(c: PageContent, mode: EraserMode, x: Float, y: Float, r: Float = 3f): PageContent =
        engine.apply(c, ItemIndex.build(c, IdentityHashMap()), mode, EraserFilter.ALL, x, y, x, y, r, Bounds())

    @Test fun precisionEraseUndoRestoresTheOriginalStrokeInPlace() {
        val a = TestInk.line("a", 0f, 0f, 100f, 0f, z = 1)
        val b = TestInk.line("b", 0f, 10f, 100f, 10f, z = 2).copy(audio = AudioAnchor("rec", 5))
        val c = TestInk.line("c", 0f, 20f, 100f, 20f, z = 3)
        val before = PageContent(strokes = listOf(a, b, c))
        // A gesture of several moves splits b twice.
        var after = erase(before, EraserMode.PRECISION, 30f, 10f)
        after = erase(after, EraserMode.PRECISION, 70f, 10f)
        assertEquals(listOf("a", "piece-0", "piece-2", "piece-3", "c"), after.strokes.map { it.id })
        val cmd = ContentDiffCommand.of("Erase", mapOf("p1" to (before to after)))!!
        val target = Target(mutableMapOf("p1" to after))
        assertTrue(cmd.undo(target))
        val restored = target.pages.getValue("p1")
        assertEquals(listOf("a", "b", "c"), restored.strokes.map { it.id })
        assertSame(b, restored.strokes[1])
        assertEquals(AudioAnchor("rec", 5), restored.strokes[1].audio)
        assertFalse(target.changed.getValue("p1")!!.isEmpty)
        assertTrue(cmd.redo(target))
        assertEquals(after.strokes.map { it.id }, target.pages.getValue("p1").strokes.map { it.id })
        // Undo again after redo: still exact.
        assertTrue(cmd.undo(target))
        assertEquals(listOf("a", "b", "c"), target.pages.getValue("p1").strokes.map { it.id })
    }

    @Test fun oneCommandCanSpanPagesAndSkipsPagesThatAreGone() {
        val p1Before = PageContent(strokes = listOf(TestInk.line("x", 0f, 0f, 100f, 0f)))
        val p2Before = PageContent(strokes = listOf(TestInk.line("y", 0f, 0f, 100f, 0f), TestInk.line("hl", 0f, 0f, 100f, 0f, pen = PenType.HIGHLIGHTER)))
        val p1After = erase(p1Before, EraserMode.STROKE, 50f, 0f)
        val p2After = erase(p2Before, EraserMode.STROKE, 50f, 0f)
        assertTrue(p1After.isEmpty && p2After.isEmpty)
        val cmd = ContentDiffCommand.of("Erase", mapOf("p1" to (p1Before to p1After), "p2" to (p2Before to p2After)))!!
        assertEquals(setOf("p1", "p2"), cmd.pageIds)
        val target = Target(mutableMapOf("p1" to p1After, "p2" to p2After))
        assertTrue(cmd.undo(target))
        assertEquals(listOf("x"), target.pages.getValue("p1").strokes.map { it.id })
        assertEquals(listOf("y", "hl"), target.pages.getValue("p2").strokes.map { it.id })
        // Page 2 deleted meanwhile: redo still applies to page 1 and reports success.
        target.pages.remove("p2")
        assertTrue(cmd.redo(target))
        assertTrue(target.pages.getValue("p1").isEmpty)
        assertNull(target.pages["p2"])
        // Both pages gone: nothing applies.
        target.pages.remove("p1")
        assertFalse(cmd.undo(target))
    }

    @Test fun eraseThenNewStrokeThenUndoTwiceRestoresOrderAndZ() {
        val a = TestInk.line("a", 0f, 0f, 100f, 0f, z = 1)
        val b = TestInk.line("b", 0f, 10f, 100f, 10f, z = 2)
        val v0 = PageContent(strokes = listOf(a, b))
        val v1 = erase(v0, EraserMode.STROKE, 50f, 0f)
        val eraseCmd = ContentDiffCommand.of("Erase", mapOf("p" to (v0 to v1)))!!
        val fresh = TestInk.line("new", 0f, 30f, 100f, 30f, z = v1.maxZ() + 1)
        val v2 = ContentOps.add(v1, listOf(fresh))
        val inkCmd = ContentDiffCommand.of("Ink", mapOf("p" to (v1 to v2)))!!
        val stack = UndoStack<InkCommand>()
        stack.push(eraseCmd); stack.push(inkCmd)
        val target = Target(mutableMapOf("p" to v2))
        stack.popUndo()!!.undo(target)
        assertEquals(listOf("b"), target.pages.getValue("p").strokes.map { it.id })
        stack.popUndo()!!.undo(target)
        assertEquals(listOf("a", "b"), target.pages.getValue("p").strokes.map { it.id })
        assertEquals(listOf(1, 2), target.pages.getValue("p").strokes.map { it.z })
        stack.popRedo()!!.redo(target)
        stack.popRedo()!!.redo(target)
        assertEquals(listOf("b", "new"), target.pages.getValue("p").strokes.map { it.id })
        assertFalse(stack.canRedo)
    }
}
