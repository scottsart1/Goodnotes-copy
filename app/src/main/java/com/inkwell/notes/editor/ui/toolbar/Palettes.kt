package com.inkwell.notes.editor.ui.toolbar

import androidx.annotation.StringRes
import com.inkwell.notes.R
import com.inkwell.notes.core.model.ToolType

/** A named ink colour (ARGB). These are content colours, not UI chrome. */
data class InkColor(val argb: Int, @StringRes val name: Int)

object Palettes {
    val pen = listOf(
        InkColor(0xFF1A1916.toInt(), R.string.editor_color_black),
        InkColor(0xFF6B675E.toInt(), R.string.editor_color_gray),
        InkColor(0xFF2952CC.toInt(), R.string.editor_color_blue),
        InkColor(0xFF2E86C1.toInt(), R.string.editor_color_sky),
        InkColor(0xFF168A7A.toInt(), R.string.editor_color_teal),
        InkColor(0xFF2E7D4F.toInt(), R.string.editor_color_green),
        InkColor(0xFFC93A2E.toInt(), R.string.editor_color_red),
        InkColor(0xFFD9822B.toInt(), R.string.editor_color_orange),
        InkColor(0xFF7046C2.toInt(), R.string.editor_color_purple),
        InkColor(0xFFC2458A.toInt(), R.string.editor_color_pink),
    )

    val pencil = listOf(
        InkColor(0xFF3B3B3B.toInt(), R.string.editor_color_graphite),
        InkColor(0xFF6B675E.toInt(), R.string.editor_color_gray),
        InkColor(0xFF2952CC.toInt(), R.string.editor_color_blue),
        InkColor(0xFFC93A2E.toInt(), R.string.editor_color_red),
        InkColor(0xFF2E7D4F.toInt(), R.string.editor_color_green),
        InkColor(0xFFD9822B.toInt(), R.string.editor_color_orange),
        InkColor(0xFF7046C2.toInt(), R.string.editor_color_purple),
        InkColor(0xFF7A5230.toInt(), R.string.editor_color_brown),
    )

    val highlighter = listOf(
        InkColor(0xFFF2D04B.toInt(), R.string.editor_color_yellow),
        InkColor(0xFF9BE08A.toInt(), R.string.editor_color_green),
        InkColor(0xFFF5A3C7.toInt(), R.string.editor_color_pink),
        InkColor(0xFF9CC7F5.toInt(), R.string.editor_color_blue),
        InkColor(0xFFF7B267.toInt(), R.string.editor_color_orange),
    )

    /** Fill colours for shapes; 0 = no fill. */
    val fills = listOf(
        InkColor(0, R.string.editor_color_none),
        InkColor(0xFFFFFFFF.toInt(), R.string.editor_color_white),
        InkColor(0xFFF2D04B.toInt(), R.string.editor_color_yellow),
        InkColor(0xFF9CC7F5.toInt(), R.string.editor_color_blue),
        InkColor(0xFF9BE08A.toInt(), R.string.editor_color_green),
        InkColor(0xFFF5A3C7.toInt(), R.string.editor_color_pink),
        InkColor(0xFFE9E7E1.toInt(), R.string.editor_color_gray),
    )

    fun forTool(t: ToolType): List<InkColor> = when (t) {
        ToolType.PENCIL -> pencil
        ToolType.HIGHLIGHTER -> highlighter
        else -> pen
    }
}
