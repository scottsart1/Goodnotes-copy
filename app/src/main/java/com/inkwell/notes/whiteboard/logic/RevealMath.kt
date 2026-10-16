package com.inkwell.notes.whiteboard.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where to move the board's viewport: the zoom to use and the board point to centre on. */
data class RevealPlan(val zoom: Float, val centerX: Float, val centerY: Float) {
    fun changesZoom(current: Float): Boolean = zoom != current
}

/**
 * Viewport math for revealing a region of the infinite board (notes written at a moment of a
 * recording). The region is centred in the part of the canvas a side panel does not cover;
 * the zoom only changes when the region does not fit, or when the board is zoomed out so far
 * that handwriting would be unreadable.
 */
object RevealMath {
    /** Space kept around the region, as a fraction of its size on each side. */
    const val MARGIN = 0.15f

    /** Zoom below which revealed handwriting is brought up to a readable size (if it fits). */
    const val READABLE_ZOOM = 0.6f

    /** Changes smaller than this (relative) keep the current zoom, so a reveal never jitters. */
    private const val ZOOM_TOLERANCE = 0.02f

    /**
     * @param visible board region the canvas shows now (points), at [currentZoom].
     * @param viewWidthPx canvas width in pixels; [viewHeightPx] likewise.
     * @param target region to reveal (points).
     * @param obscuredRightPx pixels at the right edge covered by an overlay panel.
     */
    fun plan(
        visible: Box,
        viewWidthPx: Float,
        viewHeightPx: Float,
        currentZoom: Float,
        target: Box,
        obscuredRightPx: Float = 0f,
        minZoom: Float = 0.05f,
        maxZoom: Float = 2f,
        readableZoom: Float = READABLE_ZOOM,
    ): RevealPlan {
        if (visible.width <= 0f || viewWidthPx <= 0f || viewHeightPx <= 0f || currentZoom <= 0f || !currentZoom.isFinite()) {
            return RevealPlan(currentZoom, target.centerX, target.centerY)
        }
        // Never treat more than 60% of the canvas as covered: the target still has to show.
        val obscured = obscuredRightPx.coerceIn(0f, viewWidthPx * 0.6f)
        val freeW = viewWidthPx - obscured
        val pxPerPt = viewWidthPx / visible.width
        val tw = max(target.width, 1f) * (1f + 2f * MARGIN)
        val th = max(target.height, 1f) * (1f + 2f * MARGIN)
        // Zoom at which the padded target exactly fills the free area.
        val fit = currentZoom * min(freeW / (tw * pxPerPt), viewHeightPx / (th * pxPerPt))
        var zoom = min(fit, max(currentZoom, readableZoom)).coerceIn(minZoom, maxZoom)
        if (abs(zoom - currentZoom) <= currentZoom * ZOOM_TOLERANCE) zoom = currentZoom
        // Centre of the free area sits obscured/2 px left of the canvas centre.
        val pxPerPtAfter = pxPerPt * zoom / currentZoom
        return RevealPlan(zoom, target.centerX + obscured / 2f / pxPerPtAfter, target.centerY)
    }
}
