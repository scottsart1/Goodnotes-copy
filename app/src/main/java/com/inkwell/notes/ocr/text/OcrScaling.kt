package com.inkwell.notes.ocr.text

import kotlin.math.max
import kotlin.math.roundToInt

/** Image sizing for OCR: bounded work per page, enough resolution for small print. */
object OcrScaling {
    /** Longest edge (px) handed to the text recogniser. */
    const val MAX_EDGE = 2500
    /** Render resolution for scanned PDF pages. */
    const val RENDER_DPI = 200

    /** Factor (<= 1) that brings the longest edge down to [maxEdge]. */
    fun scaleFor(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Float {
        val longest = max(width, height)
        if (longest <= 0 || longest <= maxEdge) return 1f
        return maxEdge.toFloat() / longest
    }

    fun scaledSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val s = scaleFor(width, height, maxEdge)
        return (width * s).roundToInt().coerceAtLeast(1) to (height * s).roundToInt().coerceAtLeast(1)
    }

    /** Largest power-of-two decode subsampling that keeps the longest edge >= [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * Pixel size to render a page of [widthPt] x [heightPt] points at [dpi], capped so the
     * longest edge stays within [maxEdge].
     */
    fun renderSize(widthPt: Float, heightPt: Float, dpi: Int = RENDER_DPI, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val w = (widthPt / 72f * dpi).coerceAtLeast(1f)
        val h = (heightPt / 72f * dpi).coerceAtLeast(1f)
        val s = minOf(1f, maxEdge / max(w, h))
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    /** Maps a box from a [fromW] x [fromH] space into a [toW] x [toH] space. */
    fun mapBox(l: Float, t: Float, r: Float, b: Float, fromW: Int, fromH: Int, toW: Float, toH: Float): FloatArray {
        val sx = if (fromW > 0) toW / fromW else 1f
        val sy = if (fromH > 0) toH / fromH else 1f
        return floatArrayOf(
            (l * sx).coerceIn(0f, toW), (t * sy).coerceIn(0f, toH),
            (r * sx).coerceIn(0f, toW), (b * sy).coerceIn(0f, toH),
        )
    }
}
