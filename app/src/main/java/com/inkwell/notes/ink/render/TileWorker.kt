package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.model.ItemIndex
import com.inkwell.notes.ink.viewport.PageRotation
import java.util.IdentityHashMap
import kotlin.math.floor

/** A unit of off-main-thread rendering. Every field is an immutable snapshot. */
internal class RenderJob(
    val kind: Kind,
    val pageId: String,
    val meta: PageMeta,
    val infinite: Boolean,
    val content: PageContent,
    /** Visual version the result will represent. */
    val version: Long,
    /** Pixels per page point. */
    val scale: Float,
    val ix: Int,
    val iy: Int,
    val tileSize: Int,
    val background: Bitmap?,
    /** Incremental tiles: the installed tile to draw on top of, and the items to add. */
    val base: Bitmap? = null,
    val baseVersion: Long = -1L,
    val addItems: List<Any>? = null,
    /** Snapshots: region in displayed page points. */
    val region: Bounds? = null,
    val levelScale: Float = scale,
) {
    enum class Kind { TILE, INCREMENT, SNAPSHOT }

    @Volatile var result: Bitmap? = null
}

/**
 * Background renderer for committed content. Owns a single thread, a stroke-outline cache and
 * per-page spatial indexes, so the main thread only ever draws finished bitmaps.
 */
internal class TileWorker(private val images: ImageCache) {
    private val thread = HandlerThread("InkTiles", Process.THREAD_PRIORITY_DISPLAY + 2).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var quit = false

    // ---- Worker-thread state
    private val outliner = StrokeOutliner()
    private val paths = StrokePathCache(outliner, budgetPoints = 1_600_000)
    private val indexes = HashMap<String, Pair<PageContent, ItemIndex>>()
    private val boundsCaches = HashMap<String, IdentityHashMap<Any, Bounds>>()
    private val matrix = Matrix()
    private val affine = FloatArray(6)
    private val region = Bounds()

