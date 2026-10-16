package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.FloatList
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.viewport.PageRotation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws pages (paper, PDF background, vector content) onto any Canvas in page-point space.
 * The canvas must already be scaled so that 1 unit = 1 page point (canvas.scale(pxPerPt)).
 * Shared by the live canvas, export (PNG/PDF), thumbnails and AI vision snapshots.
 *
 * Thread safe: every thread gets its own paints, paths and outline buffers.
 */
object PageRenderer {
    private const val TAG = "PageRenderer"
    private const val MAX_BITMAP_PIXELS = 36_000_000L
    private const val MAX_BITMAP_SIDE = 16_384

    private class Scratch {
        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePath = Path()
        val shapePath = Path()
        val arrowPath = Path()
        val outliner = StrokeOutliner()
        val lines = FloatList(256)
        val rectF = RectF()
        val rect = Rect()
        val matrix = Matrix()
        val affine = FloatArray(6)
        val endpoints = FloatArray(4)
        val bounds = Bounds()
    }

    private val scratchLocal = ThreadLocal.withInitial { Scratch() }
    private val s: Scratch get() = scratchLocal.get()!!

    /** Emphasis halo colour for items highlighted during audio playback. */
    @Volatile var emphasisColor: Int = 0x662952CC

    // ---------------------------------------------------------------- Whole pages

