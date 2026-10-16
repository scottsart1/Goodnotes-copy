package com.inkwell.notes.whiteboard.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Outlined tool icons that Material Icons does not provide (eraser, highlighter, lasso,
 * connector), drawn on the same 24-unit grid with a 1.7 stroke so they sit with the rest.
 * Icon() tints them like any other vector.
 */
object KitIcons {
    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
            .build()

    val Eraser: ImageVector by lazy {
        outline("Eraser") {
            // Tilted block.
            moveTo(14.2f, 3.6f); lineTo(20.4f, 9.8f); lineTo(11.2f, 19f); lineTo(5f, 12.8f); close()
            // Rubber tip divider.
            moveTo(8.3f, 9.5f); lineTo(14.5f, 15.7f)
            // Floor line.
            moveTo(11.2f, 20.4f); lineTo(20f, 20.4f)
        }
    }

    val Highlighter: ImageVector by lazy {
        outline("Highlighter") {
            moveTo(14.6f, 3.4f); lineTo(20.6f, 9.4f); lineTo(12.4f, 17.6f); lineTo(6.4f, 11.6f); close()
            // Chisel tip.
            moveTo(6.4f, 11.6f); lineTo(4.6f, 15.8f); lineTo(8.2f, 19.4f); lineTo(12.4f, 17.6f)
            // Ink line.
            moveTo(3f, 21.2f); lineTo(13f, 21.2f)
        }
    }

    val Lasso: ImageVector by lazy {
        outline("Lasso") {
            // Open loop.
            moveTo(7.2f, 15.6f)
            curveTo(3.9f, 14.6f, 2.4f, 12.4f, 3.3f, 9.9f)
            curveTo(4.4f, 6.6f, 9.1f, 4.4f, 13.9f, 4.9f)
            curveTo(18.7f, 5.4f, 21.8f, 8.3f, 20.8f, 11.3f)
            curveTo(19.9f, 14.1f, 15.8f, 16.1f, 11.2f, 16f)
            // Knot and tail.
            moveTo(9.6f, 14.4f)
            curveTo(7.7f, 15.4f, 7.6f, 17.2f, 9.1f, 18f)
            curveTo(10.4f, 18.7f, 10.4f, 20.2f, 9.1f, 21f)
        }
    }

    val Connector: ImageVector by lazy {
        outline("Connector") {
            // Source box.
            moveTo(3f, 4.5f); lineTo(9.5f, 4.5f); lineTo(9.5f, 11f); lineTo(3f, 11f); close()
            // Target box.
            moveTo(14.5f, 13f); lineTo(21f, 13f); lineTo(21f, 19.5f); lineTo(14.5f, 19.5f); close()
            // Elbow connector with arrow head.
            moveTo(9.5f, 7.75f); lineTo(12f, 7.75f); lineTo(12f, 16.25f); lineTo(14f, 16.25f)
            moveTo(12.4f, 14.6f); lineTo(14f, 16.25f); lineTo(12.4f, 17.9f)
        }
    }

    val Pen: ImageVector by lazy {
        outline("Pen") {
            // Fountain-pen nib, tilted.
            moveTo(15.5f, 3.5f); lineTo(20.5f, 8.5f); lineTo(17.5f, 11.5f); lineTo(12.5f, 6.5f); close()
            moveTo(12.5f, 6.5f); lineTo(7.5f, 8.5f); lineTo(4f, 20f); lineTo(15.5f, 16.5f); lineTo(17.5f, 11.5f)
            moveTo(4f, 20f); lineTo(9.6f, 14.4f)
            moveTo(11.1f, 13.6f); curveTo(11.6f, 13.6f, 11.6f, 12.4f, 11.1f, 12.4f); curveTo(10.6f, 12.4f, 10.6f, 13.6f, 11.1f, 13.6f)
        }
    }

    val ExportArea: ImageVector by lazy {
        outline("ExportArea") {
            // Dashed-looking frame corners.
            moveTo(3.5f, 8f); lineTo(3.5f, 3.5f); lineTo(8f, 3.5f)
            moveTo(16f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 8f)
            moveTo(3.5f, 16f); lineTo(3.5f, 20.5f); lineTo(8f, 20.5f)
            // Arrow out of the frame.
            moveTo(10f, 14f); lineTo(19.5f, 4.5f)
            moveTo(14.5f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 9.5f)
            moveTo(16f, 20.5f); lineTo(20.5f, 20.5f); lineTo(20.5f, 16f)
        }
    }
}
