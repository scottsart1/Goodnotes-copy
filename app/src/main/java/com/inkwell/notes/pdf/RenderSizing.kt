package com.inkwell.notes.pdf

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Cache key of one rendered source-PDF page: renders within a width bucket share an entry. */
internal data class RenderKey(val docId: String, val pdfPageIndex: Int, val widthBucket: Int)

internal data class PixelSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * Pixel sizes for page renders. Requested widths are rounded up to a bucket (at most 12.5%
 * larger) so small zoom changes reuse a cached bitmap, the height follows the requested aspect
 * ratio, and the result is capped so a single render can never exhaust memory.
 */
internal object RenderSizing {
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 24_000_000L
    private const val SMALL_STEP = 16
    private const val SMALL_LIMIT = 256

    /** Rounds [widthPx] up: steps of 16 px up to 256 px, then 1/8 of the enclosing power of two. */
    fun widthBucket(widthPx: Int): Int {
        if (widthPx <= 0) return 0
        val step = if (widthPx <= SMALL_LIMIT) SMALL_STEP else Integer.highestOneBit(widthPx - 1) / 8
        return ((widthPx + step - 1) / step) * step
    }

    /** The bitmap size actually rendered for a request of [widthPx] x [heightPx]. */
    fun target(widthPx: Int, heightPx: Int): PixelSize? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val w = widthBucket(widthPx)
        val h = (w.toDouble() * heightPx / widthPx).roundToInt().coerceAtLeast(1)
        return cap(PixelSize(w, h))
    }

    /** Scales [size] down (keeping its aspect) to fit [MAX_SIDE] and [MAX_PIXELS]. */
    fun cap(size: PixelSize): PixelSize {
        val (w, h) = size
        if (w <= MAX_SIDE && h <= MAX_SIDE && size.pixels <= MAX_PIXELS) return size
        val bySide = min(MAX_SIDE.toDouble() / w, MAX_SIDE.toDouble() / h)
        val byPixels = sqrt(MAX_PIXELS.toDouble() / size.pixels)
        val s = min(1.0, min(bySide, byPixels))
        return PixelSize(floor(w * s).toInt().coerceAtLeast(1), floor(h * s).toInt().coerceAtLeast(1))
    }

    /** True when a cached bitmap of [cachedWidth] x [cachedHeight] can serve [target] (same bucket, same shape). */
    fun matches(cachedWidth: Int, cachedHeight: Int, target: PixelSize): Boolean {
        if (cachedWidth != target.width) return false
        val tolerance = maxOf(2, (target.height * 0.01).roundToInt())
        return kotlin.math.abs(cachedHeight - target.height) <= tolerance
    }

    /** Bitmap cache budget: about 1/8 of the heap, within sane bounds. */
    fun cacheBudgetBytes(maxHeapBytes: Long): Long =
        (maxHeapBytes / 8).coerceIn(16L * 1024 * 1024, 256L * 1024 * 1024)
}
