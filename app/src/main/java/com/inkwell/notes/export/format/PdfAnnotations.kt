package com.inkwell.notes.export.format

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import java.util.Calendar
import kotlin.math.sqrt

/**
 * Editable PDF annotations for "keep annotations editable" exports: Ink annotations for
 * strokes and FreeText annotations for text boxes, each with an appearance stream drawn
 * exactly like the flattened export so every viewer shows the same ink.
 */
internal class PdfAnnotations(private val res: PdfDocResources, private val docId: String) {

    /** Ink annotation for [stroke]; [toUser] maps page points to user space. */
    fun ink(stroke: Stroke, toUser: Affine, modified: Calendar): PDAnnotation? {
        val outline = AndroidGeometry.strokeOutline(stroke)
        if (outline.isEmpty()) return null
        val userOutline = outline.map { toUser.mapPoints(it) }
        val rect = boundsWithMargin(userOutline, 1f) ?: return null

        val annot = PDAnnotationMarkup()
        annot.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
        annot.rectangle = rect
        annot.setInkList(arrayOf(centerline(stroke, toUser)))
        annot.color = rgb(stroke.color)
        annot.borderStyle = PDBorderStyleDictionary().apply { width = stroke.width * scaleOf(toUser) }
        val (alpha, _) = PdfCanvas.strokeAlpha(stroke.pen)
        val opacity = PdfColor.alpha(stroke.color) * alpha
        if (opacity < 0.999f) annot.constantOpacity = opacity
        if (stroke.pen == PenType.HIGHLIGHTER) annot.subject = "Highlight"
        common(annot, stroke.id, modified)
        annot.appearance = appearance(rect) { canvas, cs ->
            cs.transform(PdfCanvas.matrix(toUser))
            // Opacity comes from /CA; the appearance keeps the pen's blend.
            val opaque = (stroke.color.toLong() or 0xFF000000L).toInt()
            canvas.fill(opaque, 1f, stroke.pen == PenType.HIGHLIGHTER) { canvas.polygons(outline) }
        }
        return annot
    }

    /** FreeText annotation for an unrotated text box. */
    fun freeText(item: TextItem, toUser: Affine, modified: Calendar): PDAnnotation? {
        if (item.text.isBlank() || item.width <= 0f || item.height <= 0f) return null
        val r = toUser.mapRect(item.x, item.y, item.x + item.width, item.y + item.height)
        val rect = PDRectangle(r[0], r[1], r[2] - r[0], r[3] - r[1])
        val annot = PDAnnotationMarkup()
        annot.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_FREETEXT)
        annot.rectangle = rect
        annot.contents = item.text
        val size = item.fontSizePt * scaleOf(toUser)
        val c = item.color
        annot.defaultAppearance = "/Helv %.2f Tf %.3f %.3f %.3f rg".format(java.util.Locale.ROOT, size, PdfColor.red(c), PdfColor.green(c), PdfColor.blue(c))
        annot.defaultStyleString = "font: %.1fpt Helvetica; color:#%06X".format(java.util.Locale.ROOT, size, c and 0xFFFFFF)
        annot.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
        if (item.background != 0) annot.color = rgb(item.background)
        common(annot, item.id, modified)
        annot.appearance = appearance(rect) { canvas, cs ->
            cs.transform(PdfCanvas.matrix(toUser))
            if (item.background != 0) canvas.fill(item.background) {
                canvas.path(ShapeGeometry.roundRect(item.x, item.y, item.x + item.width, item.y + item.height, 4f))
            }
            canvas.textBox(item.text, item.x, item.y, item.width, item.fontSizePt, item.bold, item.italic, item.color)
        }
        return annot
    }

    private fun common(annot: PDAnnotationMarkup, id: String, modified: Calendar) {
        annot.annotationName = id
        annot.isPrinted = true
        annot.titlePopup = "Inkwell"
        annot.setModifiedDate(modified)
        annot.creationDate = modified
    }

    private fun appearance(rect: PDRectangle, draw: (PdfCanvas, PDPageContentStream) -> Unit): PDAppearanceDictionary {
        val stream = PDAppearanceStream(res.doc)
        stream.bBox = PDRectangle(rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height)
        stream.resources = PDResources()
        PDPageContentStream(res.doc, stream).use { cs -> draw(PdfCanvas(cs, res, docId), cs) }
        return PDAppearanceDictionary().apply { setNormalAppearance(stream) }
    }

    companion object {
        fun rgb(argb: Int) = PDColor(floatArrayOf(PdfColor.red(argb), PdfColor.green(argb), PdfColor.blue(argb)), PDDeviceRGB.INSTANCE)

        /** Uniform scale of an affine transform (for line widths and font sizes). */
        fun scaleOf(t: Affine): Float = sqrt(kotlin.math.abs(t.a * t.d - t.b * t.c)).coerceAtLeast(1e-4f)

        /** Stroke centre line in user space: x1 y1 x2 y2 ... */
        fun centerline(stroke: Stroke, toUser: Affine): FloatArray {
            val n = stroke.pointCount
            val out = FloatArray(maxOf(n, 1) * 2)
            val p = stroke.points
            for (i in 0 until n) {
                val x = p[i * InkPoints.STRIDE]
                val y = p[i * InkPoints.STRIDE + 1]
                out[i * 2] = toUser.mapX(x, y)
                out[i * 2 + 1] = toUser.mapY(x, y)
            }
            return out
        }

        fun boundsWithMargin(polys: List<FloatArray>, margin: Float): PDRectangle? {
            var l = Float.MAX_VALUE; var b = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = -Float.MAX_VALUE
            for (p in polys) {
                val bb = Affine.boundsOf(p)
                l = minOf(l, bb[0]); b = minOf(b, bb[1]); r = maxOf(r, bb[2]); t = maxOf(t, bb[3])
            }
            if (l > r || b > t) return null
            return PDRectangle(l - margin, b - margin, (r - l) + 2 * margin, (t - b) + 2 * margin)
        }
    }
}
