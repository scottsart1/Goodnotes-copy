package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoAndContentTest {
    @Test fun undoStackBasics() {
        val s = UndoStack<String>(capacity = 3)
        assertFalse(s.canUndo); assertFalse(s.canRedo)
        s.push("a"); s.push("b")
        assertEquals("b", s.popUndo())
        assertTrue(s.canRedo)
        assertEquals("b", s.popRedo())
        assertEquals("b", s.popUndo())
        s.push("c") // clears redo
        assertFalse(s.canRedo)
        assertEquals(2, s.undoSize)
    }

    @Test fun undoStackIsBounded() {
        val s = UndoStack<Int>(capacity = 200)
        repeat(250) { s.push(it) }
        assertEquals(200, s.undoSize)
        var last = -1
        while (s.canUndo) last = s.popUndo()!!
        assertEquals(50, last)
        assertNull(s.popUndo())
        assertEquals(200, s.redoSize)
    }

    @Test fun undoStackRemoveIf() {
        val s = UndoStack<String>()
        s.push("p1"); s.push("p2"); s.push("p1b")
        s.popUndo()
        s.removeIf { it.startsWith("p1") }
        assertEquals(1, s.undoSize)
        assertFalse(s.canRedo)
    }

    private val a = TestInk.line("a", 0f, 0f, 10f, 0f, z = 1)
    private val b = TestInk.line("b", 0f, 5f, 10f, 5f, z = 1)
    private val c = TestInk.line("c", 0f, 9f, 10f, 9f, z = 1)
    private val t = TextItem("t", 0f, 0f, 50f, 20f, "hello", z = 2)
    private val before = PageContent(strokes = listOf(a, b, c), texts = listOf(t))

    @Test fun diffRestoresExactOrderBothWays() {
        val b2 = Items.recolor(b, 0xFFFF0000.toInt()) as com.inkwell.notes.core.model.Stroke
        val d = TestInk.line("d", 0f, 20f, 10f, 20f, z = 3)
        val after = PageContent(strokes = listOf(a, b2, d), texts = emptyList())
        val diff = ContentOps.diff("p", before, after)
        assertEquals(setOf("b", "c", "t"), diff.removed.map { Items.id(it.item) }.toSet())
        assertEquals(setOf("b", "d"), diff.added.map { Items.id(it.item) }.toSet())
        val back = ContentOps.applyBackward(after, diff)
        assertEquals(before.strokes.map { it.id }, back.strokes.map { it.id })
        assertSame(b, back.strokes[1])
        assertEquals(listOf("t"), back.texts.map { it.id })
        val fwd = ContentOps.applyForward(back, diff)
        assertEquals(after.strokes.map { it.id }, fwd.strokes.map { it.id })
        assertSame(b2, fwd.strokes[1])
        assertTrue(fwd.texts.isEmpty())
    }

    @Test fun diffOfIdenticalContentIsEmpty() {
        assertTrue(ContentOps.diff("p", before, before).isEmpty)
        assertTrue(ContentOps.diff("p", before, before.copy()).isEmpty)
    }

    @Test fun applyIsIdempotent() {
        val after = ContentOps.remove(before, setOf("b"))
        val diff = ContentOps.diff("p", before, after)
        val twice = ContentOps.applyBackward(ContentOps.applyBackward(after, diff), diff)
        assertEquals(listOf("a", "b", "c"), twice.strokes.map { it.id })
    }

    @Test fun addReplacesSameIdInPlace() {
        val b2 = Items.withZ(b, 9)
        val out = ContentOps.add(before, listOf(b2, TestInk.line("n", 0f, 0f, 1f, 1f)))
        assertEquals(listOf("a", "b", "c", "n"), out.strokes.map { it.id })
        assertEquals(9, out.strokes[1].z)
    }

    @Test fun fragmentAndFind() {
        val f = ContentOps.fragment(before, setOf("c", "t"))
        assertEquals(listOf("c"), f.strokes.map { it.id })
        assertEquals(listOf("t"), f.texts.map { it.id })
        assertSame(t, ContentOps.find(before, "t"))
        assertNull(ContentOps.find(before, "zzz"))
    }

    private class FakeTarget(var content: PageContent) : CommandTarget {
        var changed: Bounds? = null
        override fun contentOf(pageId: String): PageContent? = if (pageId == "p") content else null
        override fun setContent(pageId: String, content: PageContent, changed: Bounds?) { this.content = content; this.changed = changed }
    }

    @Test fun contentDiffCommandUndoRedo() {
        val after = ContentOps.remove(before, setOf("a"))
        val cmd = ContentDiffCommand.of("Erase", mapOf("p" to (before to after)))!!
        val target = FakeTarget(after)
        assertTrue(cmd.undo(target))
        assertEquals(listOf("a", "b", "c"), target.content.strokes.map { it.id })
        assertFalse(target.changed!!.isEmpty)
        assertTrue(cmd.redo(target))
        assertEquals(listOf("b", "c"), target.content.strokes.map { it.id })
        assertNull(ContentDiffCommand.of("noop", mapOf("p" to (before to before))))
        // A page that disappeared cannot be undone.
        val gone = ContentDiffCommand.of("x", mapOf("other" to (before to after)))!!
        assertFalse(gone.undo(target))
    }
}
