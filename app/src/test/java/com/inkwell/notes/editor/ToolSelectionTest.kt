package com.inkwell.notes.editor

import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.editor.logic.ToolLimits
import com.inkwell.notes.editor.logic.ToolSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSelectionTest {
    private val customPencil = PenConfig(PenType.PENCIL, 0xFF123456.toInt(), 2.4f, 0.9f, 0.1f, 0.8f)
    private val start = ToolSelection.initial(ToolState(tool = ToolType.PENCIL, pencil = customPencil))

    @Test
    fun `toggling the eraser returns to the exact previous pen`() {
        val erasing = start.toggleEraser()
        assertEquals(ToolType.ERASER, erasing.tool)
        val back = erasing.toggleEraser()
        assertEquals(ToolType.PENCIL, back.tool)
        assertEquals(customPencil, back.state.pencil)
        assertEquals(start.state, back.state)
    }

    @Test
    fun `holding the side button never changes the selected tool or its configuration`() {
        val held = start.withTemporary(ToolType.ERASER)
        assertEquals(ToolType.PENCIL, held.tool)
        assertEquals(ToolType.ERASER, held.effectiveTool)
        assertTrue(held.isTemporarilyActive(ToolType.ERASER))
        assertEquals(start.state, held.state)
        val released = held.withTemporary(null)
        assertEquals(start, released)
    }

    @Test
    fun `toggle mode requests restore the tool used before the eraser`() {
        val lasso = start.select(ToolType.LASSO)
        val erasing = lasso.onToolChangeRequested(ToolType.ERASER)
        assertEquals(ToolType.ERASER, erasing.tool)
        // A second ERASER request while erasing toggles back.
        assertEquals(ToolType.LASSO, erasing.onToolChangeRequested(ToolType.ERASER).tool)
        // An explicit request for a tool is honoured as is.
        assertEquals(ToolType.HIGHLIGHTER, erasing.onToolChangeRequested(ToolType.HIGHLIGHTER).tool)
    }

    @Test
    fun `return tool survives several eraser round trips`() {
        var s = start.select(ToolType.HIGHLIGHTER)
        repeat(3) { s = s.toggleEraser().toggleEraser() }
        assertEquals(ToolType.HIGHLIGHTER, s.tool)
        s = s.select(ToolType.ERASER).select(ToolType.ERASER)
        assertEquals(ToolType.HIGHLIGHTER, s.returnTool)
    }

    @Test
    fun `starting on the eraser returns to the pen`() {
        val s = ToolSelection.initial(ToolState(tool = ToolType.ERASER))
        assertEquals(ToolType.PEN, s.toggleEraser().tool)
    }

    @Test
    fun `whiteboard-only tools fall back to the pen`() {
        assertEquals(ToolType.PEN, ToolSelection.initial(ToolState(tool = ToolType.STICKY)).tool)
        assertEquals(ToolType.PEN, ToolSelection.initial(ToolState(tool = ToolType.CONNECTOR)).tool)
    }

    @Test
    fun `presets apply their tool and pen and are recognised`() {
        val preset = PenPreset("p", "Thick red", ToolType.PEN, PenConfig(PenType.BRUSH, 0xFFC93A2E.toInt(), 3.2f, 0.8f))
        val s = start.applyPreset(preset)
        assertEquals(ToolType.PEN, s.tool)
        assertEquals(preset.pen, s.state.pen)
        assertEquals(customPencil, s.state.pencil)
        assertTrue(s.matches(preset))
        assertFalse(start.matches(preset))
        assertEquals(ToolType.PENCIL, s.toggleEraser().toggleEraser().let { it.select(ToolType.PENCIL) }.tool)
    }

    @Test
    fun `pen updates are clamped to the allowed ranges`() {
        val s = start.updatePen(ToolType.PEN) { it.copy(width = 50f, pressureSensitivity = 3f) }
        assertEquals(ToolLimits.PEN_MAX_WIDTH, s.state.pen.width)
        assertEquals(1f, s.state.pen.pressureSensitivity)
        val h = start.updatePen(ToolType.HIGHLIGHTER) { it.copy(width = 1f) }
        assertEquals(ToolLimits.HIGHLIGHTER_MIN_WIDTH, h.state.highlighter.width)
        // Updating a non-inking tool's pen is a no-op.
        assertEquals(start, start.updatePen(ToolType.LASSO) { it.copy(width = 3f) })
    }

    @Test
    fun `eraser text and shape updates are clamped`() {
        assertEquals(ToolLimits.ERASER_MAX_DP, start.updateEraser { it.copy(sizeDp = 999f) }.state.eraser.sizeDp)
        assertEquals(ToolLimits.TEXT_MIN_PT, start.updateText { it.copy(fontSizePt = 1f) }.state.text.fontSizePt)
        assertEquals(ToolLimits.SHAPE_MAX_WIDTH, start.updateShape { it.copy(strokeWidth = 99f) }.state.shape.strokeWidth)
    }
}
