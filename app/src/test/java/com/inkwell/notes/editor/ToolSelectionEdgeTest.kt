package com.inkwell.notes.editor

import com.inkwell.notes.core.model.EraserFilter
import com.inkwell.notes.core.model.EraserMode
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.editor.logic.ToolSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S Pen side button, as the canvas reports it: HOLD mode sends temporary tools, TOGGLE
 * mode sends persistent requests (ERASER, then the last non-eraser tool). Whatever happens,
 * the pen configuration must come back untouched.
 */
class ToolSelectionEdgeTest {
    private val redBrush = PenConfig(PenType.BRUSH, 0xFFC93A2E.toInt(), 3.2f, 0.8f, 0.1f, 0.9f)
    private val eraser = com.inkwell.notes.core.model.EraserConfig(EraserMode.SEGMENT, EraserFilter.HIGHLIGHTER_ONLY, 24f)
    private val start = ToolSelection.initial(ToolState(tool = ToolType.PEN, pen = redBrush, eraser = eraser))

    @Test
    fun `holding the button mid-session keeps the selected eraser mode and filter`() {
        val held = start.withTemporary(ToolType.ERASER)
        // The eraser the side button uses is the configured one (segment, highlighter only).
        assertEquals(eraser, held.state.eraser)
        assertEquals(ToolType.ERASER, held.effectiveTool)
        assertEquals(ToolType.PEN, held.tool)
    }

    @Test
    fun `changing the eraser options while the button is held does not disturb the pen`() {
        val held = start.withTemporary(ToolType.ERASER)
        val sized = held.updateEraser { it.copy(sizeDp = 40f) }
        assertEquals(40f, sized.state.eraser.sizeDp)
        assertEquals(redBrush, sized.state.pen)
        assertEquals(ToolType.PEN, sized.tool)
        val released = sized.withTemporary(null)
        assertNull(released.temporary)
        assertEquals(ToolType.PEN, released.effectiveTool)
        assertEquals(redBrush, released.state.pen)
    }

    @Test
    fun `toggle mode as the canvas drives it goes to the eraser and back to the exact tool`() {
        var s = start.select(ToolType.HIGHLIGHTER).updatePen(ToolType.HIGHLIGHTER) { it.copy(width = 20f) }
        val before = s.state
        // The canvas asks for ERASER on the first press...
        s = s.onToolChangeRequested(ToolType.ERASER)
        assertEquals(ToolType.ERASER, s.tool)
        assertEquals(ToolType.HIGHLIGHTER, s.returnTool)
        // ...and for the last non-eraser tool on the second press.
        s = s.onToolChangeRequested(ToolType.HIGHLIGHTER)
        assertEquals(before, s.state)
        assertEquals(20f, s.state.highlighter.width)
    }

    @Test
    fun `selecting the eraser from the toolbar while the button is held still remembers the pen`() {
        val held = start.withTemporary(ToolType.ERASER)
        val erasing = held.select(ToolType.ERASER)
        assertEquals(ToolType.PEN, erasing.returnTool)
        val released = erasing.withTemporary(null)
        assertEquals(ToolType.ERASER, released.tool)
        assertEquals(ToolType.PEN, released.toggleEraser().tool)
        assertEquals(redBrush, released.toggleEraser().state.pen)
    }

    @Test
    fun `applying a preset while erasing makes the preset tool the return tool`() {
        val preset = PenPreset("p", "Graphite", ToolType.PENCIL, PenConfig.DefaultPencil)
        val s = start.select(ToolType.ERASER).applyPreset(preset)
        assertEquals(ToolType.PENCIL, s.tool)
        assertEquals(ToolType.PENCIL, s.returnTool)
        assertEquals(ToolType.PENCIL, s.toggleEraser().toggleEraser().tool)
    }

    @Test
    fun `temporary tool is display only and never equal to the selected tool`() {
        val lasso = start.select(ToolType.LASSO).withTemporary(ToolType.LASSO)
        assertFalse(lasso.isTemporarilyActive(ToolType.LASSO))
        assertTrue(start.withTemporary(ToolType.LASSO).isTemporarilyActive(ToolType.LASSO))
        assertEquals(start, start.withTemporary(null))
    }

    @Test
    fun `lasso updates keep every other tool configuration`() {
        val s = start.updateLasso(mode = com.inkwell.notes.core.model.LassoMode.RECTANGLE, filter = start.state.lassoFilter.copy(highlighter = false))
        assertEquals(redBrush, s.state.pen)
        assertEquals(eraser, s.state.eraser)
        assertEquals(ToolType.PEN, s.tool)
        assertFalse(s.state.lassoFilter.highlighter)
    }
}
