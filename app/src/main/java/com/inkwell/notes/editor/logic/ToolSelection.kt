package com.inkwell.notes.editor.logic

import com.inkwell.notes.core.model.EraserConfig
import com.inkwell.notes.core.model.LassoFilter
import com.inkwell.notes.core.model.LassoMode
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.ShapeConfig
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType

/** Value ranges of tool settings shown in the options popovers. */
object ToolLimits {
    const val PEN_MIN_WIDTH = 0.4f
    const val PEN_MAX_WIDTH = 8f
    const val HIGHLIGHTER_MIN_WIDTH = 4f
    const val HIGHLIGHTER_MAX_WIDTH = 32f
    const val ERASER_MIN_DP = 4f
    const val ERASER_MAX_DP = 64f
    const val SHAPE_MIN_WIDTH = 0.5f
    const val SHAPE_MAX_WIDTH = 12f
    const val TEXT_MIN_PT = 8f
    const val TEXT_MAX_PT = 72f

    fun widthRange(tool: ToolType): ClosedFloatingPointRange<Float> =
        if (tool == ToolType.HIGHLIGHTER) HIGHLIGHTER_MIN_WIDTH..HIGHLIGHTER_MAX_WIDTH else PEN_MIN_WIDTH..PEN_MAX_WIDTH

    /** Three thickness presets per inking tool (fine, medium, bold). */
    fun widthPresets(tool: ToolType): List<Float> = when (tool) {
        ToolType.HIGHLIGHTER -> listOf(8f, 14f, 22f)
        ToolType.PENCIL -> listOf(0.8f, 1.2f, 2.2f)
        else -> listOf(1.0f, 1.8f, 3.2f)
    }
}

/** The tools offered by the notebook toolbar (whiteboard-only tools are not). */
val NotebookTools: List<ToolType> = listOf(
    ToolType.PEN, ToolType.PENCIL, ToolType.HIGHLIGHTER, ToolType.ERASER,
    ToolType.LASSO, ToolType.SHAPE, ToolType.TEXT, ToolType.IMAGE,
)

val InkingTools: Set<ToolType> = setOf(ToolType.PEN, ToolType.PENCIL, ToolType.HIGHLIGHTER)

/**
 * The editor's tool selection: the persistent [state] (with every tool's own configuration),
 * the tool to return to when leaving the eraser, and the tool temporarily active while the
 * S Pen side button is held. Immutable; every operation returns a new value.
 *
 * The temporary tool is display-only: holding the side button never changes [state], so
 * releasing it always returns to the exact previous pen and configuration.
 */
data class ToolSelection(
    val state: ToolState,
    /** Last non-eraser tool; toggling the eraser off returns here. Never ERASER. */
    val returnTool: ToolType = if (state.tool != ToolType.ERASER) state.tool else ToolType.PEN,
    val temporary: ToolType? = null,
) {
    val tool: ToolType get() = state.tool

    /** What the toolbar highlights as currently writing. */
    val effectiveTool: ToolType get() = temporary ?: state.tool

    fun isTemporarilyActive(t: ToolType): Boolean = temporary == t && state.tool != t

    fun select(t: ToolType): ToolSelection {
        if (t == state.tool) return this
        val newReturn = when {
            t != ToolType.ERASER -> t
            state.tool != ToolType.ERASER -> state.tool
            else -> returnTool
        }
        return copy(state = state.copy(tool = t), returnTool = newReturn)
    }

    /** Eraser on, or back to exactly the tool used before it. */
    fun toggleEraser(): ToolSelection = if (state.tool == ToolType.ERASER) select(returnTool) else select(ToolType.ERASER)

    /** A persistent change requested by the canvas (side button in toggle mode, gestures). */
    fun onToolChangeRequested(t: ToolType): ToolSelection =
        if (t == ToolType.ERASER && state.tool == ToolType.ERASER) select(returnTool) else select(t)

    fun withTemporary(t: ToolType?): ToolSelection = if (t == temporary) this else copy(temporary = t)

    fun penFor(t: ToolType): PenConfig? = when (t) {
        ToolType.PEN -> state.pen
        ToolType.PENCIL -> state.pencil
        ToolType.HIGHLIGHTER -> state.highlighter
        else -> null
    }

    fun updatePen(t: ToolType, transform: (PenConfig) -> PenConfig): ToolSelection {
        val range = ToolLimits.widthRange(t)
        fun fix(p: PenConfig) = transform(p).let {
            it.copy(
                width = it.width.coerceIn(range.start, range.endInclusive),
                pressureSensitivity = it.pressureSensitivity.coerceIn(0f, 1f),
                stabilization = it.stabilization.coerceIn(0f, 1f),
                opacity = it.opacity.coerceIn(0.05f, 1f),
            )
        }
        val s = when (t) {
            ToolType.PEN -> state.copy(pen = fix(state.pen))
            ToolType.PENCIL -> state.copy(pencil = fix(state.pencil))
            ToolType.HIGHLIGHTER -> state.copy(highlighter = fix(state.highlighter))
            else -> return this
        }
        return copy(state = s)
    }

    fun updateEraser(transform: (EraserConfig) -> EraserConfig): ToolSelection = copy(
        state = state.copy(eraser = transform(state.eraser).let { it.copy(sizeDp = it.sizeDp.coerceIn(ToolLimits.ERASER_MIN_DP, ToolLimits.ERASER_MAX_DP)) }),
    )

    fun updateShape(transform: (ShapeConfig) -> ShapeConfig): ToolSelection = copy(
        state = state.copy(shape = transform(state.shape).let { it.copy(strokeWidth = it.strokeWidth.coerceIn(ToolLimits.SHAPE_MIN_WIDTH, ToolLimits.SHAPE_MAX_WIDTH)) }),
    )

    fun updateText(transform: (TextConfig) -> TextConfig): ToolSelection = copy(
        state = state.copy(text = transform(state.text).let { it.copy(fontSizePt = it.fontSizePt.coerceIn(ToolLimits.TEXT_MIN_PT, ToolLimits.TEXT_MAX_PT)) }),
    )

    fun updateLasso(mode: LassoMode = state.lassoMode, filter: LassoFilter = state.lassoFilter): ToolSelection =
        copy(state = state.copy(lassoMode = mode, lassoFilter = filter))

    /** Applies a saved preset: its tool becomes active with exactly the preset's pen. */
    fun applyPreset(preset: PenPreset): ToolSelection {
        val withPen = when (preset.tool) {
            ToolType.PEN -> state.copy(pen = preset.pen)
            ToolType.PENCIL -> state.copy(pencil = preset.pen)
            ToolType.HIGHLIGHTER -> state.copy(highlighter = preset.pen)
            else -> return this
        }
        return copy(state = withPen).select(preset.tool)
    }

    /** True when [preset] describes the active tool exactly. */
    fun matches(preset: PenPreset): Boolean = state.tool == preset.tool && penFor(preset.tool) == preset.pen

    companion object {
        /** Restores a persisted state; tools the notebook does not offer fall back to the pen. */
        fun initial(saved: ToolState): ToolSelection {
            val state = if (saved.tool in NotebookTools) saved else saved.copy(tool = ToolType.PEN)
            return ToolSelection(state)
        }
    }
}
