package com.inkwell.notes.export.format

import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.render.PageRenderer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix

/** Per-document PDF resources shared by every page: fonts, graphics states, images. */
internal class PdfDocResources(
    val doc: PDDocument,
    fontsFactory: () -> PdfFonts,
    val images: PdfImages,
) {
    private val fontsLazy = lazy(fontsFactory)
    val fonts: PdfFonts by fontsLazy
    val fontsLoaded: Boolean get() = fontsLazy.isInitialized()
    private val states = HashMap<String, PDExtendedGraphicsState>()

    fun state(fillAlpha: Float, strokeAlpha: Float, multiply: Boolean): PDExtendedGraphicsState {
        val fa = (fillAlpha.coerceIn(0f, 1f) * 1000).toInt() / 1000f
        val sa = (strokeAlpha.coerceIn(0f, 1f) * 1000).toInt() / 1000f
        return states.getOrPut("$fa/$sa/$multiply") {
            PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = fa
                strokingAlphaConstant = sa
                if (multiply) blendMode = BlendMode.MULTIPLY
            }
        }
    }
}

/**
 * Draws page items into a PDF content stream whose current transform maps Inkwell page
 * points (y down) to user space. Everything stays vector: strokes are filled outlines,
 * shapes are paths, text uses the embedded Figtree fonts (characters Figtree lacks are drawn
 * as glyph outlines of the system font), images are XObjects.
 */
