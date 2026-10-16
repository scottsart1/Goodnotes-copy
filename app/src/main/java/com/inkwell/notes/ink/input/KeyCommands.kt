package com.inkwell.notes.ink.input

import android.view.KeyEvent

/** Canvas keyboard shortcuts (physical keyboard, Samsung DeX). */
sealed class KeyCommand {
    object Delete : KeyCommand()
    object Copy : KeyCommand()
    object Cut : KeyCommand()
    object Paste : KeyCommand()
    object Duplicate : KeyCommand()
    object SelectAll : KeyCommand()
    object Undo : KeyCommand()
    object Redo : KeyCommand()
    object ClearSelection : KeyCommand()
    object Group : KeyCommand()
    object Ungroup : KeyCommand()
    /** Arrow keys: move the selection (or pan when nothing is selected) by one step. */
    data class Nudge(val dx: Int, val dy: Int, val large: Boolean) : KeyCommand()

    override fun toString(): String = javaClass.simpleName
}

/**
 * Maps key presses to [KeyCommand]s. Ctrl and Meta (Cmd on Mac-style keyboards under DeX) are
 * treated alike. Pure Kotlin (key codes are compile-time constants).
 */
object KeyCommands {
    fun map(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): KeyCommand? {
        if (alt) return null
        if (ctrl) {
            return when (keyCode) {
                KeyEvent.KEYCODE_C -> KeyCommand.Copy
                KeyEvent.KEYCODE_X -> KeyCommand.Cut
                KeyEvent.KEYCODE_V -> KeyCommand.Paste
                KeyEvent.KEYCODE_D -> KeyCommand.Duplicate
                KeyEvent.KEYCODE_A -> KeyCommand.SelectAll
                KeyEvent.KEYCODE_Z -> if (shift) KeyCommand.Redo else KeyCommand.Undo
                KeyEvent.KEYCODE_Y -> KeyCommand.Redo
                KeyEvent.KEYCODE_G -> if (shift) KeyCommand.Ungroup else KeyCommand.Group
                else -> null
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> KeyCommand.Delete
            KeyEvent.KEYCODE_ESCAPE -> KeyCommand.ClearSelection
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyCommand.Nudge(-1, 0, shift)
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyCommand.Nudge(1, 0, shift)
            KeyEvent.KEYCODE_DPAD_UP -> KeyCommand.Nudge(0, -1, shift)
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyCommand.Nudge(0, 1, shift)
            else -> null
        }
    }
}

/**
 * Double-tap detection on items: two taps on the same item within [timeoutMs] and [slopPx].
 * Pure Kotlin.
 */
class TapTracker(var timeoutMs: Long = 350L, var slopPx: Float = 48f) {
    private var lastTime = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f
    private var lastKey: String? = null

    /** Records a tap on [key] (an item id, or null for empty space); true when it completes a double tap. */
    fun tap(timeMs: Long, x: Float, y: Float, key: String?): Boolean {
        val dx = x - lastX; val dy = y - lastY
        val double = key != null && key == lastKey && lastTime != Long.MIN_VALUE &&
            timeMs - lastTime in 0..timeoutMs && dx * dx + dy * dy <= slopPx * slopPx
        if (double) reset() else { lastTime = timeMs; lastX = x; lastY = y; lastKey = key }
        return double
    }

    fun reset() {
        lastTime = Long.MIN_VALUE
        lastKey = null
    }
}