    fun submit(job: RenderJob, done: (RenderJob) -> Unit) {
        if (quit) return
        handler.post {
            if (quit) return@post
            try {
                job.result = render(job)
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "Out of memory rendering a tile")
                paths.clear()
                job.result = null
            } catch (t: Throwable) {
                Log.e(TAG, "Tile rendering failed", t)
                job.result = null
            }
            main.post { if (!quit) done(job) }
        }
    }

    /** Drops cached indexes of pages no longer bound. */
    fun forgetPages(keep: Set<String>) {
        handler.post {
            indexes.keys.retainAll(keep)
            boundsCaches.keys.retainAll(keep)
        }
    }

    fun trimMemory() { handler.post { paths.clear(); indexes.clear(); boundsCaches.clear() } }

    fun quit() {
        quit = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ Rendering

    private fun indexFor(job: RenderJob): ItemIndex {
        val cached = indexes[job.pageId]
        if (cached != null && cached.first === job.content) return cached.second
        val cache = boundsCaches.getOrPut(job.pageId) { IdentityHashMap() }
        val idx = ItemIndex.build(job.content, cache)
        indexes[job.pageId] = job.content to idx
        return idx
    }

    private fun render(job: RenderJob): Bitmap? = when (job.kind) {
        RenderJob.Kind.TILE, RenderJob.Kind.INCREMENT -> renderTile(job)
        RenderJob.Kind.SNAPSHOT -> renderSnapshot(job)
    }

    private fun pathFor(stroke: Stroke): Path = paths.get(stroke)

    private fun renderTile(job: RenderJob): Bitmap? {
        val t = job.tileSize
        val base = job.base
        val bmp = if (job.kind == RenderJob.Kind.INCREMENT && base != null && !base.isRecycled) base.copy(Bitmap.Config.ARGB_8888, true)
        else Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        bmp ?: return null
        val c = Canvas(bmp)
        val s = job.scale
        c.translate(-job.ix * t.toFloat(), -job.iy * t.toFloat())
        c.scale(s, s)
        val meta = job.meta
        // Tile area in displayed page points.
        val dl = job.ix * t / s; val dt = job.iy * t / s
        val dr = (job.ix + 1) * t / s; val db = (job.iy + 1) * t / s
        val w = meta.widthPt; val h = meta.heightPt
        val rotation = if (job.infinite) 0 else PageRotation.normalize(meta.rotation)
        if (!job.infinite) {
            c.clipRect(0f, 0f, PageRotation.displayWidth(w, h, rotation), PageRotation.displayHeight(w, h, rotation))
            PageRotation.affine(w, h, rotation, affine)
            matrix.setValues(floatArrayOf(affine[0], affine[2], affine[4], affine[1], affine[3], affine[5], 0f, 0f, 1f))
            c.concat(matrix)
        }
        displayToPageRegion(dl, dt, dr, db, w, h, rotation, job.infinite, region)
        val items: List<Any>
        if (job.kind == RenderJob.Kind.INCREMENT && job.addItems != null) {
            items = job.addItems
        } else {
            PageRenderer.drawPaperRegion(c, w, h, meta.paper, job.background, region, s, job.infinite)
            val q = region.copy().outset(1f)
            items = indexFor(job).queryOrdered(q).map { it.item }
        }
        PageRenderer.drawItems(c, job.content, items, images::get, null, ::pathFor)
        return bmp
    }

    private fun renderSnapshot(job: RenderJob): Bitmap? {
        val reg = job.region ?: return null
        val s = job.scale
        val wpx = (reg.width * s).toInt().coerceIn(1, 4096)
        val hpx = (reg.height * s).toInt().coerceIn(1, 4096)
        val config = if (job.infinite) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val bmp = Bitmap.createBitmap(wpx, hpx, config)
        val c = Canvas(bmp)
        c.scale(wpx / reg.width, hpx / reg.height)
        c.translate(-reg.left, -reg.top)
        val meta = job.meta
        val w = meta.widthPt; val h = meta.heightPt
        val rotation = if (job.infinite) 0 else PageRotation.normalize(meta.rotation)
        if (!job.infinite) {
            PageRotation.affine(w, h, rotation, affine)
            matrix.setValues(floatArrayOf(affine[0], affine[2], affine[4], affine[1], affine[3], affine[5], 0f, 0f, 1f))
            c.concat(matrix)
        }
        displayToPageRegion(reg.left, reg.top, reg.right, reg.bottom, w, h, rotation, job.infinite, region)
        PageRenderer.drawPaperRegion(c, w, h, meta.paper, job.background, region, s, job.infinite)
        val items = indexFor(job).queryOrdered(region.copy().outset(1f)).map { it.item }
        PageRenderer.drawItems(c, job.content, items, images::get, null, ::pathFor)
        return bmp
    }

    companion object {
        private const val TAG = "InkTiles"

        /** Converts a displayed-page rectangle into page coordinates (axis aligned for 90 degree steps). */
        fun displayToPageRegion(dl: Float, dt: Float, dr: Float, db: Float, w: Float, h: Float, rotation: Int, infinite: Boolean, out: Bounds) {
            if (infinite || rotation == 0) { out.set(dl, dt, dr, db); return }
            val p = FloatArray(2)
            out.setEmpty()
            PageRotation.fromDisplay(dl, dt, w, h, rotation, p); out.union(p[0], p[1])
            PageRotation.fromDisplay(dr, db, w, h, rotation, p); out.union(p[0], p[1])
            PageRotation.fromDisplay(dl, db, w, h, rotation, p); out.union(p[0], p[1])
            PageRotation.fromDisplay(dr, dt, w, h, rotation, p); out.union(p[0], p[1])
        }

        fun tileIndex(v: Float, tileSize: Int, scale: Float): Int = floor(v * scale / tileSize).toInt()
    }
}

/** LRU cache of stroke outline paths, bounded by total sample count. Worker thread only. */
internal class StrokePathCache(private val outliner: StrokeOutliner, private val budgetPoints: Int) {
    private class Entry(val points: FloatArray, val width: Float, val pen: PenType, val sensitivity: Float, val path: Path, val cost: Int)

    private val map = LinkedHashMap<String, Entry>(256, 0.75f, true)
    private var total = 0

    fun get(stroke: Stroke): Path {
        val e = map[stroke.id]
        if (e != null && e.points === stroke.points && e.width == stroke.width && e.pen == stroke.pen && e.sensitivity == stroke.pressureSensitivity) return e.path
        if (e != null) { map.remove(stroke.id); total -= e.cost }
        val path = Path()
        outliner.build(stroke, path)
        val cost = stroke.pointCount + 8
        map[stroke.id] = Entry(stroke.points, stroke.width, stroke.pen, stroke.pressureSensitivity, path, cost)
        total += cost
        if (total > budgetPoints) {
            val it = map.entries.iterator()
            while (total > budgetPoints * 3 / 4 && it.hasNext()) {
                val victim = it.next()
                total -= victim.value.cost
                it.remove()
            }
        }
        return path
    }

    fun clear() { map.clear(); total = 0 }
}
