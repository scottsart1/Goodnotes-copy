package com.inkwell.notes.editor.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.inkwell.notes.editor.logic.EditorAction
import com.inkwell.notes.editor.logic.EditorShortcuts
import com.inkwell.notes.editor.logic.ShortcutKey

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Keeps the screen on while an editor is visible. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Hides the system bars while [immersive] (focus mode); a swipe shows them transiently. */
@Composable
fun ImmersiveMode(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, immersive) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (immersive) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Maps a hardware key press to an editor action (DeX and keyboard covers). */
fun editorActionFor(event: KeyEvent): EditorAction? {
    if (event.type != KeyEventType.KeyDown) return null
    val key = when (event.key) {
        Key.Z -> ShortcutKey.Z
        Key.Y -> ShortcutKey.Y
        Key.F -> ShortcutKey.F
        Key.P -> ShortcutKey.P
        Key.E -> ShortcutKey.E
        Key.H -> ShortcutKey.H
        Key.L -> ShortcutKey.L
        Key.N -> ShortcutKey.N
        Key.B -> ShortcutKey.B
        Key.C -> ShortcutKey.C
        Key.X -> ShortcutKey.X
        Key.V -> ShortcutKey.V
        Key.D -> ShortcutKey.D
        Key.A -> ShortcutKey.A
        Key.Equals -> ShortcutKey.EQUALS
        Key.Plus, Key.NumPadAdd -> ShortcutKey.PLUS
        Key.Minus, Key.NumPadSubtract -> ShortcutKey.MINUS
        Key.Delete, Key.Backspace -> ShortcutKey.DELETE
        Key.Escape -> ShortcutKey.ESCAPE
        Key.PageUp -> ShortcutKey.PAGE_UP
        Key.PageDown -> ShortcutKey.PAGE_DOWN
        else -> return null
    }
    return EditorShortcuts.resolve(key, ctrl = event.isCtrlPressed || event.isMetaPressed, shift = event.isShiftPressed, alt = event.isAltPressed)
}
