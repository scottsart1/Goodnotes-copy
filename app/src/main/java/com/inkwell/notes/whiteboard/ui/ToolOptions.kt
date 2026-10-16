package com.inkwell.notes.whiteboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.EraserMode
import com.inkwell.notes.core.model.LassoMode
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.kit.ColorSwatch
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.Segmented
import kotlin.math.roundToInt

/** The options popover content for the selected tool. */
@Composable
fun ToolOptions(tool: ToolType, state: ToolState, presets: List<PenPreset>, onChange: (ToolState) -> Unit) {
    Column(Modifier.widthIn(min = 264.dp, max = 320.dp).padding(12.dp)) {
        when (tool) {
            ToolType.PEN, ToolType.PENCIL, ToolType.HIGHLIGHTER -> PenOptions(tool, state, presets, onChange)
            ToolType.ERASER -> EraserOptions(state, onChange)
            ToolType.LASSO -> {
                SectionLabel(R.string.wb_lasso_mode)
                Segmented(
                    listOf(stringResource(R.string.wb_lasso_freeform), stringResource(R.string.wb_lasso_rectangle)),
                    if (state.lassoMode == LassoMode.FREEFORM) 0 else 1,
                    { onChange(state.copy(lassoMode = if (it == 0) LassoMode.FREEFORM else LassoMode.RECTANGLE)) },
                )
            }
            ToolType.SHAPE -> ShapeOptions(state, onChange)
            ToolType.STICKY -> {
                SectionLabel(R.string.wb_sticky_color)
                Swatches(BoardPalettes.stickies, state.stickyColor) { onChange(state.copy(stickyColor = it)) }
                Hint(R.string.wb_sticky_hint)
            }
            ToolType.TEXT -> TextOptions(state, onChange)
            ToolType.CONNECTOR -> Hint(R.string.wb_connector_hint)
            else -> Unit
        }
    }
}