    /**
     * Renders a full page into a new bitmap of [widthPx] width (displayed orientation, so
     * rotated pages come out rotated). Very tall results are scaled down to stay within memory
     * limits; unbounded pages render their content bounds.
     */
    fun renderToBitmap(
        page: PageMeta,
        content: PageContent,
        widthPx: Int,
        background: Bitmap? = null,
        images: (String) -> Bitmap? = { null },
        drawPaper: Boolean = true,
    ): Bitmap {
        val region: Bounds? = if (page.isInfinite) (contentBoundsOf(content)?.outset(24f) ?: Bounds(0f, 0f, 1000f, 1000f)) else null
        val rotation = if (page.isInfinite) 0 else PageRotation.normalize(page.rotation)
        val dw = region?.width ?: PageRotation.displayWidth(page.widthPt, page.heightPt, rotation)
        val dh = region?.height ?: PageRotation.displayHeight(page.widthPt, page.heightPt, rotation)
        var scale = widthPx.coerceAtLeast(1) / dw.coerceAtLeast(1f)
        val maxSide = MAX_BITMAP_SIDE.toFloat()
        if (dh * scale > maxSide) scale = maxSide / dh
        if (dw * scale * dh * scale > MAX_BITMAP_PIXELS) scale = sqrt(MAX_BITMAP_PIXELS / (dw * dh))
        var attempt = 0
        while (true) {
            try {
                val w = (dw * scale).toInt().coerceAtLeast(1)
                val h = (dh * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.scale(scale, scale)
                if (region != null) {
                    c.translate(-region.left, -region.top)
                    if (drawPaper) drawPaperRegion(c, 0f, 0f, page.paper, null, region, scale, infinite = true)
                    drawContent(c, content, images)
                } else {
                    val sc = s
                    PageRotation.affine(page.widthPt, page.heightPt, rotation, sc.affine)
                    sc.matrix.setValues(floatArrayOf(sc.affine[0], sc.affine[2], sc.affine[4], sc.affine[1], sc.affine[3], sc.affine[5], 0f, 0f, 1f))
                    c.concat(sc.matrix)
                    drawPage(c, page, content, background, images, drawPaper)
                }
                return bmp
            } catch (oom: OutOfMemoryError) {
                attempt++
                Log.w(TAG, "Out of memory rendering page bitmap, retrying smaller")
                if (attempt >= 4) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                scale *= 0.5f
            }
        }
    }

    fun drawPage(
        canvas: Canvas,
        page: PageMeta,
        content: PageContent,
        background: Bitmap?,
        images: (String) -> Bitmap?,
        drawPaper: Boolean = true,
    ) {
        canvas.save()
        if (!page.isInfinite) canvas.clipRect(0f, 0f, page.widthPt, page.heightPt)
        if (drawPaper) drawPaper(canvas, page.widthPt, page.heightPt, page.paper, background)
        drawContent(canvas, content, images)
        canvas.restore()
    }

    // ---------------------------------------------------------------- Paper

    fun drawPaper(canvas: Canvas, widthPt: Float, heightPt: Float, paper: PaperSpec, background: Bitmap?) {
        drawPaperRegion(canvas, widthPt, heightPt, paper, background, null, 4f, infinite = false)
    }

    /**
     * Paper fill, PDF background and line pattern restricted to [region] (page points; null =
     * whole page). [pxPerPt] picks a pattern density that stays legible when zoomed out. With
     * [infinite] the pattern covers the region without page edges or margins.
     */
    internal fun drawPaperRegion(
        canvas: Canvas, widthPt: Float, heightPt: Float, paper: PaperSpec, background: Bitmap?,
        region: Bounds?, pxPerPt: Float, infinite: Boolean,
    ) {
        val sc = s
        val l: Float; val t: Float; val r: Float; val b: Float
        if (infinite) {
            val reg = region ?: return
            l = reg.left; t = reg.top; r = reg.right; b = reg.bottom
        } else {
            l = max(0f, region?.left ?: 0f); t = max(0f, region?.top ?: 0f)
            r = min(widthPt, region?.right ?: widthPt); b = min(heightPt, region?.bottom ?: heightPt)
        }
        if (l >= r || t >= b) return
        sc.paperPaint.color = paper.color
        sc.paperPaint.style = Paint.Style.FILL
        if (infinite) canvas.drawRect(l, t, r, b, sc.paperPaint) else canvas.drawRect(0f, 0f, widthPt, heightPt, sc.paperPaint)
        if (background != null && !infinite) {
            sc.rectF.set(0f, 0f, widthPt, heightPt)
            sc.bitmapPaint.alpha = 255
            canvas.drawBitmap(background, null, sc.rectF, sc.bitmapPaint)
            return
        }
        val base = paper.effectiveSpacing()
        if (base <= 0f || paper.style == PaperStyle.BLANK) return
        val isDots = paper.style == PaperStyle.DOT_GRID
        // Level of detail: thin the pattern out instead of drawing sub-pixel mush.
        val minPx = if (isDots) 7f else 5f
        var step = base
        var multiple = 1
        while (step * pxPerPt < minPx && multiple < 100_000) { step *= 5f; multiple *= 5 }
        val lines = sc.lines
        lines.clear()
        sc.linePaint.color = paper.lineColor
        when (paper.style) {
            PaperStyle.RULED, PaperStyle.NARROW_RULED, PaperStyle.WIDE_RULED -> {
                val firstLine = if (infinite) 0f else base * 3f
                val last = if (infinite) b else min(b, heightPt - base * 0.5f)
                var y = if (infinite) floor(t / step) * step else firstLine + ceil(max(0f, t - firstLine) / step) * step
                while (y <= last) {
                    if (y >= t) { lines.add(l); lines.add(y); lines.add(r); lines.add(y) }
                    y += step
                }
                sc.linePaint.strokeWidth = 0.6f
                drawLines(canvas, lines, sc.linePaint)
                if (!infinite) {
                    val mx = base * 3f
                    if (mx in l..r) {
                        sc.linePaint.color = Color.argb(90, 220, 90, 90)
                        canvas.drawLine(mx, t, mx, b, sc.linePaint)
                    }
                }
            }
            PaperStyle.GRID, PaperStyle.GRAPH -> {
                val graph = paper.style == PaperStyle.GRAPH
                for (pass in 0..1) {
                    // pass 0: minor lines, pass 1: major lines (graph paper only).
                    if (pass == 1 && !graph) break
                    lines.clear()
                    var x = floor(l / step) * step
                    while (x <= r) {
                        val major = graph && Math.round(x / base) % 5 == 0
                        if ((pass == 1) == major && (infinite || (x >= 0f && x <= widthPt))) { lines.add(x); lines.add(t); lines.add(x); lines.add(b) }
                        x += step
                    }
                    var y = floor(t / step) * step
                    while (y <= b) {
                        val major = graph && Math.round(y / base) % 5 == 0
                        if ((pass == 1) == major && (infinite || (y >= 0f && y <= heightPt))) { lines.add(l); lines.add(y); lines.add(r); lines.add(y) }
                        y += step
                    }
                    sc.linePaint.strokeWidth = if (pass == 1) 0.9f else 0.45f
                    drawLines(canvas, lines, sc.linePaint)
                }
            }
            PaperStyle.DOT_GRID -> {
                var y = if (infinite) floor(t / step) * step else max(step, ceil(t / step) * step)
                while (y <= b && (infinite || y < heightPt)) {
                    var x = if (infinite) floor(l / step) * step else max(step, ceil(l / step) * step)
                    while (x <= r && (infinite || x < widthPt)) {
                        lines.add(x); lines.add(y)
                        x += step
                    }
                    y += step
                }
                sc.dotPaint.color = paper.lineColor
                sc.dotPaint.strokeWidth = 1.8f
                if (lines.size > 0) canvas.drawPoints(lines.toArray(), sc.dotPaint)
            }
            PaperStyle.BLANK -> Unit
        }
    }

    private fun drawLines(canvas: Canvas, lines: FloatList, paint: Paint) {
        if (lines.size >= 4) canvas.drawLines(lines.toArray(), paint)
    }

    // ---------------------------------------------------------------- Content

    /** Draws every item in paint order (see [RenderOrder]); highlighter lies beneath handwriting. */
    fun drawContent(canvas: Canvas, content: PageContent, images: (String) -> Bitmap?, emphasis: ((String) -> Boolean)? = null) {
        drawItems(canvas, content, RenderOrder.order(content), images, emphasis, null)
    }

    /**
     * Draws [ordered] items of [content]. [pathFor] may supply cached stroke outlines (tiles);
     * when it returns null the outline is built on the fly.
     */
    internal fun drawItems(
        canvas: Canvas,
        content: PageContent,
        ordered: List<Any>,
        images: (String) -> Bitmap?,
        emphasis: ((String) -> Boolean)?,
        pathFor: ((Stroke) -> Path?)?,
    ) {
        for (item in ordered) {
            when (item) {
                is Stroke -> {
                    val cached = pathFor?.invoke(item)
                    if (cached != null) drawStrokePath(canvas, item, cached, emphasis?.invoke(item.id) == true)
                    else drawStroke(canvas, item, emphasis?.invoke(item.id) == true)
                }
                is ShapeItem -> drawShape(canvas, item)
                is TextItem -> drawText(canvas, item)
                is ImageItem -> drawImage(canvas, item, images)
                is StickyItem -> drawSticky(canvas, item)
                is ConnectorItem -> drawConnector(canvas, item, content)
            }
        }
    }

    fun drawStroke(canvas: Canvas, stroke: Stroke, emphasized: Boolean = false) {
        if (stroke.pointCount == 0) return
        val sc = s
        sc.outliner.build(stroke, sc.strokePath)
        drawStrokePath(canvas, stroke, sc.strokePath, emphasized)
    }

    /** Draws a stroke whose outline [path] was already built. */
    internal fun drawStrokePath(canvas: Canvas, stroke: Stroke, path: Path, emphasized: Boolean) {
        val sc = s
        if (emphasized) {
            sc.haloPaint.color = emphasisColor
            sc.haloPaint.strokeWidth = max(2.5f, stroke.width * 1.2f)
            canvas.drawPath(path, sc.haloPaint)
        }
        InkPaints.configureStroke(sc.strokePaint, stroke.pen, stroke.color)
        canvas.drawPath(path, sc.strokePaint)
        sc.strokePaint.shader = null
        sc.strokePaint.blendMode = null
    }

    /** Width of a stroke at a sample, from nominal width, pressure and pen type. */
    fun widthAt(stroke: Stroke, pressure: Float, speed: Float = 0f): Float =
        stroke.width * PenModel.widthFactor(stroke.pen, pressure, stroke.pressureSensitivity, speed)

    /** Builds the filled outline (round caps, chisel ends for highlighters) of a variable-width stroke. */
    fun buildStrokeOutline(stroke: Stroke, out: Path) {
        s.outliner.build(stroke, out)
    }

    // ---------------------------------------------------------------- Shapes

    fun shapePath(shape: ShapeItem, out: Path) {
        out.reset()
        val p = shape.points
        when (shape.kind) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> if (p.size >= 4) {
                out.moveTo(p[0], p[1]); out.lineTo(p[2], p[3])
            }
            ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE, ShapeKind.ELLIPSE -> if (p.size >= 4) {
                val r = RectF(min(p[0], p[2]), min(p[1], p[3]), max(p[0], p[2]), max(p[1], p[3]))
                when (shape.kind) {
                    ShapeKind.RECTANGLE -> out.addRect(r, Path.Direction.CW)
                    ShapeKind.ROUNDED_RECTANGLE -> {
                        val cr = min(shape.cornerRadius, min(r.width(), r.height()) / 2f)
                        out.addRoundRect(r, cr, cr, Path.Direction.CW)
                    }
                    else -> out.addOval(r, Path.Direction.CW)
                }
                if (shape.rotation != 0f) {
                    val m = Matrix()
                    m.setRotate(shape.rotation, r.centerX(), r.centerY())
                    out.transform(m)
                }
            }
            ShapeKind.TRIANGLE, ShapeKind.POLYGON, ShapeKind.STAR -> if (p.size >= 4) {
                out.moveTo(p[0], p[1])
                var i = 2
                while (i + 1 < p.size) { out.lineTo(p[i], p[i + 1]); i += 2 }
                out.close()
            }
        }
    }

