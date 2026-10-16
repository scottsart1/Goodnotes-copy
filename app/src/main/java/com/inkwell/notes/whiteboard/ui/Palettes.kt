package com.inkwell.notes.whiteboard.ui

import androidx.annotation.StringRes
import com.inkwell.notes.R
import com.inkwell.notes.core.model.ToolType

/** A named content colour (ARGB). These are ink colours, not UI chrome. */
data class NamedColor(val argb: Int, @param:StringRes val name: Int)

object BoardPalettes {
    val pen = listOf(
        NamedColor(0xFF1A1916.toInt(), R.string.cd_color_black),
        NamedColor(0xFF6B675E.toInt(), R.string.cd_color_gray),
        NamedColor(0xFF2952CC.toInt(), R.string.cd_color_blue),
        NamedColor(0xFF168A7A.toInt(), R.string.cd_color_teal),
        NamedColor(0xFF2E7D4F.toInt(), R.string.cd_color_green),
        NamedColor(0xFFC93A2E.toInt(), R.string.cd_color_red),
        NamedColor(0xFFD9822B.toInt(), R.string.cd_color_orange),
        NamedColor(0xFF7046C2.toInt(), R.string.cd_color_purple),
    )

    val pencil = listOf(
        NamedColor(0xFF3B3B3B.toInt(), R.string.cd_color_graphite),
        NamedColor(0xFF6B675E.toInt(), R.string.cd_color_gray),
        NamedColor(0xFF2952CC.toInt(), R.string.cd_color_blue),
        NamedColor(0xFFC93A2E.toInt(), R.string.cd_color_red),
        NamedColor(0xFF2E7D4F.toInt(), R.string.cd_color_green),
        NamedColor(0xFFD9822B.toInt(), R.string.cd_color_orange),
        NamedColor(0xFF7046C2.toInt(), R.string.cd_color_purple),
        NamedColor(0xFF7A5230.toInt(), R.string.cd_color_brown),
    )

    val highlighter = listOf(
        NamedColor(0xFFF2D04B.toInt(), R.string.cd_color_yellow),
        NamedColor(0xFF9BE08A.toInt(), R.string.cd_color_green),
        NamedColor(0xFFF5A3C7.toInt(), R.string.cd_color_pink),
        NamedColor(0xFF9CC7F5.toInt(), R.string.cd_color_blue),
        NamedColor(0xFFF7B267.toInt(), R.string.cd_color_orange),
        NamedColor(0xFFC9B6F2.toInt(), R.string.cd_color_lavender),
    )

    /** Shape fills; 0 = no fill. */
    val fills = listOf(
        NamedColor(0, R.string.cd_color_none),
        NamedColor(0xFFFFFFFF.toInt(), R.string.cd_color_white),
        NamedColor(0xFFFBE7A1.toInt(), R.string.cd_color_yellow),
        NamedColor(0xFFCFE3FA.toInt(), R.string.cd_color_blue),
        NamedColor(0xFFD3F0CB.toInt(), R.string.cd_color_green),
        NamedColor(0xFFF9D3E3.toInt(), R.string.cd_color_pink),
        NamedColor(0xFFE9E7E1.toInt(), R.string.cd_color_gray),
    )

    val stickies = listOf(
        NamedColor(0xFFFFE89A.toInt(), R.string.cd_color_yellow),
        NamedColor(0xFFFFC8DD.toInt(), R.string.cd_color_pink),
        NamedColor(0xFFC9E4FF.toInt(), R.string.cd_color_blue),
        NamedColor(0xFFCDEFC4.toInt(), R.string.cd_color_mint),
        NamedColor(0xFFFFD6A5.toInt(), R.string.cd_color_peach),
        NamedColor(0xFFE0D4FF.toInt(), R.string.cd_color_lavender),
    )

    fun forTool(t: ToolType): List<NamedColor> = when (t) {
        ToolType.PENCIL -> pencil
        ToolType.HIGHLIGHTER -> highlighter
        ToolType.STICKY -> stickies
        else -> pen
    }

    /** Nominal widths (points) for the three thickness dots. */
    fun widths(t: ToolType): List<Float> = when (t) {
        ToolType.PENCIL -> listOf(0.8f, 1.3f, 2.2f)
        ToolType.HIGHLIGHTER -> listOf(8f, 14f, 22f)
        ToolType.SHAPE -> listOf(1f, 2f, 4f)
        else -> listOf(1.0f, 1.8f, 3.2f)
    }

    /** Same colour ignoring alpha. */
    fun sameColor(a: Int, b: Int): Boolean = (a and 0xFFFFFF) == (b and 0xFFFFFF) && ((a ushr 24) == 0) == ((b ushr 24) == 0)

    /** Index of the thickness dot closest to [width]. */
    fun nearestWidth(t: ToolType, width: Float): Int =
        widths(t).withIndex().minByOrNull { kotlin.math.abs(it.value - width) }?.index ?: 1
}
