package com.inkwell.notes.editor

import com.inkwell.notes.core.model.ToolbarPosition
import com.inkwell.notes.editor.logic.Bounds
import com.inkwell.notes.editor.logic.Placement
import com.inkwell.notes.editor.logic.PopupSide
import com.inkwell.notes.editor.logic.ToolbarItem
import com.inkwell.notes.editor.logic.ToolbarLayout
import com.inkwell.notes.editor.logic.ToolbarMode
import com.inkwell.notes.editor.logic.ToolbarOrientation
import com.inkwell.notes.editor.logic.TopBarAction
import com.inkwell.notes.editor.logic.TopBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutLogicTest {

    @Test
    fun `full toolbar fits a landscape tablet and a split pane`() {
        for (width in listOf(1480f, 740f)) {
            val spec = ToolbarLayout.compute(ToolbarPosition.TOP, compact = false, availableDp = width)
            assertEquals(ToolbarMode.FULL, spec.mode)
            assertTrue(spec.overflow.isEmpty())
            assertEquals(ToolbarItem.OVERFLOW, spec.groups.last().single())
            assertEquals(4, spec.groups.size)
            assertTrue(ToolbarLayout.lengthDp(spec) <= width)
        }
    }

    @Test
    fun `narrow widths move items into the overflow in priority order`() {
        val spec = ToolbarLayout.compute(ToolbarPosition.BOTTOM, compact = false, availableDp = 360f)
        assertTrue(ToolbarLayout.lengthDp(spec) <= 360f - 2 * ToolbarLayout.OUTER_MARGIN_DP)
        assertTrue(ToolbarItem.IMAGE in spec.overflow)
        assertTrue(ToolbarItem.SHAPE in spec.overflow)
        // The essentials stay.
        for (item in listOf(ToolbarItem.PEN, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER, ToolbarItem.UNDO)) {
            assertTrue(item in spec.visibleItems)
        }
        // Overflow keeps toolbar order.
        val order = (ToolbarLayout.TOOLS + ToolbarLayout.HISTORY + ToolbarLayout.EXTRAS)
        assertEquals(spec.overflow.sortedBy { order.indexOf(it) }, spec.overflow)
    }

    @Test
    fun `compact mode uses smaller buttons and fits more`() {
        val normal = ToolbarLayout.compute(ToolbarPosition.TOP, compact = false, availableDp = 520f)
        val compact = ToolbarLayout.compute(ToolbarPosition.TOP, compact = true, availableDp = 520f)
        assertEquals(32f, compact.buttonDp)
        assertTrue(compact.overflow.size <= normal.overflow.size)
    }

    @Test
    fun `side positions are vertical`() {
        assertEquals(ToolbarOrientation.VERTICAL, ToolbarLayout.compute(ToolbarPosition.LEFT, false, 900f).orientation)
        assertEquals(ToolbarOrientation.VERTICAL, ToolbarLayout.compute(ToolbarPosition.RIGHT, false, 900f).orientation)
        assertEquals(ToolbarOrientation.HORIZONTAL, ToolbarLayout.compute(ToolbarPosition.FLOATING, false, 900f).orientation)
    }

    @Test
    fun `focus mode hides the toolbar or shrinks it to a handle`() {
        assertEquals(ToolbarMode.HIDDEN, ToolbarLayout.compute(ToolbarPosition.TOP, false, 1000f, focusMode = true, focusHidesToolbar = true).mode)
        assertEquals(ToolbarMode.HANDLE, ToolbarLayout.compute(ToolbarPosition.TOP, false, 1000f, focusMode = true, focusHidesToolbar = false).mode)
        assertEquals(ToolbarMode.FULL, ToolbarLayout.compute(ToolbarPosition.TOP, false, 1000f, focusMode = true, focusHidesToolbar = false, handleExpanded = true).mode)
    }

    @Test
    fun `floating toolbar stays inside its container`() {
        val (x, y) = ToolbarLayout.floatingOffset(1f, 1f, 1000f, 800f, 500f, 44f, 8f)
        assertEquals(1000f - 500f - 8f, x)
        assertEquals(800f - 44f - 8f, y)
        val (x0, y0) = ToolbarLayout.floatingOffset(0f, 0f, 1000f, 800f, 500f, 44f, 8f)
        assertEquals(8f, x0)
        assertEquals(8f, y0)
        val (fx, fy) = ToolbarLayout.floatingFraction(250f, 100f, 1000f, 800f, 500f, 44f)
        assertEquals(0.5f, fx, 1e-4f)
        assertEquals(122f / 800f, fy, 1e-4f)
        val (rx, ry) = ToolbarLayout.floatingOffset(fx, fy, 1000f, 800f, 500f, 44f, 8f)
        assertEquals(250f, rx, 1e-3f)
        assertEquals(100f, ry, 1e-3f)
    }

    @Test
    fun `top bar keeps the most useful buttons as it narrows`() {
        val (wide, wideOverflow) = TopBarLayout.split(1480f)
        assertEquals(TopBarAction.entries.toList(), wide)
        assertTrue(wideOverflow.isEmpty())
        val (narrow, narrowOverflow) = TopBarLayout.split(420f)
        assertTrue(narrow.size < wide.size)
        assertTrue(narrow.isEmpty() || narrow.first() == TopBarAction.PAGES || TopBarAction.AI in narrow)
        assertEquals(TopBarAction.entries.size, narrow.size + narrowOverflow.size)
        assertFalse(TopBarLayout.showPageLabel(420f))
    }

    @Test
    fun `selection menu goes above the selection, below when there is no room`() {
        val above = Placement.selectionMenu(Bounds(100f, 300f, 300f, 400f), 200f, 48f, 1000f, 800f, gap = 8f, margin = 8f)
        assertEquals(PopupSide.ABOVE, above.side)
        assertEquals(300f - 8f - 48f, above.y)
        assertEquals(100f, above.x)
        val below = Placement.selectionMenu(Bounds(100f, 20f, 300f, 100f), 200f, 48f, 1000f, 800f, gap = 8f, margin = 8f)
        assertEquals(PopupSide.BELOW, below.side)
        assertEquals(108f, below.y)
        val clamped = Placement.selectionMenu(Bounds(-50f, 300f, 10f, 400f), 200f, 48f, 1000f, 800f, gap = 8f, margin = 8f)
        assertEquals(8f, clamped.x)
        val huge = Placement.selectionMenu(Bounds(0f, 10f, 1000f, 795f), 200f, 48f, 1000f, 800f, gap = 8f, margin = 8f)
        assertTrue(huge.y >= 8f && huge.y + 48f <= 792f)
    }

    @Test
    fun `popover flips when the preferred side does not fit`() {
        val anchor = Bounds(500f, 740f, 536f, 776f)
        val p = Placement.popover(anchor, 300f, 200f, 1200f, 800f, PopupSide.BELOW, 8f, 8f)
        assertEquals(PopupSide.ABOVE, p.side)
        assertEquals(740f - 8f - 200f, p.y)
        val side = Placement.popover(Bounds(4f, 300f, 40f, 336f), 300f, 200f, 1200f, 800f, PopupSide.START, 8f, 8f)
        assertEquals(PopupSide.END, side.side)
        assertEquals(48f, side.x)
    }

    @Test
    fun `panels push on tablets, overlay in split panes and cover phones`() {
        assertEquals(Placement.PanelMode.PUSH, Placement.panelMode(1480f, 420f))
        assertEquals(Placement.PanelMode.OVERLAY, Placement.panelMode(740f, 420f))
        assertEquals(Placement.PanelMode.FULL, Placement.panelMode(400f, 420f))
        assertEquals(640f, Placement.panelWidth(900f, 1480f))
        assertEquals(320f, Placement.panelWidth(100f, 1480f))
        assertEquals(300f, Placement.panelWidth(420f, 300f))
        assertEquals(0.3f, Placement.splitFraction(0.1f))
        assertEquals(0.7f, Placement.splitFraction(0.95f))
    }
}
