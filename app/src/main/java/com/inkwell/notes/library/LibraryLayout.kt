package com.inkwell.notes.library

import kotlin.math.floor
import kotlin.math.max

/** How the navigation rail is presented at a given window width. */
enum class RailMode {
    /** 232dp rail with labels and the folder tree (900dp and wider). */
    FULL,

    /** 72dp icon rail; the full rail opens as a drawer (600 to 900dp). */
    COMPACT,

    /** No rail; a modal drawer (below 600dp). */
    DRAWER,
}

/** Responsive layout decisions in dp, unit-tested. */
object LibraryLayout {
    const val FULL_RAIL_MIN_WIDTH = 900f
    const val COMPACT_RAIL_MIN_WIDTH = 600f
    const val RAIL_WIDTH = 232f
    const val COMPACT_RAIL_WIDTH = 72f

    /** Cards stay at least this wide on tablets... */
    const val MIN_CARD_WIDTH = 200f

    /** ...and on phones, so two columns fit side by side. */
    const val MIN_CARD_WIDTH_PHONE = 148f

    /** Below this content width the search field moves to its own row. */
    const val SINGLE_ROW_TOP_BAR_MIN_WIDTH = 720f

    fun railMode(windowWidthDp: Float): RailMode = when {
        windowWidthDp >= FULL_RAIL_MIN_WIDTH -> RailMode.FULL
        windowWidthDp >= COMPACT_RAIL_MIN_WIDTH -> RailMode.COMPACT
        else -> RailMode.DRAWER
    }

    fun railWidth(mode: RailMode): Float = when (mode) {
        RailMode.FULL -> RAIL_WIDTH
        RailMode.COMPACT -> COMPACT_RAIL_WIDTH
        RailMode.DRAWER -> 0f
    }

    /** Horizontal content padding: generous on tablets, the 16dp gutter on phones. */
    fun contentPadding(windowWidthDp: Float): Float = if (windowWidthDp >= COMPACT_RAIL_MIN_WIDTH) 24f else 16f

    fun minCardWidth(windowWidthDp: Float): Float =
        if (windowWidthDp >= COMPACT_RAIL_MIN_WIDTH) MIN_CARD_WIDTH else MIN_CARD_WIDTH_PHONE

    /**
     * Number of grid columns that fit [availableWidthDp] with cards at least [minCellDp] wide and
     * [spacingDp] between them. Always at least one.
     */
    fun gridColumns(availableWidthDp: Float, minCellDp: Float, spacingDp: Float): Int {
        if (availableWidthDp <= 0f || minCellDp <= 0f) return 1
        val n = floor((availableWidthDp + spacingDp) / (minCellDp + spacingDp)).toInt()
        return max(1, n)
    }

    fun singleRowTopBar(contentWidthDp: Float): Boolean = contentWidthDp >= SINGLE_ROW_TOP_BAR_MIN_WIDTH
}