@Composable
private fun PenOptions(tool: ToolType, state: ToolState, presets: List<PenPreset>, onChange: (ToolState) -> Unit) {
    val pen = state.activePen() ?: return
    fun set(p: PenConfig) = onChange(
        when (tool) {
            ToolType.PENCIL -> state.copy(pencil = p)
            ToolType.HIGHLIGHTER -> state.copy(highlighter = p)
            else -> state.copy(pen = p)
        },
    )
    if (tool == ToolType.PEN) {
        SectionLabel(R.string.wb_options_style)
        val styles = listOf(PenType.BALLPOINT, PenType.FOUNTAIN, PenType.BRUSH)
        Segmented(
            listOf(stringResource(R.string.wb_style_ballpoint), stringResource(R.string.wb_style_fountain), stringResource(R.string.wb_style_brush)),
            styles.indexOf(pen.type).coerceAtLeast(0),
            { set(pen.copy(type = styles[it])) },
        )
        Spacer(Modifier.height(12.dp))
    }
    SectionLabel(R.string.wb_options_color)
    Swatches(BoardPalettes.forTool(tool), pen.color) { set(pen.copy(color = it)) }
    Spacer(Modifier.height(8.dp))
    SectionLabel(R.string.wb_options_thickness)
    ThicknessRow(tool, pen.width, pen.color) { set(pen.copy(width = it)) }
    if (presets.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        SectionLabel(R.string.wb_options_presets)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p ->
                PresetChip(p, active = p.tool == tool && p.pen.type == pen.type && BoardPalettes.sameColor(p.pen.color, pen.color) && p.pen.width == pen.width) {
                    onChange(
                        when (p.tool) {
                            ToolType.PENCIL -> state.copy(tool = ToolType.PENCIL, pencil = p.pen)
                            ToolType.HIGHLIGHTER -> state.copy(tool = ToolType.HIGHLIGHTER, highlighter = p.pen)
                            else -> state.copy(tool = ToolType.PEN, pen = p.pen)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EraserOptions(state: ToolState, onChange: (ToolState) -> Unit) {
    val e = state.eraser
    SectionLabel(R.string.wb_eraser_mode)
    Segmented(
        listOf(stringResource(R.string.wb_eraser_precision), stringResource(R.string.wb_eraser_stroke)),
        if (e.mode == EraserMode.PRECISION) 0 else 1,
        { onChange(state.copy(eraser = e.copy(mode = if (it == 0) EraserMode.PRECISION else EraserMode.STROKE))) },
    )
    Spacer(Modifier.height(12.dp))
    SectionLabel(R.string.wb_eraser_size)
    val sizes = listOf(10f to R.string.wb_eraser_small, 18f to R.string.wb_eraser_medium, 32f to R.string.wb_eraser_large)
    val current = sizes.withIndex().minByOrNull { kotlin.math.abs(it.value.first - e.sizeDp) }?.index ?: 1
    Row(verticalAlignment = Alignment.CenterVertically) {
        sizes.forEachIndexed { i, (size, label) ->
            Dot(diameter = (6 + i * 5).dp, color = Inkwell.colors.inkMuted, selected = i == current, description = stringResource(label)) {
                onChange(state.copy(eraser = e.copy(sizeDp = size)))
            }
        }
    }
}

@Composable
private fun ShapeOptions(state: ToolState, onChange: (ToolState) -> Unit) {
    val s = state.shape
    SectionLabel(R.string.wb_shape_kind)
    val kinds = listOf(
        Triple(ShapeKind.LINE, Icons.Outlined.HorizontalRule, R.string.wb_shape_line),
        Triple(ShapeKind.ARROW, Icons.AutoMirrored.Outlined.ArrowRightAlt, R.string.wb_shape_arrow),
        Triple(ShapeKind.DOUBLE_ARROW, Icons.Outlined.SwapHoriz, R.string.wb_shape_double_arrow),
        Triple(ShapeKind.RECTANGLE, Icons.Outlined.Rectangle, R.string.wb_shape_rectangle),
        Triple(ShapeKind.ROUNDED_RECTANGLE, Icons.Outlined.CropSquare, R.string.wb_shape_rounded),
        Triple(ShapeKind.ELLIPSE, Icons.Outlined.RadioButtonUnchecked, R.string.wb_shape_ellipse),
        Triple(ShapeKind.TRIANGLE, Icons.Outlined.ChangeHistory, R.string.wb_shape_triangle),
    )
    FlowRow {
        kinds.forEach { (kind, icon, label) ->
            KitIconButton(icon, stringResource(label), { onChange(state.copy(shape = s.copy(kind = kind))) }, selected = s.kind == kind)
        }
    }
    Spacer(Modifier.height(8.dp))
    SectionLabel(R.string.wb_shape_outline)
    Swatches(BoardPalettes.pen, s.strokeColor) { onChange(state.copy(shape = s.copy(strokeColor = it))) }
    Spacer(Modifier.height(8.dp))
    SectionLabel(R.string.wb_shape_fill)
    Swatches(BoardPalettes.fills, s.fillColor) { onChange(state.copy(shape = s.copy(fillColor = it))) }
    Spacer(Modifier.height(8.dp))
    SectionLabel(R.string.wb_options_thickness)
    ThicknessRow(ToolType.SHAPE, s.strokeWidth, s.strokeColor) { onChange(state.copy(shape = s.copy(strokeWidth = it))) }
}

@Composable
private fun TextOptions(state: ToolState, onChange: (ToolState) -> Unit) {
    val t = state.text
    val c = Inkwell.colors
    SectionLabel(R.string.wb_text_size)
    Row(verticalAlignment = Alignment.CenterVertically) {
        KitIconButton(Icons.Outlined.Remove, stringResource(R.string.wb_text_smaller), { onChange(state.copy(text = t.copy(fontSizePt = (t.fontSizePt - 2f).coerceAtLeast(8f)))) })
        Text(stringResource(R.string.wb_pt_value, t.fontSizePt.roundToInt()), style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.width(52.dp))
        KitIconButton(Icons.Outlined.Add, stringResource(R.string.wb_text_larger), { onChange(state.copy(text = t.copy(fontSizePt = (t.fontSizePt + 2f).coerceAtMost(96f)))) })
        Spacer(Modifier.width(8.dp))
        KitIconButton(Icons.Outlined.FormatBold, stringResource(R.string.wb_bold), { onChange(state.copy(text = t.copy(bold = !t.bold))) }, selected = t.bold)
        KitIconButton(Icons.Outlined.FormatItalic, stringResource(R.string.wb_italic), { onChange(state.copy(text = t.copy(italic = !t.italic))) }, selected = t.italic)
    }
    Spacer(Modifier.height(8.dp))
    SectionLabel(R.string.wb_options_color)
    Swatches(BoardPalettes.pen, t.color) { onChange(state.copy(text = t.copy(color = it))) }
    Hint(R.string.wb_text_hint)
}

@Composable
private fun SectionLabel(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelMedium,
        color = Inkwell.colors.inkMuted,
        modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
    )
}

@Composable
private fun Hint(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.bodySmall,
        color = Inkwell.colors.inkMuted,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
    )
}

@Composable
private fun Swatches(colors: List<NamedColor>, current: Int, onPick: (Int) -> Unit) {
    FlowRow(maxItemsInEachRow = 6) {
        colors.forEach { nc ->
            ColorSwatch(nc.argb, BoardPalettes.sameColor(nc.argb, current), stringResource(nc.name), { onPick(nc.argb) })
        }
    }
}

@Composable
private fun ThicknessRow(tool: ToolType, width: Float, color: Int, onPick: (Float) -> Unit) {
    val widths = BoardPalettes.widths(tool)
    val selected = BoardPalettes.nearestWidth(tool, width)
    val labels = listOf(R.string.wb_thickness_fine, R.string.wb_thickness_medium, R.string.wb_thickness_bold)
    val dotColor = if ((color ushr 24) == 0) Inkwell.colors.inkMuted else Color(color).copy(alpha = 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        widths.forEachIndexed { i, w ->
            Dot(diameter = (6 + i * 5).dp, color = dotColor, selected = i == selected, description = stringResource(labels[i])) { onPick(w) }
        }
    }
}

@Composable
private fun Dot(diameter: androidx.compose.ui.unit.Dp, color: Color, selected: Boolean, description: String, onClick: () -> Unit) {
    val c = Inkwell.colors
    Box(
        Modifier
            .size(Dimens.minTouch)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(32.dp).border(2.dp, c.accent, CircleShape))
        Box(Modifier.size(diameter).clip(CircleShape).background(color))
    }
}

@Composable
private fun PresetChip(preset: PenPreset, active: Boolean, onClick: () -> Unit) {
    val c = Inkwell.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .border(1.dp, if (active) c.accent else c.outline, shape)
            .background(if (active) c.accentContainer else c.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(preset.pen.color)))
        Spacer(Modifier.width(6.dp))
        Text(preset.name, style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1)
    }
}
