package com.inkwell.notes.editor

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.editor.ui.dialogs.formatTokens
import com.inkwell.notes.editor.ui.pages.PaperColor
import com.inkwell.notes.editor.ui.pages.pageSizeMm
import com.inkwell.notes.editor.ui.toolbar.Palettes
import com.inkwell.notes.editor.ui.toolbar.sameColor
import com.inkwell.notes.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiHelpersTest {
    @Test
    fun `colours compare by visible rgb and zero only matches itself`() {
        assertTrue(sameColor(0xFF2952CC.toInt(), 0x802952CC.toInt()))
        assertFalse(sameColor(0xFF2952CC.toInt(), 0xFF2952CD.toInt()))
        assertTrue(sameColor(0, 0))
        assertFalse(sameColor(0, 0xFF000000.toInt()))
    }

    @Test
    fun `paper colours are recognised from stored values`() {
        assertEquals(PaperColor.WHITE, PaperColor.of(0xFFFFFFFF.toInt()))
        assertEquals(PaperColor.DARK, PaperColor.of(PaperColor.DARK.paper))
        assertEquals(PaperColor.IVORY, PaperColor.of(0x00FBF6E9))
        assertEquals(PaperColor.WHITE, PaperColor.of(0xFF123456.toInt()))
    }

    @Test
    fun `page sizes show in millimetres`() {
        assertEquals("210 × 297 mm", pageSizeMm(PageMeta("p", "d", 0, 595f, 842f)))
    }

    @Test
    fun `token counts are compact`() {
        assertEquals("950", formatTokens(950))
        assertEquals("1.1k", formatTokens(1_100))
        assertEquals("40.0k", formatTokens(40_000))
    }

    @Test
    fun `palettes match the design`() {
        assertTrue(Palettes.pen.size in 8..12)
        assertEquals(5, Palettes.highlighter.size)
        assertEquals(Palettes.highlighter, Palettes.forTool(ToolType.HIGHLIGHTER))
        assertEquals(Palettes.pencil, Palettes.forTool(ToolType.PENCIL))
        assertEquals(0, Palettes.fills.first().argb)
        assertEquals(Palettes.pen.size, Palettes.pen.map { it.argb }.toSet().size)
    }
}
