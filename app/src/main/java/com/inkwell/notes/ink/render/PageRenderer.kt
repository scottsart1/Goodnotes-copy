package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws pages (paper, PDF background, vector content) onto any Canvas in page-point space.
 * The canvas must already be scaled so that 1 unit = 1 page point (canvas.scale(pxPerPt)).
 * Shared by the live canvas, export (PNG/PDF), thumbnails and AI vision snapshots.
 */
object PageRenderer {

    /** Renders a full page into a new bitmap of [widthPx] width. */
    fun renderToBitmap(
        page: PageMeta,
        content: PageContent,
        widthPx: Int,
        background: Bitmap? = null,
        images: (String) -> Bitmap? = { null },
        drawPaper: Boolean = true,
    ): Bitmap {
        val w = if (page.isInfinite) contentBounds(content)?.width()?.coerceAtLeast(1f) ?: 1000f else page.widthPt
        val h = if (page.isInfinite) contentBounds(content)?.height()?.coerceAtLeast(1f) ?: 1000f else page.heightPt
        val scale = widthPx / w
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        if (page.isInfinite) {
            val b = contentBounds(content)
            c.drawColor(page.paper.color)
            if (b != null) c.translate(-b.left, -b.top)
        } else {
            drawPage(c, page, content, background, images, drawPaper)
        }
        if (page.isInfinite) drawContent(c, content, images)
        return bmp
    }

    fun drawPage(
        canvas: Canvas,
        page: PageMeta,
        content: PageContent,
        background: Bitmap?,
        images: (String) -> Bitmap?,
        drawPaper: Boolean = true,
    ) {
        if (drawPaper) drawPaper(canvas, page.widthPt, page.heightPt, page.paper, background)
        drawContent(canvas, content, images)
    }

    // ---------------------------------------------------------------- Paper

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun drawPaper(canvas: Canvas, widthPt: Float, heightPt: Float, paper: PaperSpec, background: Bitmap?) {
        paperPaint.color = paper.color
        canvas.drawRect(0f, 0f, widthPt, heightPt, paperPaint)
        if (background != null) {
            canvas.drawBitmap(background, null, RectF(0f, 0f, widthPt, heightPt), bitmapPaint)
            return
        }
        val s = paper.effectiveSpacing()
        if (s <= 0f || paper.style == PaperStyle.BLANK) return
        linePaint.color = paper.lineColor
        linePaint.strokeWidth = 0.6f
        when (paper.style) {
            PaperStyle.RULED, PaperStyle.NARROW_RULED, PaperStyle.WIDE_RULED -> {
                var y = s * 3f
                while (y < heightPt - s * 0.5f) {
                    canvas.drawLine(0f, y, widthPt, y, linePaint)
                    y += s
                }
                // Left margin.
                linePaint.color = Color.argb(90, 220, 90, 90)
                canvas.drawLine(s * 3f, 0f, s * 3f, heightPt, linePaint)
            }
            PaperStyle.GRID, PaperStyle.GRAPH -> {
                var x = 0f
                var i = 0
                while (x <= widthPt) {
                    linePaint.strokeWidth = if (paper.style == PaperStyle.GRAPH && i % 5 == 0) 0.9f else 0.45f
                    canvas.drawLine(x, 0f, x, heightPt, linePaint)
                    x += s; i++
                }
                var y = 0f
                i = 0
                while (y <= heightPt) {
                    linePaint.strokeWidth = if (paper.style == PaperStyle.GRAPH && i % 5 == 0) 0.9f else 0.45f
                    canvas.drawLine(0f, y, widthPt, y, linePaint)
                    y += s; i++
                }
            }
            PaperStyle.DOT_GRID -> {
                paperPaint.color = paper.lineColor
                var y = s
                while (y < heightPt) {
                    var x = s
                    while (x < widthPt) {
                        canvas.drawCircle(x, y, 0.9f, paperPaint)
                        x += s
                    }
                    y += s
                }
            }
            PaperStyle.BLANK -> Unit
        }
    }

    // ---------------------------------------------------------------- Content

    private enum class Kind { STROKE, HIGHLIGHT, SHAPE, TEXT, IMAGE, STICKY, CONNECTOR }
    private class Entry(val z: Int, val kind: Kind, val item: Any)

