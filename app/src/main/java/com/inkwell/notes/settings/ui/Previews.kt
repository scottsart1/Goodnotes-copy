package com.inkwell.notes.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.settings.logic.PressureCurveMath
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Visual spacing used by the small style tiles, where true scale would be unreadable. */
internal fun tileSpacing(style: PaperStyle): Dp = when (style) {
    PaperStyle.BLANK -> 0.dp
    PaperStyle.RULED -> 7.dp
    PaperStyle.NARROW_RULED -> 5.dp
    PaperStyle.WIDE_RULED -> 10.dp
    PaperStyle.GRID -> 7.dp
    PaperStyle.DOT_GRID -> 7.dp
    PaperStyle.GRAPH -> 4.dp
}

/** Draws a paper background: colour plus the style's lines, grid or dots at [spacingPx]. */
internal fun DrawScope.drawPaper(style: PaperStyle, paper: Color, line: Color, spacingPx: Float, lineWidthPx: Float) {
    drawRect(paper)
    if (style == PaperStyle.BLANK || spacingPx < 2f) return
    val w = size.width
    val h = size.height
    when (style) {
        PaperStyle.RULED, PaperStyle.NARROW_RULED, PaperStyle.WIDE_RULED -> {
            var y = spacingPx * 2f
            while (y < h - spacingPx * 0.5f) {
                drawLine(line, Offset(0f, y), Offset(w, y), lineWidthPx)
                y += spacingPx
            }
        }
        PaperStyle.GRID -> {
            var x = spacingPx
            while (x < w) { drawLine(line, Offset(x, 0f), Offset(x, h), lineWidthPx); x += spacingPx }
            var y = spacingPx
            while (y < h) { drawLine(line, Offset(0f, y), Offset(w, y), lineWidthPx); y += spacingPx }
        }
        PaperStyle.DOT_GRID -> {
            val r = max(lineWidthPx * 1.1f, 1f)
            var y = spacingPx
            while (y < h) {
                var x = spacingPx
                while (x < w) { drawCircle(line, r, Offset(x, y)); x += spacingPx }
                y += spacingPx
            }
        }
        PaperStyle.GRAPH -> {
            val minor = line.copy(alpha = line.alpha * 0.55f)
            var i = 1
            var x = spacingPx
            while (x < w) {
                drawLine(if (i % 5 == 0) line else minor, Offset(x, 0f), Offset(x, h), if (i % 5 == 0) lineWidthPx * 1.4f else lineWidthPx)
                x += spacingPx; i++
            }
            i = 1
            var y = spacingPx
            while (y < h) {
                drawLine(if (i % 5 == 0) line else minor, Offset(0f, y), Offset(w, y), if (i % 5 == 0) lineWidthPx * 1.4f else lineWidthPx)
                y += spacingPx; i++
            }
        }
        PaperStyle.BLANK -> Unit
    }
}

/** A paper tile at a fixed, legible spacing (style picker). */
@Composable
internal fun PaperTile(style: PaperStyle, paperArgb: Int, lineArgb: Int, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Canvas(modifier) {
        val corner = CornerRadius(6.dp.toPx())
        drawRoundRect(Color(paperArgb), cornerRadius = corner)
        drawPaperClipped(style, Color(paperArgb), Color(lineArgb).copy(alpha = 1f), tileSpacing(style).toPx(), 1.dp.toPx())
        drawRoundRect(c.outline, cornerRadius = corner, style = Stroke(1.dp.toPx()))
    }
}

/** A page at true proportions and scale for the current template. */
@Composable
internal fun PagePreview(
    style: PaperStyle,
    paperArgb: Int,
    lineArgb: Int,
    spacingPt: Float,
    pageWidthPt: Float,
    modifier: Modifier = Modifier,
    description: String,
) {
    val c = Inkwell.colors
    Canvas(modifier.semantics { contentDescription = description }) {
        val corner = CornerRadius(4.dp.toPx())
        drawRoundRect(Color(paperArgb), cornerRadius = corner)
        val spacing = if (pageWidthPt > 0f) spacingPt * size.width / pageWidthPt else 0f
        drawPaperClipped(style, Color(paperArgb), Color(lineArgb), spacing, 1.dp.toPx() * 0.75f)
        drawRoundRect(c.outline, cornerRadius = corner, style = Stroke(1.dp.toPx()))
    }
}

private fun DrawScope.drawPaperClipped(style: PaperStyle, paper: Color, line: Color, spacingPx: Float, lineWidthPx: Float) {
    val inset = 1.dp.toPx()
    drawContext.canvas.save()
    drawContext.canvas.clipRect(inset, inset, size.width - inset, size.height - inset)
    drawPaper(style, paper, line, spacingPx, lineWidthPx)
    drawContext.canvas.restore()
}

/** The pressure response curve with a linear reference diagonal. */
@Composable
internal fun PressureCurveGraph(bias: Float, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }
    Canvas(modifier.semantics { contentDescription = "Pressure curve preview. ${PressureCurveMath.describe(bias)}" }) {
        val pad = 10.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        drawRoundRect(c.surfaceAlt, cornerRadius = CornerRadius(8.dp.toPx()))
        for (q in 1..3) {
            val f = q / 4f
            drawLine(c.outline, Offset(pad + w * f, pad), Offset(pad + w * f, pad + h), 1.dp.toPx())
            drawLine(c.outline, Offset(pad, pad + h * f), Offset(pad + w, pad + h * f), 1.dp.toPx())
        }
        drawLine(c.inkMuted.copy(alpha = 0.5f), Offset(pad, pad + h), Offset(pad + w, pad), 1.dp.toPx(), pathEffect = dash)
        val pts = PressureCurveMath.samples(bias, 64)
        val path = Path()
        for (i in 0 until pts.size / 2) {
            val x = pad + pts[i * 2] * w
            val y = pad + h - pts[i * 2 + 1] * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A sample stroke whose width follows a simulated pressure ramp through the curve. */
@Composable
internal fun PressureSampleStroke(bias: Float, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Canvas(modifier) {
        val steps = 96
        val left = 12.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val mid = size.height / 2f
        val amp = size.height * 0.22f
        val minW = 1.dp.toPx()
        val maxW = 7.dp.toPx()
        var prev: Offset? = null
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = left + (right - left) * t
            val y = mid + amp * sin(t * 2.0 * PI).toFloat()
            val p = PressureCurveMath.apply(PressureCurveMath.samplePressure(t), bias)
            val cur = Offset(x, y)
            prev?.let { drawLine(c.ink, it, cur, minW + (maxW - minW) * p, cap = StrokeCap.Round) }
            prev = cur
        }
    }
}
