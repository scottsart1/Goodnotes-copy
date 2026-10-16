package com.inkwell.notes.library.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.library.CardDecor
import com.inkwell.notes.ui.theme.Inkwell
import java.io.File
import kotlin.math.max

private const val A4_ASPECT = 595f / 842f
private const val WHITEBOARD_ASPECT = 4f / 3f

/** Page aspect (width / height) used before the real thumbnail is known. */
internal fun placeholderAspect(kind: DocKind): Float = if (kind == DocKind.WHITEBOARD) WHITEBOARD_ASPECT else A4_ASPECT

/**
 * Paper-like document thumbnail inside a frame of [frameAspect]. Shows the rendered cover
 * (first page, its own aspect) when available, otherwise blank paper in the default style or
 * a PDF / whiteboard / typed placeholder. The page sits on the frame's bottom edge like paper
 * on a shelf.
 */
@Composable
internal fun DocThumbnail(
    doc: DocumentMeta,
    decor: CardDecor?,
    defaultPaper: PaperSpec,
    modifier: Modifier = Modifier,
    frameAspect: Float = 0.8f,
    selected: Boolean = false,
    corner: Dp = 8.dp,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = Inkwell.colors
    val shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(corner)
    BoxWithConstraints(modifier.aspectRatio(frameAspect), contentAlignment = Alignment.BottomCenter) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceIn(48, 1200)
        val path = decor?.thumbnailPath
        val context = LocalContext.current
        val painter = if (path != null) {
            val request = remember(decor.thumbnailKey, widthPx) {
                ImageRequest.Builder(context)
                    .data(File(path))
                    .memoryCacheKey(decor.thumbnailKey ?: path)
                    .size(Dimension.Pixels(widthPx), Dimension.Undefined)
                    .precision(Precision.INEXACT)
                    .crossfade(120)
                    .build()
            }
            rememberAsyncImagePainter(request)
        } else {
            null
        }
        val success = painter?.state as? AsyncImagePainter.State.Success
        val intrinsic = success?.painter?.intrinsicSize
        val aspect = if (intrinsic != null && intrinsic.width > 0f && intrinsic.height > 0f && intrinsic.width.isFinite()) {
            intrinsic.width / intrinsic.height
        } else {
            placeholderAspect(doc.kind)
        }
        val borderColor = if (selected) c.accent else c.outline
        Box(
            Modifier
                .aspectRatio(aspect, matchHeightConstraintsFirst = aspect <= frameAspect)
                .clip(shape)
                .border(if (selected) 2.dp else 1.dp, borderColor, shape),
        ) {
            if (painter != null && success != null) {
                Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                Placeholder(doc.kind, defaultPaper)
                // Keep the painter drawn so a pending load completes and replaces the placeholder.
                if (painter != null) Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            overlay()
        }
    }
}

@Composable
private fun Placeholder(kind: DocKind, defaultPaper: PaperSpec) {
    val c = Inkwell.colors
    when (kind) {
        DocKind.NOTEBOOK -> PaperPreview(defaultPaper, Modifier.fillMaxSize())
        DocKind.PDF -> Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
            TextLines(Modifier.fillMaxSize().padding(14.dp), c.surfaceAlt)
            Box(Modifier.size(36.dp).clip(R8).background(c.surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(20.dp), tint = c.inkMuted)
            }
        }
        DocKind.TYPED -> Box(Modifier.fillMaxSize().background(c.surface)) {
            TextLines(Modifier.fillMaxSize().padding(14.dp), c.surfaceAlt, heading = true)
        }
        DocKind.WHITEBOARD -> Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
            PaperPreview(PaperSpec(style = PaperStyle.DOT_GRID, color = 0x00000000, lineColor = c.outline.toArgbInt()), Modifier.fillMaxSize())
            Icon(Icons.Outlined.Gesture, null, Modifier.size(22.dp), tint = c.inkMuted)
        }
    }
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt()
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Grey bars suggesting text, for PDF and typed placeholders. */
@Composable
private fun TextLines(modifier: Modifier, color: Color, heading: Boolean = false) {
    Canvas(modifier) {
        val barH = max(3f, size.height * 0.022f)
        val gap = barH * 2.1f
        var y = 0f
        var i = 0
        if (heading) {
            drawRoundRect(color, Offset(0f, 0f), Size(size.width * 0.55f, barH * 1.6f), CornerRadius(barH))
            y += barH * 1.6f + gap * 1.4f
        }
        while (y + barH < size.height) {
            val w = when (i % 5) {
                4 -> 0.62f
                2 -> 0.9f
                else -> 1f
            }
            drawRoundRect(color, Offset(0f, y), Size(size.width * w, barH), CornerRadius(barH / 2f))
            y += barH + gap
            i++
            if (i % 6 == 0) y += gap
        }
    }
}

/**
 * Draws a paper background (blank, ruled, grid, dots, graph) scaled as if the page were
 * [pageWidthPt] points wide. Used by placeholders and the template picker.
 */
@Composable
internal fun PaperPreview(paper: PaperSpec, modifier: Modifier, pageWidthPt: Float = 595f) {
    Canvas(modifier) { drawPaper(paper, pageWidthPt) }
}

internal fun DrawScope.drawPaper(paper: PaperSpec, pageWidthPt: Float) {
    if ((paper.color ushr 24) != 0) drawRect(Color(paper.color))
    val scale = size.width / pageWidthPt
    val spacing = max(paper.effectiveSpacing() * scale, 4f)
    val line = Color(paper.lineColor)
    val stroke = max(1f, 0.5f * scale)
    when (paper.style) {
        PaperStyle.BLANK -> Unit
        PaperStyle.RULED, PaperStyle.NARROW_RULED, PaperStyle.WIDE_RULED -> {
            var y = spacing * 2.5f
            while (y < size.height - spacing * 0.5f) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                y += spacing
            }
        }
        PaperStyle.GRID -> drawGrid(line, spacing, stroke, boldEvery = 0)
        PaperStyle.GRAPH -> drawGrid(line, spacing, stroke * 0.8f, boldEvery = 5)
        PaperStyle.DOT_GRID -> {
            val r = max(1f, stroke * 1.1f)
            var y = spacing
            while (y < size.height) {
                var x = spacing
                while (x < size.width) {
                    drawCircle(line, r, Offset(x, y))
                    x += spacing
                }
                y += spacing
            }
        }
    }
}

private fun DrawScope.drawGrid(line: Color, spacing: Float, stroke: Float, boldEvery: Int) {
    val faint = if (boldEvery > 0) line.copy(alpha = line.alpha * 0.55f) else line
    var i = 1
    var x = spacing
    while (x < size.width) {
        val bold = boldEvery > 0 && i % boldEvery == 0
        drawLine(if (bold) line else faint, Offset(x, 0f), Offset(x, size.height), if (bold) stroke * 1.6f else stroke)
        x += spacing
        i++
    }
    i = 1
    var y = spacing
    while (y < size.height) {
        val bold = boldEvery > 0 && i % boldEvery == 0
        drawLine(if (bold) line else faint, Offset(0f, y), Offset(size.width, y), if (bold) stroke * 1.6f else stroke)
        y += spacing
        i++
    }
}