    /** Draws every item ordered by z; highlighter strokes go beneath ink of the same z. */
    fun drawContent(canvas: Canvas, content: PageContent, images: (String) -> Bitmap?, emphasis: ((String) -> Boolean)? = null) {
        val entries = ArrayList<Entry>(content.strokes.size + content.shapes.size + content.texts.size + content.images.size)
        content.strokes.forEach { entries += Entry(it.z * 2 + if (it.pen == PenType.HIGHLIGHTER) 0 else 1, if (it.pen == PenType.HIGHLIGHTER) Kind.HIGHLIGHT else Kind.STROKE, it) }
        content.shapes.forEach { entries += Entry(it.z * 2 + 1, Kind.SHAPE, it) }
        content.texts.forEach { entries += Entry(it.z * 2 + 1, Kind.TEXT, it) }
        content.images.forEach { entries += Entry(it.z * 2, Kind.IMAGE, it) }
        content.stickies.forEach { entries += Entry(it.z * 2, Kind.STICKY, it) }
        content.connectors.forEach { entries += Entry(it.z * 2, Kind.CONNECTOR, it) }
        entries.sortBy { it.z }
        for (e in entries) {
            when (e.kind) {
                Kind.STROKE, Kind.HIGHLIGHT -> drawStroke(canvas, e.item as Stroke, emphasis?.invoke(e.item.id) == true)
                Kind.SHAPE -> drawShape(canvas, e.item as ShapeItem)
                Kind.TEXT -> drawText(canvas, e.item as TextItem)
                Kind.IMAGE -> drawImage(canvas, e.item as ImageItem, images)
                Kind.STICKY -> drawSticky(canvas, e.item as StickyItem)
                Kind.CONNECTOR -> drawConnector(canvas, e.item as ConnectorItem, content)
            }
        }
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePath = Path()

    fun drawStroke(canvas: Canvas, stroke: Stroke, emphasized: Boolean = false) {
        val n = stroke.pointCount
        if (n == 0) return
        strokePaint.color = stroke.color
        strokePaint.blendMode = null
        when (stroke.pen) {
            PenType.HIGHLIGHTER -> {
                strokePaint.alpha = 115
                strokePaint.blendMode = BlendMode.MULTIPLY
            }
            PenType.PENCIL -> strokePaint.alpha = (Color.alpha(stroke.color) * 0.85f).toInt()
            else -> Unit
        }
        if (emphasized) strokePaint.setShadowLayer(3f, 0f, 0f, 0x802952CC.toInt()) else strokePaint.clearShadowLayer()
        buildStrokeOutline(stroke, strokePath)
        canvas.drawPath(strokePath, strokePaint)
        strokePaint.blendMode = null
        strokePaint.clearShadowLayer()
    }

    /** Width of a stroke at a sample, from nominal width, pressure and pen type. */
    fun widthAt(stroke: Stroke, pressure: Float, speed: Float = 0f): Float {
        val p = pressure.coerceIn(0f, 1f)
        val s = stroke.pressureSensitivity.coerceIn(0f, 1f)
        val w = stroke.width
        return when (stroke.pen) {
            PenType.BALLPOINT -> w * (1f - 0.25f * s + 0.25f * s * p)
            PenType.FOUNTAIN -> w * (0.45f + s * 0.9f * p.pow(0.8f) + (1f - s) * 0.55f)
            PenType.BRUSH -> w * (0.2f + s * 1.8f * p.pow(1.2f) + (1f - s) * 0.8f)
            PenType.PENCIL -> w * (0.7f + 0.5f * s * p)
            PenType.HIGHLIGHTER -> w
        }.coerceAtLeast(0.15f)
    }

    /** Builds a filled outline polygon (with round caps) for a variable-width stroke. */
    fun buildStrokeOutline(stroke: Stroke, out: Path) {
        out.reset()
        val pts = stroke.points
        val n = stroke.pointCount
        val st = InkPoints.STRIDE
        if (n == 1) {
            val r = widthAt(stroke, pts[InkPoints.PRESSURE]) / 2f
            out.addCircle(pts[0], pts[1], r, Path.Direction.CW)
            return
        }
        if (stroke.pen == PenType.HIGHLIGHTER) {
            // Chisel highlighter: constant width, square-ish ends.
            val hw = stroke.width / 2f
            val left = FloatArray(n * 2)
            val right = FloatArray(n * 2)
            for (i in 0 until n) {
                val x = pts[i * st]; val y = pts[i * st + 1]
                val j0 = max(0, i - 1); val j1 = min(n - 1, i + 1)
                val dx = pts[j1 * st] - pts[j0 * st]; val dy = pts[j1 * st + 1] - pts[j0 * st + 1]
                val len = hypot(dx, dy).coerceAtLeast(1e-4f)
                val nx = -dy / len * hw; val ny = dx / len * hw
                left[i * 2] = x + nx; left[i * 2 + 1] = y + ny
                right[i * 2] = x - nx; right[i * 2 + 1] = y - ny
            }
            out.moveTo(left[0], left[1])
            for (i in 1 until n) out.lineTo(left[i * 2], left[i * 2 + 1])
            for (i in n - 1 downTo 0) out.lineTo(right[i * 2], right[i * 2 + 1])
            out.close()
            return
        }
        val left = FloatArray(n * 2)
        val right = FloatArray(n * 2)
        val radius = FloatArray(n)
        for (i in 0 until n) {
            val x = pts[i * st]; val y = pts[i * st + 1]
            val j0 = max(0, i - 1); val j1 = min(n - 1, i + 1)
            val dx = pts[j1 * st] - pts[j0 * st]; val dy = pts[j1 * st + 1] - pts[j0 * st + 1]
            val len = hypot(dx, dy).coerceAtLeast(1e-4f)
            var r = widthAt(stroke, pts[i * st + InkPoints.PRESSURE]) / 2f
            // Taper the first/last samples slightly for a natural pen lift.
            if (stroke.pen == PenType.FOUNTAIN || stroke.pen == PenType.BRUSH) {
                val edge = min(i, n - 1 - i)
                if (edge < 3) r *= 0.55f + 0.15f * edge
            }
            radius[i] = r
            val nx = -dy / len * r; val ny = dx / len * r
            left[i * 2] = x + nx; left[i * 2 + 1] = y + ny
            right[i * 2] = x - nx; right[i * 2 + 1] = y - ny
        }
        out.moveTo(left[0], left[1])
        for (i in 1 until n) {
            val mx = (left[(i - 1) * 2] + left[i * 2]) / 2f; val my = (left[(i - 1) * 2 + 1] + left[i * 2 + 1]) / 2f
            out.quadTo(left[(i - 1) * 2], left[(i - 1) * 2 + 1], mx, my)
        }
        out.lineTo(left[(n - 1) * 2], left[(n - 1) * 2 + 1])
        // End cap.
        val ex = pts[(n - 1) * st]; val ey = pts[(n - 1) * st + 1]
        val endAngle = atan2(left[(n - 1) * 2 + 1] - ey, left[(n - 1) * 2] - ex)
        addArc(out, ex, ey, radius[n - 1], endAngle, -Math.PI.toFloat())
        out.lineTo(right[(n - 1) * 2], right[(n - 1) * 2 + 1])
        for (i in n - 2 downTo 0) {
            val mx = (right[(i + 1) * 2] + right[i * 2]) / 2f; val my = (right[(i + 1) * 2 + 1] + right[i * 2 + 1]) / 2f
            out.quadTo(right[(i + 1) * 2], right[(i + 1) * 2 + 1], mx, my)
        }
        out.lineTo(right[0], right[1])
        val sx = pts[0]; val sy = pts[1]
        val startAngle = atan2(right[1] - sy, right[0] - sx)
        addArc(out, sx, sy, radius[0], startAngle, -Math.PI.toFloat())
        out.close()
    }

    private fun addArc(path: Path, cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
        val steps = 6
        for (k in 1..steps) {
            val a = start + sweep * k / steps
            path.lineTo(cx + r * cos(a), cy + r * sin(a))
        }
    }

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val shapePath = Path()

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
                    ShapeKind.ROUNDED_RECTANGLE -> out.addRoundRect(r, shape.cornerRadius, shape.cornerRadius, Path.Direction.CW)
                    else -> out.addOval(r, Path.Direction.CW)
                }
                if (shape.rotation != 0f) {
                    val m = android.graphics.Matrix()
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
        shapePath(shape, shapePath)
        if (shape.fillColor != 0 && shape.kind !in setOf(ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW)) {
            shapePaint.style = Paint.Style.FILL
            shapePaint.color = shape.fillColor
            canvas.drawPath(shapePath, shapePaint)
        }
        shapePaint.style = Paint.Style.STROKE
        shapePaint.color = shape.strokeColor
        shapePaint.strokeWidth = shape.strokeWidth
        canvas.drawPath(shapePath, shapePaint)
        val p = shape.points
        if (p.size >= 4 && (shape.kind == ShapeKind.ARROW || shape.kind == ShapeKind.DOUBLE_ARROW)) {
            drawArrowHead(canvas, p[0], p[1], p[2], p[3], shape.strokeWidth, shape.strokeColor)
            if (shape.kind == ShapeKind.DOUBLE_ARROW) drawArrowHead(canvas, p[2], p[3], p[0], p[1], shape.strokeWidth, shape.strokeColor)
        }
    }

