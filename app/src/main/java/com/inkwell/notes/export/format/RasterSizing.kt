package com.inkwell.notes.export.format

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixel sizes for page rasters, bounded so a huge page or board never exhausts memory. */
object RasterSizing {
    /** Upper bound of one raster (ARGB_8888: 4 bytes per pixel, ~128 MB). */
    const val MAX_PIXELS = 32_000_000L
    const val MAX_SIDE = 12_000

    data class Size(val width: Int, val height: Int)

    /** Width in pixels of a [widthPt] wide page at [dpi]. */
    fun widthForDpi(widthPt: Float, dpi: Int): Int = (widthPt / 72f * dpi).roundToInt().coerceAtLeast(1)

    /**
     * Output size for content of [contentW] x [contentH] (any unit) drawn [requestedWidth]
     * pixels wide, scaled down to respect [maxPixels] and [maxSide].
     */
    fun fit(contentW: Float, contentH: Float, requestedWidth: Int, maxPixels: Long = MAX_PIXELS, maxSide: Int = MAX_SIDE): Size {
        val cw = contentW.coerceAtLeast(1f)
        val ch = contentH.coerceAtLeast(1f)
        var w = requestedWidth.coerceAtLeast(1).toDouble()
        var h = w * ch / cw
        val sideScale = minOf(1.0, maxSide / max(w, h))
        w *= sideScale; h *= sideScale
        val pixels = w * h
        if (pixels > maxPixels) {
            val s = sqrt(maxPixels / pixels)
            w *= s; h *= s
        }
        return Size(floor(w).toInt().coerceAtLeast(1), floor(h).toInt().coerceAtLeast(1))
    }

    /** Largest power-of-two sample size that keeps a decoded image at least [targetW] x [targetH]. */
    fun sampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
        var s = 1
        while (srcW / (s * 2) >= targetW && srcH / (s * 2) >= targetH) s *= 2
        return s
    }
}
