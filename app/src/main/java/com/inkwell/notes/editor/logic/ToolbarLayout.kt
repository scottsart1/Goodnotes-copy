package com.inkwell.notes.editor.logic

import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.core.model.ToolbarPosition
import kotlin.math.max
import kotlin.math.min

enum class ToolbarItem(val tool: ToolType? = null) {
    PEN(ToolType.PEN), PENCIL(ToolType.PENCIL), HIGHLIGHTER(ToolType.HIGHLIGHTER), ERASER(ToolType.ERASER),
    LASSO(ToolType.LASSO), SHAPE(ToolType.SHAPE), TEXT(ToolType.TEXT), IMAGE(ToolType.IMAGE),
    UNDO, REDO, RECORD, AI, OVERFLOW;

    companion object {
        fun forTool(t: ToolType): ToolbarItem? = entries.firstOrNull { it.tool == t }
    }
}

enum class ToolbarOrientation { HORIZONTAL, VERTICAL }

/** FULL = the pill; HANDLE = a tiny translucent handle (focus mode); HIDDEN = nothing. */
enum class ToolbarMode { FULL, HANDLE, HIDDEN }

data class ToolbarSpec(
    val mode: ToolbarMode,
    val orientation: ToolbarOrientation,
    /** Visible button size; every button sits in a [ToolbarLayout.SLOT_DP] touch target. */
    val buttonDp: Float,
    val iconDp: Float,
    /** Visible items in groups separated by dividers; the overflow button is the last group. */
    val groups: List<List<ToolbarItem>>,
    /** Items that did not fit and are offered in the overflow menu instead. */
    val overflow: List<ToolbarItem>,
) {
    val visibleItems: List<ToolbarItem> get() = groups.flatten()
}

/** Decides how the floating tool pill is laid out for the space and preferences at hand. */
object ToolbarLayout {
    val TOOLS = listOf(
        ToolbarItem.PEN, ToolbarItem.PENCIL, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
        ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.TEXT, ToolbarItem.IMAGE,
    )
    val HISTORY = listOf(ToolbarItem.UNDO, ToolbarItem.REDO)
    val EXTRAS = listOf(ToolbarItem.RECORD, ToolbarItem.AI)

    /** First entries move into the overflow menu first when the pill does not fit. */
    val DROP_ORDER = listOf(
        ToolbarItem.IMAGE, ToolbarItem.SHAPE, ToolbarItem.TEXT, ToolbarItem.RECORD,
        ToolbarItem.AI, ToolbarItem.PENCIL, ToolbarItem.REDO, ToolbarItem.LASSO,
    )

    const val BUTTON_DP = 36f
    const val COMPACT_BUTTON_DP = 32f
    const val ICON_DP = 20f
    const val COMPACT_ICON_DP = 18f
    /** Touch target of each button (>= 44dp in both directions). */
    const val SLOT_DP = 44f
    const val DIVIDER_DP = 9f

    /** Compact mode keeps the insert tools in the overflow menu. */
    val COMPACT_OVERFLOW = listOf(ToolbarItem.SHAPE, ToolbarItem.TEXT, ToolbarItem.IMAGE)
    const val END_PADDING_DP = 6f
    /** Space kept free around the pill along its axis. */
    const val OUTER_MARGIN_DP = 16f

    fun compute(
        position: ToolbarPosition,
        compact: Boolean,
        availableDp: Float,
        focusMode: Boolean = false,
        focusHidesToolbar: Boolean = true,
        handleExpanded: Boolean = false,
    ): ToolbarSpec {
        val orientation = if (position == ToolbarPosition.LEFT || position == ToolbarPosition.RIGHT) ToolbarOrientation.VERTICAL else ToolbarOrientation.HORIZONTAL
        val button = if (compact) COMPACT_BUTTON_DP else BUTTON_DP
        val icon = if (compact) COMPACT_ICON_DP else ICON_DP
        if (focusMode && !handleExpanded) {
            return ToolbarSpec(
                mode = if (focusHidesToolbar) ToolbarMode.HIDDEN else ToolbarMode.HANDLE,
                orientation = orientation, buttonDp = button, iconDp = icon, groups = emptyList(), overflow = emptyList(),
            )
        }
        val visible = (TOOLS + HISTORY + EXTRAS).toMutableList()
        val overflow = mutableListOf<ToolbarItem>()
        if (compact) {
            visible.removeAll(COMPACT_OVERFLOW)
            overflow += COMPACT_OVERFLOW
        }
        val budget = availableDp - 2 * OUTER_MARGIN_DP
        for (item in DROP_ORDER) {
            if (lengthDp(groupsOf(visible)) <= budget) break
            if (visible.remove(item)) overflow += item
        }
        return ToolbarSpec(ToolbarMode.FULL, orientation, button, icon, groupsOf(visible), overflow.sortedBy { (TOOLS + HISTORY + EXTRAS).indexOf(it) })
    }

