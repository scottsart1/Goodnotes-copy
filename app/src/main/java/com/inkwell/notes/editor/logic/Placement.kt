package com.inkwell.notes.editor.logic

import kotlin.math.max
import kotlin.math.min

/** Where a popup ends up relative to its anchor. */
enum class PopupSide { BELOW, ABOVE, END, START }

data class Placed(val x: Float, val y: Float, val side: PopupSide)

/** Positioning of floating menus, popovers and side panels. All values in one unit (px or dp). */
object Placement {

    /**
     * A selection menu of [menuW]x[menuH] centred above the selection [bounds] (l, t, r, b), or
     * below it when there is no room above; inside the top of the selection when neither fits.
     * Always kept within the container with [margin]. [minTop] reserves space at the top
     * (e.g. a floating toolbar).
     */
    fun selectionMenu(
        bounds: Bounds,
        menuW: Float,
        menuH: Float,
        containerW: Float,
        containerH: Float,
        gap: Float,
        margin: Float,
        minTop: Float = 0f,
    ): Placed {
        val x = clamp(bounds.centerX - menuW / 2f, margin, containerW - menuW - margin)
        val top = max(margin, minTop)
        val aboveY = bounds.top - gap - menuH
        if (aboveY >= top) return Placed(x, aboveY, PopupSide.ABOVE)
        val belowY = bounds.bottom + gap
        if (belowY + menuH <= containerH - margin) return Placed(x, belowY, PopupSide.BELOW)
        val inside = clamp(max(bounds.top, top) + gap, top, containerH - menuH - margin)
        return Placed(x, inside, PopupSide.BELOW)
    }

    /**
     * A popover next to [anchor] on [preferred] side, flipped to the opposite side when it does
     * not fit, and clamped to the window.
     */
    fun popover(
        anchor: Bounds,
        popupW: Float,
        popupH: Float,
        windowW: Float,
        windowH: Float,
        preferred: PopupSide,
        gap: Float,
        margin: Float,
    ): Placed {
        fun fitsBelow() = anchor.bottom + gap + popupH <= windowH - margin
        fun fitsAbove() = anchor.top - gap - popupH >= margin
        fun fitsEnd() = anchor.right + gap + popupW <= windowW - margin
        fun fitsStart() = anchor.left - gap - popupW >= margin
        val side = when (preferred) {
            PopupSide.BELOW -> if (fitsBelow() || !fitsAbove()) PopupSide.BELOW else PopupSide.ABOVE
            PopupSide.ABOVE -> if (fitsAbove() || !fitsBelow()) PopupSide.ABOVE else PopupSide.BELOW
            PopupSide.END -> if (fitsEnd() || !fitsStart()) PopupSide.END else PopupSide.START
            PopupSide.START -> if (fitsStart() || !fitsEnd()) PopupSide.START else PopupSide.END
        }
        val (x, y) = when (side) {
            PopupSide.BELOW -> (anchor.centerX - popupW / 2f) to (anchor.bottom + gap)
            PopupSide.ABOVE -> (anchor.centerX - popupW / 2f) to (anchor.top - gap - popupH)
            PopupSide.END -> (anchor.right + gap) to (anchor.centerY - popupH / 2f)
            PopupSide.START -> (anchor.left - gap - popupW) to (anchor.centerY - popupH / 2f)
        }
        return Placed(clamp(x, margin, windowW - popupW - margin), clamp(y, margin, windowH - popupH - margin), side)
    }

    /** How a side panel shares a pane with the canvas. */
    enum class PanelMode {
        /** Panel beside the canvas; the canvas shrinks. */
        PUSH,
        /** Panel floats over the canvas edge. */
        OVERLAY,
        /** Panel covers the whole pane (phones). */
        FULL,
    }

    fun panelMode(paneWidthDp: Float, panelWidthDp: Float, minCanvasDp: Float = 420f): PanelMode = when {
        paneWidthDp - panelWidthDp >= minCanvasDp -> PanelMode.PUSH
        paneWidthDp >= panelWidthDp + 160f -> PanelMode.OVERLAY
        else -> PanelMode.FULL
    }

    /** Effective width of a panel in a pane, never wider than the pane. */
    fun panelWidth(requestedDp: Float, paneWidthDp: Float, minDp: Float = 320f, maxDp: Float = 640f): Float =
        min(requestedDp.coerceIn(minDp, maxDp), paneWidthDp)

    /**
     * Estimated pixels per page point for a paged canvas of [viewWidthPx] showing pages up to
     * [maxPageWidthPt] wide at [zoom] (fit-width layout with a 24dp margin on each side).
     */
    fun estimatePxPerPt(viewWidthPx: Float, density: Float, maxPageWidthPt: Float, zoom: Float): Float {
        if (viewWidthPx <= 0f || maxPageWidthPt <= 0f) return density * zoom
        val avail = max(viewWidthPx - 2f * 24f * density, viewWidthPx * 0.6f)
        return avail / maxPageWidthPt * zoom
    }

    /** Divider fraction for the split view, keeping each pane at least [minFraction]. */
    fun splitFraction(fraction: Float, minFraction: Float = 0.3f): Float = fraction.coerceIn(minFraction, 1f - minFraction)

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else v.coerceIn(lo, hi)
}
