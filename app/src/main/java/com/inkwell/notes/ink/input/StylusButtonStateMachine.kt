package com.inkwell.notes.ink.input

import com.inkwell.notes.core.model.SideButtonMode
import com.inkwell.notes.core.model.ToolType

/** Kind of pointer that produced an event (mirrors MotionEvent tool types). */
enum class PointerKind { STYLUS, ERASER_TIP, FINGER, MOUSE, UNKNOWN }

/** Stylus event phases the state machine understands. */
enum class StylusPhase { HOVER_ENTER, HOVER_MOVE, HOVER_EXIT, DOWN, MOVE, UP, CANCEL, BUTTON_PRESS, BUTTON_RELEASE }

/**
 * Decides the effective tool for stylus input from the S Pen side button and eraser tip.
 *
 * - HOLD_TO_ERASE / HOLD_TO_LASSO: while the button is held (seen in buttonState of any hover,
 *   contact or button event) the effective tool is ERASER / LASSO; releasing returns to the
 *   base tool. Changes apply immediately, including mid-stroke.
 * - TOGGLE_ERASER: each press (rising edge) requests a persistent switch to ERASER, or back to
 *   the last non-eraser tool when the eraser is already active.
 * - OFF: the button is ignored.
 * - An eraser tip (TOOL_TYPE_ERASER) always erases, whatever the mode.
 *
 * The host owns the tool state; this class never changes it except through [Result.requestTool].
 * A hover exit releases the temporary tool only after [EXIT_GRACE_MS] (the framework sends a
 * hover exit right before every pen down), resolved through [onTimeout].
 * Pure Kotlin; allocation free per event.
 */
class StylusButtonStateMachine {
    class Result {
        /** Tool that stylus input should use now. */
        var effectiveTool: ToolType = ToolType.PEN
        /** True when the temporary tool changed with this event. */
        var temporaryChanged: Boolean = false
        /** New temporary tool (null = released) when [temporaryChanged]. */
        var temporaryTool: ToolType? = null
        /** Persistent tool switch the host should apply (toggle mode), or null. */
        var requestTool: ToolType? = null

        internal fun reset(effective: ToolType) {
            effectiveTool = effective; temporaryChanged = false; temporaryTool = null; requestTool = null
        }
    }

    var mode: SideButtonMode = SideButtonMode.HOLD_TO_ERASE
        set(value) {
            if (field == value) return
            field = value
            pendingModeChange = true
        }
    var baseTool: ToolType = ToolType.PEN
        private set
    /** Last non-eraser tool seen through [setBaseTool], used by toggle mode. */
    var lastNonEraserTool: ToolType = ToolType.PEN
        private set
    var buttonHeld: Boolean = false
        private set
    var eraserTip: Boolean = false
        private set
    /** Temporary tool currently reported to the host (null = none). */
    var temporaryTool: ToolType? = null
        private set

    private var hoverExitDeadline = -1L
    private var pendingModeChange = false
    private val result = Result()

    val effectiveTool: ToolType get() = temporaryTool ?: baseTool

    /** Called whenever the host's ToolState changes. */
    fun setBaseTool(tool: ToolType) {
        baseTool = tool
        if (tool != ToolType.ERASER) lastNonEraserTool = tool
    }

    /** True while a hover-exit release is waiting for [onTimeout]. */
    val hasPendingTimeout: Boolean get() = hoverExitDeadline >= 0

    /**
     * Feeds one stylus event. [buttonState] is MotionEvent.getButtonState(); events from fingers
     * and mice leave the state untouched.
     */
    fun onEvent(phase: StylusPhase, kind: PointerKind, buttonState: Int, timeMs: Long): Result {
        result.reset(effectiveTool)
        if (kind != PointerKind.STYLUS && kind != PointerKind.ERASER_TIP) return result
        if (phase == StylusPhase.HOVER_EXIT) {
            hoverExitDeadline = timeMs + EXIT_GRACE_MS
            return result
        }
        hoverExitDeadline = -1L
        val held = (buttonState and BUTTON_MASK) != 0
        val wasHeld = buttonHeld
        buttonHeld = held
        eraserTip = kind == PointerKind.ERASER_TIP
        if (mode == SideButtonMode.TOGGLE_ERASER && held && !wasHeld) {
            val target = if (baseTool == ToolType.ERASER) lastNonEraserTool else ToolType.ERASER
            result.requestTool = target
            // Optimistic: the host applies the request asynchronously.
            setBaseTool(target)
        }
        updateTemporary()
        result.effectiveTool = effectiveTool
        return result
    }

    /** Resolves a pending hover exit; call when [hasPendingTimeout] and the deadline passed. */
    fun onTimeout(nowMs: Long): Result {
        result.reset(effectiveTool)
        if (hoverExitDeadline < 0 || nowMs < hoverExitDeadline) return result
        hoverExitDeadline = -1L
        buttonHeld = false
        eraserTip = false
        updateTemporary()
        result.effectiveTool = effectiveTool
        return result
    }

    /** Re-evaluates after a mode change without a new event. */
    fun refresh(): Result {
        result.reset(effectiveTool)
        if (pendingModeChange) { pendingModeChange = false; updateTemporary() }
        result.effectiveTool = effectiveTool
        return result
    }

    /** Forgets everything (e.g. when the view is rebound). */
    fun reset() {
        buttonHeld = false; eraserTip = false; temporaryTool = null; hoverExitDeadline = -1L
    }

    private fun updateTemporary() {
        val desired: ToolType? = when {
            eraserTip -> ToolType.ERASER
            !buttonHeld -> null
            mode == SideButtonMode.HOLD_TO_ERASE -> ToolType.ERASER
            mode == SideButtonMode.HOLD_TO_LASSO -> ToolType.LASSO
            else -> null
        }
        if (desired != temporaryTool) {
            temporaryTool = desired
            result.temporaryChanged = true
            result.temporaryTool = desired
        }
    }

    companion object {
        // MotionEvent button constants (copied so the logic stays testable on the JVM).
        const val BUTTON_SECONDARY = 0x02
        const val BUTTON_STYLUS_PRIMARY = 0x20
        const val BUTTON_STYLUS_SECONDARY = 0x40
        const val BUTTON_MASK = BUTTON_SECONDARY or BUTTON_STYLUS_PRIMARY or BUTTON_STYLUS_SECONDARY
        const val EXIT_GRACE_MS = 160L
    }
}