    private fun groupsOf(visible: List<ToolbarItem>): List<List<ToolbarItem>> =
        listOf(TOOLS, HISTORY, EXTRAS).map { g -> g.filter { it in visible } }.filter { it.isNotEmpty() } + listOf(listOf(ToolbarItem.OVERFLOW))

    /** Length of the pill along its axis for [groups]. */
    fun lengthDp(groups: List<List<ToolbarItem>>): Float {
        val items = groups.sumOf { it.size }
        if (items == 0) return 0f
        return 2 * END_PADDING_DP + items * SLOT_DP + (groups.size - 1) * DIVIDER_DP
    }

    fun lengthDp(spec: ToolbarSpec): Float = lengthDp(spec.groups)

    /**
     * Top-left of a floating toolbar of [barW]x[barH] whose centre sits at the fractional position
     * ([fx], [fy]) of a [containerW]x[containerH] area, kept fully inside with [margin].
     */
    fun floatingOffset(fx: Float, fy: Float, containerW: Float, containerH: Float, barW: Float, barH: Float, margin: Float): Pair<Float, Float> {
        val maxX = max(margin, containerW - barW - margin)
        val maxY = max(margin, containerH - barH - margin)
        val x = (fx.coerceIn(0f, 1f) * containerW - barW / 2f).coerceIn(min(margin, maxX), maxX)
        val y = (fy.coerceIn(0f, 1f) * containerH - barH / 2f).coerceIn(min(margin, maxY), maxY)
        return x to y
    }

    /** Inverse of [floatingOffset]: the fractional centre to persist after a drag. */
    fun floatingFraction(x: Float, y: Float, containerW: Float, containerH: Float, barW: Float, barH: Float): Pair<Float, Float> {
        if (containerW <= 0f || containerH <= 0f) return 0.5f to 0.08f
        return ((x + barW / 2f) / containerW).coerceIn(0f, 1f) to ((y + barH / 2f) / containerH).coerceIn(0f, 1f)
    }
}

enum class TopBarAction { SEARCH, PAGES, OUTLINE, AI, RECORDINGS, EXPORT, FOCUS }

/** Which top-bar buttons fit at a given width; the rest go into the overflow menu. */
object TopBarLayout {
    private val PRIORITY = listOf(
        TopBarAction.AI, TopBarAction.PAGES, TopBarAction.SEARCH, TopBarAction.EXPORT,
        TopBarAction.OUTLINE, TopBarAction.RECORDINGS, TopBarAction.FOCUS,
    )
    private val ORDER = listOf(
        TopBarAction.SEARCH, TopBarAction.PAGES, TopBarAction.OUTLINE, TopBarAction.AI,
        TopBarAction.RECORDINGS, TopBarAction.EXPORT, TopBarAction.FOCUS,
    )

    /** Space for back, title, page label and overflow, in dp. */
    const val RESERVED_DP = 44f + 220f + 44f
    const val PAGE_LABEL_DP = 96f
    const val BUTTON_DP = 44f

    fun showPageLabel(widthDp: Float): Boolean = widthDp >= 560f

    fun split(widthDp: Float): Pair<List<TopBarAction>, List<TopBarAction>> {
        var free = widthDp - RESERVED_DP - (if (showPageLabel(widthDp)) PAGE_LABEL_DP else 0f)
        val shown = mutableSetOf<TopBarAction>()
        for (a in PRIORITY) {
            if (free < BUTTON_DP) break
            shown += a
            free -= BUTTON_DP
        }
        return ORDER.filter { it in shown } to ORDER.filter { it !in shown }
    }
}
