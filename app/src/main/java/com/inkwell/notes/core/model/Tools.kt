package com.inkwell.notes.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ToolType { PEN, PENCIL, HIGHLIGHTER, ERASER, LASSO, SHAPE, TEXT, IMAGE, HAND, STICKY, CONNECTOR }

@Serializable
data class PenConfig(
    val type: PenType,
    /** ARGB. Highlighters carry their translucency in [opacity], not in alpha. */
    val color: Int,
    /** Nominal width in page points. */
    val width: Float,
    /** 0..1: 0 = constant width, 1 = strong pressure response. */
    val pressureSensitivity: Float = 0.6f,
    /** 0..1: amount of stroke smoothing / stabilisation. */
    val stabilization: Float = 0.3f,
    /** 0..1. */
    val opacity: Float = 1f,
) {
    companion object {
        val DefaultPen = PenConfig(PenType.FOUNTAIN, 0xFF1A1916.toInt(), 1.6f, 0.6f, 0.3f, 1f)
        val DefaultPencil = PenConfig(PenType.PENCIL, 0xFF3B3B3B.toInt(), 1.2f, 0.7f, 0.2f, 0.9f)
        val DefaultHighlighter = PenConfig(PenType.HIGHLIGHTER, 0xFFF2D04B.toInt(), 14f, 0f, 0.5f, 0.45f)
    }
}

/** How the eraser decides what to remove. */
@Serializable
enum class EraserMode {
    /** Removes only the ink under the eraser, splitting strokes. */
    PRECISION,
    /** Removes whole strokes/items touched. */
    STROKE,
    /** Removes the part of a stroke between its crossings with other strokes (or its sharp corners). */
    SEGMENT,
    /** Removes the whole handwritten word (proximity cluster) touched. */
    WORD,
}

/** Which content types the eraser may touch. */
@Serializable
enum class EraserFilter { ALL, INK_ONLY, HIGHLIGHTER_ONLY, SHAPES_ONLY }

@Serializable
data class EraserConfig(
    val mode: EraserMode = EraserMode.STROKE,
    val filter: EraserFilter = EraserFilter.ALL,
    /** Diameter in screen dp for PRECISION; hit tolerance for the others. */
    val sizeDp: Float = 16f,
)

@Serializable
data class ShapeConfig(
    val kind: ShapeKind = ShapeKind.RECTANGLE,
    val strokeColor: Int = 0xFF1A1916.toInt(),
    val fillColor: Int = 0,
    val strokeWidth: Float = 2f,
)

@Serializable
data class TextConfig(
    val fontSizePt: Float = 14f,
    val color: Int = 0xFF1A1916.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
)

@Serializable
enum class LassoMode { FREEFORM, RECTANGLE }

/** Which item types a lasso may pick up. */
@Serializable
data class LassoFilter(
    val handwriting: Boolean = true,
    val highlighter: Boolean = true,
    val shapes: Boolean = true,
    val images: Boolean = true,
    val text: Boolean = true,
)

/**
 * Everything the canvas needs to know about the active tool. Each tool keeps its own
 * configuration so switching tools (including the S Pen side-button eraser) always
 * returns to the exact previous pen settings.
 */
@Serializable
data class ToolState(
    val tool: ToolType = ToolType.PEN,
    val pen: PenConfig = PenConfig.DefaultPen,
    val pencil: PenConfig = PenConfig.DefaultPencil,
    val highlighter: PenConfig = PenConfig.DefaultHighlighter,
    val eraser: EraserConfig = EraserConfig(),
    val shape: ShapeConfig = ShapeConfig(),
    val text: TextConfig = TextConfig(),
    val lassoMode: LassoMode = LassoMode.FREEFORM,
    val lassoFilter: LassoFilter = LassoFilter(),
    val stickyColor: Int = 0xFFFFE89A.toInt(),
) {
    /** The pen configuration for an inking tool, or null for non-inking tools. */
    fun activePen(): PenConfig? = when (tool) {
        ToolType.PEN -> pen
        ToolType.PENCIL -> pencil
        ToolType.HIGHLIGHTER -> highlighter
        else -> null
    }
}

/** A saved pen combination ("Thin black pen", "Yellow highlighter", ...). */
@Serializable
data class PenPreset(
    val id: String,
    val name: String,
    val tool: ToolType,
    val pen: PenConfig,
) {
    companion object {
        val Defaults = listOf(
            PenPreset("preset-thin-black", "Thin black", ToolType.PEN, PenConfig(PenType.BALLPOINT, 0xFF1A1916.toInt(), 1.0f, 0.3f)),
            PenPreset("preset-medium-blue", "Medium blue", ToolType.PEN, PenConfig(PenType.FOUNTAIN, 0xFF2952CC.toInt(), 1.8f, 0.6f)),
            PenPreset("preset-thick-red", "Thick red", ToolType.PEN, PenConfig(PenType.BRUSH, 0xFFC93A2E.toInt(), 3.2f, 0.8f)),
            PenPreset("preset-yellow-hl", "Yellow highlighter", ToolType.HIGHLIGHTER, PenConfig.DefaultHighlighter),
            PenPreset("preset-graphite", "Graphite pencil", ToolType.PENCIL, PenConfig.DefaultPencil),
        )
    }
}
