package com.inkwell.notes.editor.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.editor.logic.ToolbarItem

/** Outlined icons the Material set lacks, drawn on the same 24-unit grid. */
object EditorIcons {
    private fun outlined(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    val Eraser: ImageVector by lazy {
        outlined("Eraser") {
            moveTo(14.5f, 3.8f); lineTo(20.2f, 9.5f); lineTo(10.4f, 19.3f); lineTo(4.7f, 13.6f); close()
            moveTo(8.8f, 9.5f); lineTo(14.5f, 15.2f)
            moveTo(12.5f, 20.5f); lineTo(20f, 20.5f)
        }
    }

    val Lasso: ImageVector by lazy {
        outlined("Lasso") {
            // Open loop with a tail, the classic lasso mark.
            moveTo(7.4f, 14.0f)
            curveTo(4.9f, 12.9f, 3.6f, 11.3f, 4.1f, 9.3f)
            curveTo(4.9f, 6.1f, 8.6f, 4.0f, 12.6f, 4.0f)
            curveTo(16.9f, 4.0f, 20.2f, 6.4f, 20.0f, 9.6f)
            curveTo(19.8f, 12.6f, 16.3f, 14.8f, 12.0f, 14.9f)
            curveTo(10.4f, 14.9f, 8.8f, 14.6f, 7.4f, 14.0f)
            moveTo(7.4f, 14.0f)
            curveTo(6.2f, 15.6f, 8.6f, 16.9f, 7.8f, 18.6f)
            curveTo(7.3f, 19.6f, 6.4f, 20.2f, 5.6f, 20.6f)
        }
    }

    val Line: ImageVector by lazy { outlined("Line") { moveTo(5f, 19f); lineTo(19f, 5f) } }
    val Arrow: ImageVector by lazy {
        outlined("Arrow") { moveTo(5f, 19f); lineTo(19f, 5f); moveTo(11f, 5f); lineTo(19f, 5f); lineTo(19f, 13f) }
    }
    val DoubleArrow: ImageVector by lazy {
        outlined("DoubleArrow") {
            moveTo(5f, 19f); lineTo(19f, 5f)
            moveTo(12f, 5f); lineTo(19f, 5f); lineTo(19f, 12f)
            moveTo(5f, 12f); lineTo(5f, 19f); lineTo(12f, 19f)
        }
    }
    val Rectangle: ImageVector by lazy { outlined("Rect") { moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 18f); lineTo(4f, 18f); close() } }
    val RoundedRectangle: ImageVector by lazy {
        outlined("RoundRect") {
            moveTo(8f, 6f); lineTo(16f, 6f); quadTo(20f, 6f, 20f, 10f); lineTo(20f, 14f); quadTo(20f, 18f, 16f, 18f)
            lineTo(8f, 18f); quadTo(4f, 18f, 4f, 14f); lineTo(4f, 10f); quadTo(4f, 6f, 8f, 6f); close()
        }
    }
    val Ellipse: ImageVector by lazy {
        outlined("Ellipse") {
            moveTo(3.5f, 12f)
            arcTo(8.5f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 20.5f, y1 = 12f)
            arcTo(8.5f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 3.5f, y1 = 12f)
            close()
        }
    }
    val Triangle: ImageVector by lazy { outlined("Triangle") { moveTo(12f, 4.5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close() } }
    val Polygon: ImageVector by lazy {
        outlined("Polygon") { moveTo(12f, 3.8f); lineTo(20.2f, 9.6f); lineTo(17.1f, 19.2f); lineTo(6.9f, 19.2f); lineTo(3.8f, 9.6f); close() }
    }
    val Star: ImageVector by lazy {
        outlined("Star") {
            moveTo(12f, 3.5f); lineTo(14.5f, 9.2f); lineTo(20.6f, 9.6f); lineTo(15.9f, 13.6f); lineTo(17.4f, 19.6f)
            lineTo(12f, 16.4f); lineTo(6.6f, 19.6f); lineTo(8.1f, 13.6f); lineTo(3.4f, 9.6f); lineTo(9.5f, 9.2f); close()
        }
    }

    fun shape(kind: ShapeKind): ImageVector = when (kind) {
        ShapeKind.LINE -> Line
        ShapeKind.ARROW -> Arrow
        ShapeKind.DOUBLE_ARROW -> DoubleArrow
        ShapeKind.RECTANGLE -> Rectangle
        ShapeKind.ROUNDED_RECTANGLE -> RoundedRectangle
        ShapeKind.ELLIPSE -> Ellipse
        ShapeKind.TRIANGLE -> Triangle
        ShapeKind.POLYGON -> Polygon
        ShapeKind.STAR -> Star
    }

    fun tool(t: ToolType): ImageVector = when (t) {
        ToolType.PEN -> Icons.Outlined.Draw
        ToolType.PENCIL -> Icons.Outlined.Edit
        ToolType.HIGHLIGHTER -> Icons.Outlined.BorderColor
        ToolType.ERASER -> Eraser
        ToolType.LASSO -> Lasso
        ToolType.SHAPE -> Icons.Outlined.Interests
        ToolType.TEXT -> Icons.Outlined.TextFields
        ToolType.IMAGE -> Icons.Outlined.Image
        else -> Icons.Outlined.Draw
    }

    fun item(i: ToolbarItem): ImageVector = when (i) {
        ToolbarItem.UNDO -> Icons.AutoMirrored.Outlined.Undo
        ToolbarItem.REDO -> Icons.AutoMirrored.Outlined.Redo
        ToolbarItem.RECORD -> Icons.Outlined.Mic
        ToolbarItem.AI -> Icons.Outlined.AutoAwesome
        ToolbarItem.OVERFLOW -> Icons.Outlined.MoreHoriz
        else -> tool(i.tool ?: ToolType.PEN)
    }
}
