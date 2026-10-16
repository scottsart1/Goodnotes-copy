package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ink.InkDocumentSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * PDF page backgrounds at a resolution matching the zoom. The current bitmap is kept (and drawn
 * blurry) until the sharper one arrives. Paper pages are probed once at low resolution; when the
 * source returns null the page is remembered as having no background. Main thread only.
 */
internal class BackgroundCache(private val scope: CoroutineScope) {
    var source: InkDocumentSource? = null
    /** Main thread: a page's background changed. */
    var onChanged: ((String) -> Unit)? = null

    private class Entry {
        var bitmap: Bitmap? = null
        var job: Job? = null
        var requestedWidth = 0
        var jobStartedAt = 0L
        var none = false
        var failures = 0
        var lastFailureAt = 0L
        var lastUsed = 0L
    }

    private val entries = HashMap<String, Entry>()

    fun bitmap(pageId: String): Bitmap? = entries[pageId]?.bitmap?.takeUnless { it.isRecycled }

    fun hasNoBackground(pageId: String): Boolean = entries[pageId]?.none == true

    /** True while a sharper bitmap is being rendered and the current one is too blurry. */
    fun isUpgrading(pageId: String, desiredWidth: Int): Boolean {
        val e = entries[pageId] ?: return false
        val job = e.job ?: return false
        if (!job.isActive) return false
        if (SystemClock.uptimeMillis() - e.jobStartedAt > UPGRADE_WAIT_MS) return false
        val w = e.bitmap?.width ?: 0
        return w < desiredWidth * 0.85f
    }

    /**
     * Makes sure a background of about [pxPerPt] resolution exists for [page]. [primary] pages
     * (the one the user is looking at) get a larger pixel budget.
     */
    fun ensure(page: PageMeta, pxPerPt: Float, primary: Boolean, viewportMoving: Boolean = false) {
        val src = source ?: return
        val e = entries.getOrPut(page.id) { Entry() }
        e.lastUsed = SystemClock.uptimeMillis()
        if (e.none) return
        if (page.widthPt <= 0f || page.heightPt <= 0f) { e.none = true; return }
        val probe = page.pdfPageIndex == null && e.bitmap == null
        val desired = if (probe) PROBE_WIDTH else desiredWidth(page, pxPerPt, primary)
        val current = e.bitmap?.width ?: 0
        if (current >= desired * 0.85f) return
        // While zooming or flinging keep the current (blurry) bitmap; upgrade once settled.
        if (viewportMoving && e.bitmap != null) return
        if (e.job?.isActive == true && e.requestedWidth >= desired * 0.85f) return
        if (e.failures > 0 && SystemClock.uptimeMillis() - e.lastFailureAt < RETRY_MS * e.failures) return
        e.job?.cancel()
        e.requestedWidth = desired
        e.jobStartedAt = SystemClock.uptimeMillis()
        val w = desired
        val h = ceil(w * page.heightPt / page.widthPt).toInt().coerceAtLeast(1)
        e.job = scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { src.renderBackground(page, w, h)?.let { Bitmaps.prepare(it, 0) } }
                if (source !== src) return@launch
                if (bmp == null) {
                    if (page.pdfPageIndex == null) e.none = true
                    else { e.failures++; e.lastFailureAt = SystemClock.uptimeMillis() }
                } else {
                    e.bitmap = bmp
                    e.failures = 0
                    onChanged?.invoke(page.id)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "Page background failed (${t.javaClass.simpleName})")
                e.failures++
                e.lastFailureAt = SystemClock.uptimeMillis()
            }
        }
    }

    private fun desiredWidth(page: PageMeta, pxPerPt: Float, primary: Boolean): Int {
        val maxPixels = if (primary) PRIMARY_PIXELS else SECONDARY_PIXELS
        val aspect = page.heightPt / page.widthPt
        val byZoom = page.widthPt * pxPerPt
        val byBudget = sqrt(maxPixels / aspect)
        val w = minOf(byZoom, byBudget, MAX_SIDE.toFloat() / maxOf(1f, aspect)).coerceAtLeast(MIN_WIDTH.toFloat())
        // Round up so small zoom changes do not trigger re-renders.
        return (ceil(w / 256f) * 256f).toInt()
    }

    fun drop(pageId: String) {
        entries.remove(pageId)?.job?.cancel()
    }

    /** Keeps only [keep] pages (others are released). */
    fun retain(keep: Set<String>) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (id, e) = it.next()
            if (id !in keep && !e.none) { e.job?.cancel(); it.remove() }
        }
    }

    fun clear() {
        entries.values.forEach { it.job?.cancel() }
        entries.clear()
    }

    companion object {
        private const val TAG = "InkBackgrounds"
        private const val PROBE_WIDTH = 256
        private const val MIN_WIDTH = 256
        private const val MAX_SIDE = 8192
        private const val PRIMARY_PIXELS = 12_000_000f
        private const val SECONDARY_PIXELS = 4_000_000f
        private const val RETRY_MS = 5_000L
        private const val UPGRADE_WAIT_MS = 450L
    }
}
