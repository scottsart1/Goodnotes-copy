package com.inkwell.notes.ink.render

import androidx.compose.ui.graphics.toArgb
import com.inkwell.notes.ui.theme.DarkInkwell
import com.inkwell.notes.ui.theme.InkwellColors
import com.inkwell.notes.ui.theme.LightInkwell

/** Inkwell design tokens as ARGB ints for the View-based canvas. */
class CanvasTheme(val dark: Boolean) {
    private val c: InkwellColors = if (dark) DarkInkwell else LightInkwell
    val desk = c.desk.toArgb()
    val pageShadow = c.pageShadow.toArgb()
    val accent = c.accent.toArgb()
    val onAccent = c.onAccent.toArgb()
    val selection = c.selection.toArgb()
    val ink = c.ink.toArgb()
    val inkMuted = c.inkMuted.toArgb()
    val surface = c.surface.toArgb()
    val outline = c.outline.toArgb()
    val record = c.record.toArgb()
    val danger = c.danger.toArgb()

    /** [color] with its alpha replaced by [alpha] (0..255). */
    fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
}