    fun drawShape(canvas: Canvas, shape: ShapeItem) {
        val sc = s
        shapePath(shape, sc.shapePath)
        val closed = ItemGeometry.isClosed(shape.kind)
        sc.shapePaint.blendMode = null
        if (shape.fillColor != 0 && closed) {
            sc.shapePaint.style = Paint.Style.FILL
            sc.shapePaint.color = shape.fillColor
            canvas.drawPath(sc.shapePath, sc.shapePaint)
        }
        sc.shapePaint.style = Paint.Style.STROKE
        sc.shapePaint.color = shape.strokeColor
        sc.shapePaint.strokeWidth = shape.strokeWidth
        canvas.drawPath(sc.shapePath, sc.shapePaint)
        val p = shape.points
        if (p.size >= 4 && (shape.kind == ShapeKind.ARROW || shape.kind == ShapeKind.DOUBLE_ARROW)) {
            drawArrowHead(canvas, p[0], p[1], p[2], p[3], shape.strokeWidth, shape.strokeColor)
            if (shape.kind == ShapeKind.DOUBLE_ARROW) drawArrowHead(canvas, p[2], p[3], p[0], p[1], shape.strokeWidth, shape.strokeColor)
        }
    }

    fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, width: Float, color: Int) {
        val sc = s
        if (abs(toX - fromX) + abs(toY - fromY) < 1e-3f) return
        val a = atan2(toY - fromY, toX - fromX)
        val len = 6f + width * 3f
        sc.arrowPath.reset()
        sc.arrowPath.moveTo(toX, toY)
        sc.arrowPath.lineTo(toX - len * cos(a - 0.45f), toY - len * sin(a - 0.45f))
        sc.arrowPath.lineTo(toX - len * cos(a + 0.45f), toY - len * sin(a + 0.45f))
        sc.arrowPath.close()
        sc.shapePaint.style = Paint.Style.FILL
        sc.shapePaint.color = color
        canvas.drawPath(sc.arrowPath, sc.shapePaint)
    }

    // ---------------------------------------------------------------- Boxes

    fun drawText(canvas: Canvas, item: TextItem) {
        val sc = s
        canvas.save()
        if (item.rotation != 0f) canvas.rotate(item.rotation, item.x + item.width / 2f, item.y + item.height / 2f)
        if (item.background != 0) {
            sc.boxPaint.color = item.background
            canvas.drawRoundRect(item.x, item.y, item.x + item.width, item.y + item.height, 4f, 4f, sc.boxPaint)
        }
        val tp = sc.textPaint
        tp.color = item.color
        tp.textSize = item.fontSizePt.coerceIn(1f, 1000f)
        tp.isFakeBoldText = item.bold
        tp.textSkewX = if (item.italic) -0.2f else 0f
        if (item.text.isNotEmpty()) {
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, max(1, item.width.toInt()))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            canvas.translate(item.x, item.y)
            layout.draw(canvas)
        }
        canvas.restore()
    }

    fun drawImage(canvas: Canvas, item: ImageItem, images: (String) -> Bitmap?) {
        val sc = s
        val bmp = images(item.assetId)
        canvas.save()
        if (item.rotation != 0f) canvas.rotate(item.rotation, item.x + item.width / 2f, item.y + item.height / 2f)
        sc.rectF.set(item.x, item.y, item.x + item.width, item.y + item.height)
        if (bmp != null && !bmp.isRecycled) {
            val l = (bmp.width * item.cropLeft).toInt().coerceIn(0, bmp.width - 1)
            val t = (bmp.height * item.cropTop).toInt().coerceIn(0, bmp.height - 1)
            val r = (bmp.width * (1f - item.cropRight)).toInt().coerceIn(l + 1, bmp.width)
            val b = (bmp.height * (1f - item.cropBottom)).toInt().coerceIn(t + 1, bmp.height)
            sc.rect.set(l, t, r, b)
            sc.bitmapPaint.alpha = (item.opacity * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bmp, sc.rect, sc.rectF, sc.bitmapPaint)
            sc.bitmapPaint.alpha = 255
        } else {
            sc.boxPaint.color = 0xFFEDEBE5.toInt()
            canvas.drawRect(sc.rectF, sc.boxPaint)
        }
        canvas.restore()
    }

    fun drawSticky(canvas: Canvas, item: StickyItem) {
        val sc = s
        sc.boxPaint.color = 0x1A000000
        canvas.drawRoundRect(item.x + 1f, item.y + 2f, item.x + item.width + 1f, item.y + item.height + 2f, 6f, 6f, sc.boxPaint)
        sc.boxPaint.color = item.color
        canvas.drawRoundRect(item.x, item.y, item.x + item.width, item.y + item.height, 6f, 6f, sc.boxPaint)
        if (item.text.isNotEmpty()) {
            val tp = sc.textPaint
            tp.color = 0xFF1A1916.toInt()
            tp.textSize = 14f
            tp.isFakeBoldText = false
            tp.textSkewX = 0f
            val w = max(1, (item.width - 20f).toInt())
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, w).setIncludePad(false).build()
            canvas.save()
            canvas.translate(item.x + 10f, item.y + 10f)
            canvas.clipRect(0f, 0f, item.width - 20f, item.height - 20f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    // ---------------------------------------------------------------- Connectors & bounds

    /** Bounds of any item by id (used for connector endpoints). */
    fun itemBounds(content: PageContent, id: String): RectF? =
        ItemGeometry.attachBounds(content, id, Bounds())?.toRectF()

    /** Resolves a connector's live endpoints: nearest edge midpoints of the attached items. */
    fun connectorEndpoints(c: ConnectorItem, content: PageContent): FloatArray =
        ItemGeometry.connectorEndpoints(c, content, FloatArray(4))

    fun drawConnector(canvas: Canvas, c: ConnectorItem, content: PageContent) {
        val sc = s
        val e = ItemGeometry.connectorEndpoints(c, content, sc.endpoints)
        val fx = e[0]; val fy = e[1]; val tx = e[2]; val ty = e[3]
        sc.shapePaint.style = Paint.Style.STROKE
        sc.shapePaint.color = c.color
        sc.shapePaint.strokeWidth = c.width
        canvas.drawLine(fx, fy, tx, ty, sc.shapePaint)
        if (c.arrowEnd) drawArrowHead(canvas, fx, fy, tx, ty, c.width, c.color)
        if (c.arrowStart) drawArrowHead(canvas, tx, ty, fx, fy, c.width, c.color)
        if (c.label.isNotEmpty()) {
            val tp = sc.textPaint
            tp.color = c.color
            tp.textSize = 12f
            tp.isFakeBoldText = false
            tp.textSkewX = 0f
            val mx = (fx + tx) / 2f; val my = (fy + ty) / 2f
            val w = tp.measureText(c.label)
            canvas.drawText(c.label, mx - w / 2f, my - 4f, tp)
        }
    }

    fun pointsBounds(points: FloatArray, stride: Int): RectF? {
        if (points.size < 2) return null
        return Geo.polylineBounds(points, stride, Bounds()).toRectF()
    }

    /** Union bounds of all items, or null when empty. */
    fun contentBounds(content: PageContent): RectF? = contentBoundsOf(content)?.toRectF()

    /** Plain-Kotlin variant of [contentBounds]. */
    fun contentBoundsOf(content: PageContent): Bounds? {
        val out = Bounds()
        val tmp = Bounds()
        content.strokes.forEach { out.union(ItemGeometry.bounds(it, tmp, content)) }
        content.shapes.forEach { out.union(ItemGeometry.bounds(it, tmp, content)) }
        content.texts.forEach { out.union(ItemGeometry.bounds(it, tmp, content)) }
        content.images.forEach { out.union(ItemGeometry.bounds(it, tmp, content)) }
        content.stickies.forEach { out.union(ItemGeometry.bounds(it, tmp, content)) }
        content.connectors.forEach { out.union(ItemGeometry.bounds(it, tmp, content)) }
        return if (out.isEmpty) null else out
    }

    private fun Bounds.toRectF(): RectF = RectF(left, top, right, bottom)

    /** Number of stroke samples, for cost estimates. */
    internal fun pointCount(s: Stroke): Int = s.points.size / InkPoints.STRIDE
}