internal class PdfCanvas(
    private val cs: PDPageContentStream,
    private val res: PdfDocResources,
    private val docId: String,
) {
    private val fallbackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------------------------ Primitives

    fun path(ops: List<PathOp>) {
        for (op in ops) when (op) {
            is PathOp.MoveTo -> cs.moveTo(op.x, op.y)
            is PathOp.LineTo -> cs.lineTo(op.x, op.y)
            is PathOp.CubicTo -> cs.curveTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
            PathOp.Close -> cs.closePath()
        }
    }

    fun polygons(polys: List<FloatArray>) {
        for (p in polys) {
            if (p.size < 6) continue
            cs.moveTo(p[0], p[1])
            var i = 2
            while (i + 1 < p.size) { cs.lineTo(p[i], p[i + 1]); i += 2 }
            cs.closePath()
        }
    }

    /** Runs [draw] (which must build a path) and fills it with [argb], optionally multiplied. */
    fun fill(argb: Int, alphaFactor: Float = 1f, multiply: Boolean = false, draw: () -> Unit) {
        val alpha = PdfColor.alpha(argb) * alphaFactor
        if (alpha <= 0.001f) return
        cs.saveGraphicsState()
        if (alpha < 0.999f || multiply) cs.setGraphicsStateParameters(res.state(alpha, 1f, multiply))
        cs.setNonStrokingColor(PdfColor.red(argb), PdfColor.green(argb), PdfColor.blue(argb))
        draw()
        cs.fill()
        cs.restoreGraphicsState()
    }

    fun strokePath(ops: List<PathOp>, argb: Int, width: Float) {
        val alpha = PdfColor.alpha(argb)
        if (alpha <= 0.001f || ops.isEmpty()) return
        cs.saveGraphicsState()
        if (alpha < 0.999f) cs.setGraphicsStateParameters(res.state(1f, alpha, false))
        cs.setStrokingColor(PdfColor.red(argb), PdfColor.green(argb), PdfColor.blue(argb))
        cs.setLineWidth(width.coerceAtLeast(0.01f))
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        path(ops)
        cs.stroke()
        cs.restoreGraphicsState()
    }

    // ------------------------------------------------------------------ Paper

    fun paper(paper: com.inkwell.notes.core.model.PaperSpec, widthPt: Float, heightPt: Float) {
        fill(paper.color or 0xFF000000.toInt()) { cs.addRect(0f, 0f, widthPt, heightPt) }
        val plan = PaperGeometry.plan(paper, widthPt, heightPt)
        plan.lines.groupBy { it.width to it.color }.forEach { (key, lines) ->
            val (width, color) = key
            val alpha = PdfColor.alpha(color)
            if (alpha <= 0.001f) return@forEach
            cs.saveGraphicsState()
            if (alpha < 0.999f) cs.setGraphicsStateParameters(res.state(1f, alpha, false))
            cs.setStrokingColor(PdfColor.red(color), PdfColor.green(color), PdfColor.blue(color))
            cs.setLineWidth(width)
            for (l in lines) { cs.moveTo(l.x1, l.y1); cs.lineTo(l.x2, l.y2) }
            cs.stroke()
            cs.restoreGraphicsState()
        }
        if (plan.dots.isNotEmpty()) {
            fill(plan.dotColor) {
                var i = 0
                while (i + 1 < plan.dots.size) {
                    path(ShapeGeometry.circle(plan.dots[i], plan.dots[i + 1], plan.dotRadius))
                    i += 2
                }
            }
        }
    }

    // ------------------------------------------------------------------ Items

    fun items(content: PageContent, include: (Any) -> Boolean = { true }) {
        for (item in ZOrder.ordered(content)) {
            if (!include(item)) continue
            when (item) {
                is Stroke -> stroke(item)
                is ShapeItem -> shape(item)
                is TextItem -> text(item)
                is ImageItem -> image(item)
                is StickyItem -> sticky(item)
                is ConnectorItem -> connector(item, content)
            }
        }
    }

    fun stroke(stroke: Stroke, polygons: List<FloatArray> = AndroidGeometry.strokeOutline(stroke)) {
        if (polygons.isEmpty()) return
        val (factor, multiply) = strokeAlpha(stroke.pen)
        fill(stroke.color, factor, multiply) { polygons(polygons) }
    }

    fun shape(shape: ShapeItem) {
        val ops = ShapeGeometry.path(shape)
        if (ops.isEmpty()) return
        if (shape.fillColor != 0 && shape.kind !in ShapeGeometry.OPEN_KINDS) fill(shape.fillColor) { path(ops) }
        strokePath(ops, shape.strokeColor, shape.strokeWidth)
        val p = shape.points
        if (p.size >= 4 && (shape.kind == com.inkwell.notes.core.model.ShapeKind.ARROW || shape.kind == com.inkwell.notes.core.model.ShapeKind.DOUBLE_ARROW)) {
            arrowHead(p[0], p[1], p[2], p[3], shape.strokeWidth, shape.strokeColor)
            if (shape.kind == com.inkwell.notes.core.model.ShapeKind.DOUBLE_ARROW) arrowHead(p[2], p[3], p[0], p[1], shape.strokeWidth, shape.strokeColor)
        }
    }

    private fun arrowHead(fx: Float, fy: Float, tx: Float, ty: Float, width: Float, color: Int) {
        if (kotlin.math.abs(tx - fx) + kotlin.math.abs(ty - fy) < 1e-3f) return
        fill(color) { path(ShapeGeometry.arrowHead(fx, fy, tx, ty, width)) }
    }

    fun connector(c: ConnectorItem, content: PageContent) {
        val e = PageRenderer.connectorEndpoints(c, content)
        strokePath(listOf(PathOp.MoveTo(e[0], e[1]), PathOp.LineTo(e[2], e[3])), c.color, c.width)
        if (c.arrowEnd) arrowHead(e[0], e[1], e[2], e[3], c.width, c.color)
        if (c.arrowStart) arrowHead(e[2], e[3], e[0], e[1], c.width, c.color)
    }

    fun text(item: TextItem) {
        cs.saveGraphicsState()
        if (item.rotation != 0f) cs.transform(matrix(Affine.rotation(item.rotation, item.x + item.width / 2f, item.y + item.height / 2f)))
        if (item.background != 0) fill(item.background) { path(ShapeGeometry.roundRect(item.x, item.y, item.x + item.width, item.y + item.height, 4f)) }
        textBox(item.text, item.x, item.y, item.width, item.fontSizePt, item.bold, item.italic, item.color)
        cs.restoreGraphicsState()
    }

    /** Lays out [text] like the canvas and draws it with its top-left at ([x], [y]). */
    fun textBox(text: String, x: Float, y: Float, width: Float, size: Float, bold: Boolean, italic: Boolean, color: Int) {
        val lines = TextLines.layout(text, width, size, bold, italic)
        for (line in lines) {
            textLine(line.text, x + line.left, y + line.baseline, size, bold, italic, color, maxWidth = width - line.left + 0.5f)
        }
    }

    fun sticky(item: StickyItem) {
        fill(0x1A000000) { path(ShapeGeometry.roundRect(item.x + 1f, item.y + 2f, item.x + item.width + 1f, item.y + item.height + 2f, 6f)) }
        fill(item.color) { path(ShapeGeometry.roundRect(item.x, item.y, item.x + item.width, item.y + item.height, 6f)) }
        if (item.text.isBlank() || item.width <= 20f || item.height <= 20f) return
        cs.saveGraphicsState()
        cs.addRect(item.x + 10f, item.y + 10f, item.width - 20f, item.height - 20f)
        cs.clip()
        textBox(item.text, item.x + 10f, item.y + 10f, item.width - 20f, 14f, false, false, 0xFF1A1916.toInt())
        cs.restoreGraphicsState()
    }

    fun image(item: ImageItem) {
        if (item.width <= 0f || item.height <= 0f) return
        cs.saveGraphicsState()
        if (item.rotation != 0f) cs.transform(matrix(Affine.rotation(item.rotation, item.x + item.width / 2f, item.y + item.height / 2f)))
        val img: PDImageXObject? = res.images.get(docId, item.assetId)
        if (img == null) {
            fill(0xFFEDEBE5.toInt()) { cs.addRect(item.x, item.y, item.width, item.height) }
        } else {
            val visW = (1f - item.cropLeft - item.cropRight).coerceIn(0.01f, 1f)
            val visH = (1f - item.cropTop - item.cropBottom).coerceIn(0.01f, 1f)
            val cropped = visW < 0.999f || visH < 0.999f
            if (cropped) {
                cs.addRect(item.x, item.y, item.width, item.height)
                cs.clip()
            }
            if (item.opacity < 0.999f) cs.setGraphicsStateParameters(res.state(item.opacity.coerceIn(0f, 1f), 1f, false))
            val fw = item.width / visW
            val fh = item.height / visH
            val fx = item.x - item.cropLeft * fw
            val fy = item.y - item.cropTop * fh
            cs.drawImage(img, Matrix(fw, 0f, 0f, -fh, fx, fy + fh))
        }
        cs.restoreGraphicsState()
    }

    // ------------------------------------------------------------------ Text

    private class Run(val text: String, val covered: Boolean)

    private fun runs(text: String, bold: Boolean): List<Run> {
        val out = ArrayList<Run>()
        val sb = StringBuilder()
        var cur: Boolean? = null
        var i = 0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\t'.code || Character.isISOControl(cp)) cp = ' '.code
            val covered = res.fonts.covers(cp, bold)
            if (cur != null && covered != cur) {
                out += Run(sb.toString(), cur)
                sb.setLength(0)
            }
            cur = covered
            sb.appendCodePoint(cp)
        }
        if (cur != null && sb.isNotEmpty()) out += Run(sb.toString(), cur)
        return out
    }

    /**
     * Draws one line of text with its baseline at ([x], [baseline]), compressed horizontally
     * when wider than [maxWidth] (> 0) so it never leaves its box.
     */
    fun textLine(text: String, x: Float, baseline: Float, size: Float, bold: Boolean, italic: Boolean, color: Int, maxWidth: Float = 0f) {
        if (text.isBlank()) return
        val runs = runs(text, bold)
        fallbackPaint.textSize = size
        fallbackPaint.isFakeBoldText = bold
        fallbackPaint.textSkewX = if (italic) -0.2f else 0f
        val widths = runs.map { if (it.covered) res.fonts.width(it.text, size, bold) else fallbackPaint.measureText(it.text) }
        val total = widths.sum()
        val h = if (maxWidth > 0f && total > maxWidth) maxWidth / total else 1f
        val alpha = PdfColor.alpha(color)
        if (alpha <= 0.001f) return
        cs.saveGraphicsState()
        if (alpha < 0.999f) cs.setGraphicsStateParameters(res.state(alpha, alpha, false))
        cs.setNonStrokingColor(PdfColor.red(color), PdfColor.green(color), PdfColor.blue(color))
        var cx = x
        runs.forEachIndexed { idx, run ->
            if (run.covered) {
                cs.beginText()
                cs.setFont(res.fonts.font(bold), size)
                cs.setTextMatrix(Matrix(h, 0f, if (italic) 0.2f else 0f, -1f, cx, baseline))
                cs.showText(run.text)
                cs.endText()
            } else if (run.text.isNotBlank()) {
                val glyphs = Path()
                fallbackPaint.getTextPath(run.text, 0, run.text.length, 0f, 0f, glyphs)
                val m = android.graphics.Matrix()
                m.setScale(h, 1f)
                m.postTranslate(cx, baseline)
                glyphs.transform(m)
                val polys = AndroidGeometry.polygons(glyphs, 0.02f)
                if (polys.isNotEmpty()) {
                    polygons(polys)
                    cs.fill()
                }
            }
            cx += widths[idx] * h
        }
        cs.restoreGraphicsState()
    }

    /**
     * Invisible, selectable text fitted to a box (search layer for recognised handwriting
     * and OCR). Characters the embedded font lacks become spaces so words stay separate.
     */
    fun invisibleText(text: String, left: Float, top: Float, right: Float, bottom: Float) {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || right <= left || bottom <= top) return
        val lineH = (bottom - top) / lines.size
        val size = (lineH * 0.85f).coerceIn(1f, 400f)
        val font = res.fonts.font(false)
        cs.saveGraphicsState()
        lines.forEachIndexed { i, raw ->
            val sb = StringBuilder()
            var k = 0
            while (k < raw.length) {
                val cp = raw.codePointAt(k)
                k += Character.charCount(cp)
                if (!Character.isISOControl(cp) && res.fonts.covers(cp, false)) sb.appendCodePoint(cp) else sb.append(' ')
            }
            val line = sb.toString().trim()
            if (line.isEmpty()) return@forEachIndexed
            val natural = res.fonts.width(line, size, false).coerceAtLeast(0.01f)
            val h = ((right - left) / natural).coerceIn(0.05f, 20f)
            val baseline = top + lineH * (i + 1) - lineH * 0.2f
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            cs.setFont(font, size)
            cs.setTextMatrix(Matrix(h, 0f, 0f, -1f, left, baseline))
            cs.showText(line)
            cs.endText()
        }
        cs.restoreGraphicsState()
    }

    companion object {
        fun matrix(a: Affine) = Matrix(a.a, a.b, a.c, a.d, a.e, a.f)

        /** Opacity factor and blend of a pen, as the canvas paints it. */
        fun strokeAlpha(pen: PenType): Pair<Float, Boolean> = when (pen) {
            PenType.HIGHLIGHTER -> HIGHLIGHTER_ALPHA to true
            PenType.PENCIL -> 0.85f to false
            else -> 1f to false
        }

        const val HIGHLIGHTER_ALPHA = 0.45f
    }
}
