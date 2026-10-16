package com.inkwell.notes.ink.input

import com.inkwell.notes.core.model.SideButtonMode
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.input.StylusButtonStateMachine.Companion.BUTTON_SECONDARY
import com.inkwell.notes.ink.input.StylusButtonStateMachine.Companion.BUTTON_STYLUS_PRIMARY
import com.inkwell.notes.ink.input.StylusButtonStateMachine.Companion.BUTTON_STYLUS_SECONDARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StylusButtonStateMachineTest {
    private lateinit var sm: StylusButtonStateMachine
    private var t = 0L

    @Before fun setUp() {
        sm = StylusButtonStateMachine()
        sm.mode = SideButtonMode.HOLD_TO_ERASE
        sm.setBaseTool(ToolType.PEN)
        t = 0L
    }

    private fun ev(phase: StylusPhase, buttons: Int = 0, kind: PointerKind = PointerKind.STYLUS): StylusButtonStateMachine.Result {
        t += 8
        val r = sm.onEvent(phase, kind, buttons, t)
        // Copy: the result object is reused.
        return StylusButtonStateMachine.Result().also {
            it.effectiveTool = r.effectiveTool; it.temporaryChanged = r.temporaryChanged
            it.temporaryTool = r.temporaryTool; it.requestTool = r.requestTool
        }
    }

    @Test fun hoverPressAndReleaseTogglesTemporaryEraser() {
        assertEquals(ToolType.PEN, ev(StylusPhase.HOVER_ENTER).effectiveTool)
        val pressed = ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, pressed.effectiveTool)
        assertTrue(pressed.temporaryChanged)
        assertEquals(ToolType.ERASER, pressed.temporaryTool)
        // Holding: no repeated notifications.
        assertFalse(ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY).temporaryChanged)
        val released = ev(StylusPhase.HOVER_MOVE, 0)
        assertEquals(ToolType.PEN, released.effectiveTool)
        assertTrue(released.temporaryChanged)
        assertNull(released.temporaryTool)
    }

    @Test fun pressedBeforeDownErasesFromFirstContact() {
        ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        ev(StylusPhase.HOVER_EXIT, BUTTON_STYLUS_PRIMARY)
        val down = ev(StylusPhase.DOWN, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, down.effectiveTool)
        // Hover exit before DOWN must not have flickered the temporary tool.
        assertFalse(down.temporaryChanged)
    }

    @Test fun pressMidStrokeSwitchesImmediatelyAndReleaseResumesPen() {
        ev(StylusPhase.DOWN, 0)
        assertEquals(ToolType.PEN, ev(StylusPhase.MOVE, 0).effectiveTool)
        val press = ev(StylusPhase.MOVE, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, press.effectiveTool)
        assertTrue(press.temporaryChanged)
        assertEquals(ToolType.ERASER, ev(StylusPhase.MOVE, BUTTON_STYLUS_PRIMARY).effectiveTool)
        val release = ev(StylusPhase.MOVE, 0)
        assertEquals(ToolType.PEN, release.effectiveTool)
        assertTrue(release.temporaryChanged)
        assertNull(release.temporaryTool)
    }

    @Test fun releaseReturnsToExactBaseTool() {
        sm.setBaseTool(ToolType.HIGHLIGHTER)
        ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, sm.effectiveTool)
        ev(StylusPhase.HOVER_MOVE, 0)
        assertEquals(ToolType.HIGHLIGHTER, sm.effectiveTool)
        assertEquals(ToolType.HIGHLIGHTER, sm.baseTool)
    }

    @Test fun alternativeFirmwareButtonBitsCount() {
        assertEquals(ToolType.ERASER, ev(StylusPhase.HOVER_MOVE, BUTTON_SECONDARY).effectiveTool)
        ev(StylusPhase.HOVER_MOVE, 0)
        assertEquals(ToolType.ERASER, ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_SECONDARY).effectiveTool)
    }

    @Test fun buttonPressAndReleaseEvents() {
        val press = ev(StylusPhase.BUTTON_PRESS, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, press.effectiveTool)
        assertTrue(press.temporaryChanged)
        val release = ev(StylusPhase.BUTTON_RELEASE, 0)
        assertEquals(ToolType.PEN, release.effectiveTool)
        assertTrue(release.temporaryChanged)
    }

    @Test fun upWithButtonStillHeldKeepsEraserUntilRelease() {
        ev(StylusPhase.DOWN, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, ev(StylusPhase.UP, BUTTON_STYLUS_PRIMARY).effectiveTool)
        assertEquals(ToolType.ERASER, ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY).effectiveTool)
        assertEquals(ToolType.PEN, ev(StylusPhase.HOVER_MOVE, 0).effectiveTool)
    }

    @Test fun hoverExitReleasesAfterGraceTimeout() {
        ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        ev(StylusPhase.HOVER_EXIT, BUTTON_STYLUS_PRIMARY)
        assertTrue(sm.hasPendingTimeout)
        // Too early: nothing happens.
        assertFalse(sm.onTimeout(t + 10).temporaryChanged)
        val r = sm.onTimeout(t + StylusButtonStateMachine.EXIT_GRACE_MS + 1)
        assertTrue(r.temporaryChanged)
        assertNull(r.temporaryTool)
        assertEquals(ToolType.PEN, r.effectiveTool)
        assertFalse(sm.hasPendingTimeout)
    }

    @Test fun toggleModeRequestsOnRisingEdgeOnly() {
        sm.mode = SideButtonMode.TOGGLE_ERASER
        val first = ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, first.requestTool)
        assertFalse(first.temporaryChanged)
        // Held: no further requests.
        assertNull(ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY).requestTool)
        assertNull(ev(StylusPhase.BUTTON_PRESS, BUTTON_STYLUS_PRIMARY).requestTool)
        // Release: nothing.
        assertNull(ev(StylusPhase.HOVER_MOVE, 0).requestTool)
        // Second press goes back to the previous pen.
        assertEquals(ToolType.PEN, ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY).requestTool)
    }

    @Test fun toggleFromEraserReturnsToLastNonEraserTool() {
        sm.mode = SideButtonMode.TOGGLE_ERASER
        sm.setBaseTool(ToolType.PENCIL)
        sm.setBaseTool(ToolType.ERASER)
        assertEquals(ToolType.PENCIL, ev(StylusPhase.DOWN, BUTTON_STYLUS_PRIMARY).requestTool)
    }

    @Test fun eraserTipAlwaysErasesEvenWhenOff() {
        sm.mode = SideButtonMode.OFF
        val r = ev(StylusPhase.HOVER_ENTER, 0, PointerKind.ERASER_TIP)
        assertEquals(ToolType.ERASER, r.effectiveTool)
        assertTrue(r.temporaryChanged)
        assertEquals(ToolType.ERASER, ev(StylusPhase.DOWN, 0, PointerKind.ERASER_TIP).effectiveTool)
        // Back to the normal tip.
        val tip = ev(StylusPhase.HOVER_MOVE, 0, PointerKind.STYLUS)
        assertEquals(ToolType.PEN, tip.effectiveTool)
        assertTrue(tip.temporaryChanged)
    }

    @Test fun offModeIgnoresButton() {
        sm.mode = SideButtonMode.OFF
        val r = ev(StylusPhase.MOVE, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.PEN, r.effectiveTool)
        assertFalse(r.temporaryChanged)
        assertNull(r.requestTool)
    }

    @Test fun holdToLassoSelectsWhileHeld() {
        sm.mode = SideButtonMode.HOLD_TO_LASSO
        assertEquals(ToolType.LASSO, ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY).effectiveTool)
        assertEquals(ToolType.PEN, ev(StylusPhase.HOVER_MOVE, 0).effectiveTool)
    }

    @Test fun fingerAndMouseEventsAreIgnored() {
        ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        val finger = ev(StylusPhase.MOVE, 0, PointerKind.FINGER)
        assertEquals(ToolType.ERASER, finger.effectiveTool)
        assertFalse(finger.temporaryChanged)
        val mouse = ev(StylusPhase.MOVE, BUTTON_SECONDARY, PointerKind.MOUSE)
        assertFalse(mouse.temporaryChanged)
        assertTrue(sm.buttonHeld)
    }

    @Test fun modeChangeWhileHeldIsAppliedOnRefresh() {
        ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        assertEquals(ToolType.ERASER, sm.effectiveTool)
        sm.mode = SideButtonMode.OFF
        val r = sm.refresh()
        assertTrue(r.temporaryChanged)
        assertEquals(ToolType.PEN, r.effectiveTool)
    }

    @Test fun baseToolChangeWhileHeldDoesNotDropTemporary() {
        ev(StylusPhase.HOVER_MOVE, BUTTON_STYLUS_PRIMARY)
        sm.setBaseTool(ToolType.PENCIL)
        assertEquals(ToolType.ERASER, sm.effectiveTool)
        ev(StylusPhase.HOVER_MOVE, 0)
        assertEquals(ToolType.PENCIL, sm.effectiveTool)
    }
}