    private val arrowPath = Path()

    fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, width: Float, color: Int) {
        val a = atan2(toY - fromY, toX - fromX)
        val len = 6f + width * 3f
        arrowPath.reset()
        arrowPath.moveTo(toX, toY)
        arrowPath.lineTo(toX - len * cos(a - 0.45f), toY - len * sin(a - 0.45f))
        arrowPath.lineTo(toX - len * cos(a + 0.45f), toY - len * sin(a + 0.45f))
        arrowPath.close()
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = color
        canvas.drawPath(arrowPath, shapePaint)
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun drawText(canvas: Canvas, item: TextItem) {
        canvas.save()
        if (item.rotation != 0f) canvas.rotate(item.rotation, item.x + item.width / 2f, item.y + item.height / 2f)
        if (item.background != 0) {
            boxPaint.color = item.background
            canvas.drawRoundRect(item.x, item.y, item.x + item.width, item.y + item.height, 4f, 4f, boxPaint)
        }
        textPaint.color = item.color
        textPaint.textSize = item.fontSizePt
        textPaint.isFakeBoldText = item.bold
        textPaint.textSkewX = if (item.italic) -0.2f else 0f
        val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, textPaint, max(1, item.width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        canvas.translate(item.x, item.y)
        layout.draw(canvas)
        canvas.restore()
    }

    fun drawImage(canvas: Canvas, item: ImageItem, images: (String) -> Bitmap?) {
        val bmp = images(item.assetId)
        canvas.save()
        if (item.rotation != 0f) canvas.rotate(item.rotation, item.x + item.width / 2f, item.y + item.height / 2f)
        val dst = RectF(item.x, item.y, item.x + item.width, item.y + item.height)
        if (bmp != null) {
            val src = android.graphics.Rect(
                (bmp.width * item.cropLeft).toInt(), (bmp.height * item.cropTop).toInt(),
                (bmp.width * (1f - item.cropRight)).toInt(), (bmp.height * (1f - item.cropBottom)).toInt(),
            )
            bitmapPaint.alpha = (item.opacity * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bmp, src, dst, bitmapPaint)
            bitmapPaint.alpha = 255
        } else {
            boxPaint.color = 0xFFEDEBE5.toInt()
            canvas.drawRect(dst, boxPaint)
        }
        canvas.restore()
    }

    fun drawSticky(canvas: Canvas, item: StickyItem) {
        boxPaint.color = 0x22000000
        canvas.drawRoundRect(item.x + 1.5f, item.y + 2.5f, item.x + item.width + 1.5f, item.y + item.height + 2.5f, 6f, 6f, boxPaint)
        boxPaint.color = item.color
        canvas.drawRoundRect(item.x, item.y, item.x + item.width, item.y + item.height, 6f, 6f, boxPaint)
        if (item.text.isNotEmpty()) {
            textPaint.color = 0xFF1A1916.toInt()
            textPaint.textSize = 14f
            textPaint.isFakeBoldText = false
            textPaint.textSkewX = 0f
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, textPaint, max(1, (item.width - 20f).toInt())).build()
            canvas.save()
            canvas.translate(item.x + 10f, item.y + 10f)
            canvas.clipRect(0f, 0f, item.width - 20f, item.height - 20f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Bounds of any item by id (used for connector endpoints). */
    fun itemBounds(content: PageContent, id: String): RectF? {
        content.stickies.firstOrNull { it.id == id }?.let { return RectF(it.x, it.y, it.x + it.width, it.y + it.height) }
        content.texts.firstOrNull { it.id == id }?.let { return RectF(it.x, it.y, it.x + it.width, it.y + it.height) }
        content.images.firstOrNull { it.id == id }?.let { return RectF(it.x, it.y, it.x + it.width, it.y + it.height) }
        content.shapes.firstOrNull { it.id == id }?.let { s -> return pointsBounds(s.points, 2) }
        content.strokes.firstOrNull { it.id == id }?.let { s -> return pointsBounds(s.points, InkPoints.STRIDE) }
        return null
    }

    /** Resolves a connector's live endpoints: nearest edge midpoints of the attached items. */
    fun connectorEndpoints(c: ConnectorItem, content: PageContent): FloatArray {
        val fromB = c.fromItemId?.let { itemBounds(content, it) }
        val toB = c.toItemId?.let { itemBounds(content, it) }
        var fx = c.fromX; var fy = c.fromY; var tx = c.toX; var ty = c.toY
        val fc = fromB?.let { floatArrayOf(it.centerX(), it.centerY()) }
        val tc = toB?.let { floatArrayOf(it.centerX(), it.centerY()) }
        if (fromB != null) { val p = edgePoint(fromB, tc?.get(0) ?: tx, tc?.get(1) ?: ty); fx = p[0]; fy = p[1] }
        if (toB != null) { val p = edgePoint(toB, fc?.get(0) ?: fx, fc?.get(1) ?: fy); tx = p[0]; ty = p[1] }
        return floatArrayOf(fx, fy, tx, ty)
    }

    private fun edgePoint(r: RectF, towardX: Float, towardY: Float): FloatArray {
        val dx = towardX - r.centerX(); val dy = towardY - r.centerY()
        return if (kotlin.math.abs(dx) * r.height() > kotlin.math.abs(dy) * r.width()) {
            floatArrayOf(if (dx > 0) r.right else r.left, r.centerY())
        } else {
            floatArrayOf(r.centerX(), if (dy > 0) r.bottom else r.top)
        }
    }

    fun drawConnector(canvas: Canvas, c: ConnectorItem, content: PageContent) {
        val e = connectorEndpoints(c, content)
        shapePaint.style = Paint.Style.STROKE
        shapePaint.color = c.color
        shapePaint.strokeWidth = c.width
        canvas.drawLine(e[0], e[1], e[2], e[3], shapePaint)
        if (c.arrowEnd) drawArrowHead(canvas, e[0], e[1], e[2], e[3], c.width, c.color)
        if (c.arrowStart) drawArrowHead(canvas, e[2], e[3], e[0], e[1], c.width, c.color)
    }

    fun pointsBounds(points: FloatArray, stride: Int): RectF? {
        if (points.size < 2) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < points.size) {
            l = min(l, points[i]); r = max(r, points[i]); t = min(t, points[i + 1]); b = max(b, points[i + 1])
            i += stride
        }
        return RectF(l, t, r, b)
    }

    /** Union bounds of all items, or null when empty. */
    fun contentBounds(content: PageContent): RectF? {
        var out: RectF? = null
        fun add(r: RectF?) { if (r != null) { if (out == null) out = RectF(r) else out!!.union(r) } }
        content.strokes.forEach { s -> add(pointsBounds(s.points, InkPoints.STRIDE)?.apply { inset(-s.width, -s.width) }) }
        content.shapes.forEach { add(pointsBounds(it.points, 2)) }
        content.texts.forEach { add(RectF(it.x, it.y, it.x + it.width, it.y + it.height)) }
        content.images.forEach { add(RectF(it.x, it.y, it.x + it.width, it.y + it.height)) }
        content.stickies.forEach { add(RectF(it.x, it.y, it.x + it.width, it.y + it.height)) }
        return out
    }
}
