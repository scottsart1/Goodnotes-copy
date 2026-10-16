package com.inkwell.notes.ink.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCommandsTest {
    private fun map(code: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) = KeyCommands.map(code, ctrl, shift, alt)

    @Test fun editingShortcuts() {
        assertEquals(KeyCommand.Copy, map(KeyEvent.KEYCODE_C, ctrl = true))
        assertEquals(KeyCommand.Cut, map(KeyEvent.KEYCODE_X, ctrl = true))
        assertEquals(KeyCommand.Paste, map(KeyEvent.KEYCODE_V, ctrl = true))
        assertEquals(KeyCommand.Duplicate, map(KeyEvent.KEYCODE_D, ctrl = true))
        assertEquals(KeyCommand.SelectAll, map(KeyEvent.KEYCODE_A, ctrl = true))
        assertEquals(KeyCommand.Undo, map(KeyEvent.KEYCODE_Z, ctrl = true))
        assertEquals(KeyCommand.Redo, map(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
        assertEquals(KeyCommand.Redo, map(KeyEvent.KEYCODE_Y, ctrl = true))
        assertEquals(KeyCommand.Group, map(KeyEvent.KEYCODE_G, ctrl = true))
        assertEquals(KeyCommand.Ungroup, map(KeyEvent.KEYCODE_G, ctrl = true, shift = true))
    }

    @Test fun plainKeys() {
        assertEquals(KeyCommand.Delete, map(KeyEvent.KEYCODE_DEL))
        assertEquals(KeyCommand.Delete, map(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(KeyCommand.ClearSelection, map(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(KeyCommand.Nudge(-1, 0, false), map(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(KeyCommand.Nudge(0, 1, true), map(KeyEvent.KEYCODE_DPAD_DOWN, shift = true))
        // Letters without a modifier, and Alt combinations, are left to the system.
        assertNull(map(KeyEvent.KEYCODE_C))
        assertNull(map(KeyEvent.KEYCODE_Z, ctrl = true, alt = true))
        assertNull(map(KeyEvent.KEYCODE_Q, ctrl = true))
    }

    @Test fun doubleTapNeedsTheSameItemQuicklyAndNearby() {
        val t = TapTracker(timeoutMs = 300, slopPx = 20f)
        assertFalse(t.tap(0, 10f, 10f, "a"))
        assertTrue(t.tap(200, 14f, 12f, "a"))
        // A third tap starts over.
        assertFalse(t.tap(300, 14f, 12f, "a"))
        // Different item, too slow, too far, empty space.
        assertFalse(t.tap(400, 14f, 12f, "b"))
        assertFalse(t.tap(1000, 14f, 12f, "b"))
        assertFalse(t.tap(1100, 80f, 12f, "b"))
        assertFalse(t.tap(1200, 80f, 12f, null))
        assertFalse(t.tap(1300, 80f, 12f, null))
    }
}
