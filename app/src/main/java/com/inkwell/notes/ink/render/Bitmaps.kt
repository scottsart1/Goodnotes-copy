package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import kotlin.math.max

/** Bitmap helpers for bitmaps coming from document sources. */
internal object Bitmaps {
    private const val TAG = "InkBitmaps"

    /**
     * Makes a source bitmap safe for software canvases (tiles, exports): HARDWARE bitmaps are
     * copied to ARGB_8888, and bitmaps whose longest side exceeds [maxSide] are scaled down.
     * Call off the main thread.
     */
    fun prepare(bitmap: Bitmap, maxSide: Int): Bitmap {
        var b = bitmap
        if (b.config == Bitmap.Config.HARDWARE) {
            b = b.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
        }
        val longest = max(b.width, b.height)
        if (maxSide > 0 && longest > maxSide) {
            val f = maxSide.toFloat() / longest
            try {
                b = Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "Out of memory downscaling an image; using it as is")
            }
        }
        return b
    }

    /** A bitmap drawable on [canvas]: hardware bitmaps only draw on hardware canvases. */
    fun drawable(bitmap: Bitmap, canvas: Canvas): Bitmap? {
        if (bitmap.isRecycled) return null
        if (bitmap.config != Bitmap.Config.HARDWARE || canvas.isHardwareAccelerated) return bitmap
        return try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (t: Throwable) { null }
    }
}
