package com.inkwell.notes.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.EraserFilter
import com.inkwell.notes.core.model.EraserMode
import com.inkwell.notes.core.model.LassoMode
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.editor.logic.ToolLimits
import com.inkwell.notes.editor.logic.ToolSelection
import com.inkwell.notes.editor.ui.components.EditorIcons
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.components.LabeledSlider
import com.inkwell.notes.editor.ui.components.SectionLabel
import com.inkwell.notes.editor.ui.components.Segmented
import com.inkwell.notes.editor.ui.components.Swatch
import com.inkwell.notes.editor.ui.components.ToggleChip
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.abs

/** Which colour a custom-colour pick is for. */
enum class ColorTarget { PEN, PENCIL, HIGHLIGHTER, SHAPE_STROKE, SHAPE_FILL, TEXT }

/** Actions the options popover cannot perform itself. */
class ToolOptionsActions(
    val onCustomColor: (ColorTarget, Int) -> Unit,
    val onSavePreset: (ToolType, PenConfig) -> Unit,
    val onEditPreset: (PenPreset) -> Unit,
    val onPickPhoto: () -> Unit,
    val onCamera: () -> Unit,
    val onPasteImage: () -> Unit,
    val onSelectAll: () -> Unit,
)

/** The small popover a selected tool opens. */
@Composable
fun ToolOptionsPopover(
    tool: ToolType,
    tools: ToolSelection,
    presets: List<PenPreset>,
    onChange: (ToolSelection) -> Unit,
    actions: ToolOptionsActions,
) {
    val maxH = (LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(240).dp
    Column(
        Modifier
            .width(316.dp)
            .heightIn(max = maxH)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when (tool) {
            ToolType.PEN, ToolType.PENCIL, ToolType.HIGHLIGHTER -> PenOptions(tool, tools, presets, onChange, actions)
            ToolType.ERASER -> EraserOptions(tools, onChange)
            ToolType.LASSO -> LassoOptions(tools, onChange, actions)
            ToolType.SHAPE -> ShapeOptions(tools, onChange, actions)
            ToolType.TEXT -> TextOptions(tools, onChange, actions)
            ToolType.IMAGE -> ImageOptions(actions)
            else -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenOptions(tool: ToolType, tools: ToolSelection, presets: List<PenPreset>, onChange: (ToolSelection) -> Unit, actions: ToolOptionsActions) {
    val pen = tools.penFor(tool) ?: return
    fun update(transform: (PenConfig) -> PenConfig) = onChange(tools.updatePen(tool, transform))

    if (tool == ToolType.PEN) {
        SectionLabel(stringResource(R.string.editor_pen_style))
        Segmented(
            options = listOf(PenType.BALLPOINT, PenType.FOUNTAIN, PenType.BRUSH),
            selected = pen.type.takeIf { it in setOf(PenType.BALLPOINT, PenType.FOUNTAIN, PenType.BRUSH) } ?: PenType.FOUNTAIN,
            label = { stringResource(penTypeLabel(it)) },
            onSelect = { t -> update { it.copy(type = t) } },
        )
    }

    SectionLabel(stringResource(R.string.editor_color))
    val palette = Palettes.forTool(tool)
    FlowRow(horizontalArrangement = Arrangement.Start) {
        palette.forEach { ink ->
            Swatch(ink.argb, selected = sameColor(pen.color, ink.argb), onClick = { update { it.copy(color = ink.argb) } }, contentDescription = stringResource(ink.name))
        }
        if (palette.none { sameColor(it.argb, pen.color) }) {
            Swatch(pen.color, selected = true, onClick = {}, contentDescription = stringResource(R.string.editor_color_custom))
        }
        IconAction(
            Icons.Outlined.Colorize,
            stringResource(R.string.editor_custom_color),
            onClick = {
                actions.onCustomColor(
                    when (tool) { ToolType.PENCIL -> ColorTarget.PENCIL; ToolType.HIGHLIGHTER -> ColorTarget.HIGHLIGHTER; else -> ColorTarget.PEN },
                    pen.color,
                )
            },
        )
    }

    SectionLabel(stringResource(R.string.editor_thickness))
    val range = ToolLimits.widthRange(tool)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolLimits.widthPresets(tool).forEachIndexed { i, w ->
            ThicknessDot(
                dotDp = 6f + i * 5f,
                selected = abs(pen.width - w) < 0.05f,
                color = Color(pen.color),
                description = stringResource(listOf(R.string.editor_thin, R.string.editor_medium, R.string.editor_thick)[i]),
                onClick = { update { it.copy(width = w) } },
            )
        }
    }
    LabeledSlider(
        label = stringResource(R.string.editor_width),
        value = pen.width,
        range = range,
        valueText = stringResource(R.string.editor_points_value, pen.width),
        onChange = { v -> update { it.copy(width = (v * 10f).toInt() / 10f) } },
    )
    if (tool != ToolType.HIGHLIGHTER) {
        LabeledSlider(
            label = stringResource(R.string.editor_pressure),
            value = pen.pressureSensitivity,
            range = 0f..1f,
            valueText = "${(pen.pressureSensitivity * 100).toInt()}%",
            onChange = { v -> update { it.copy(pressureSensitivity = v) } },
        )
    } else {
        LabeledSlider(
            label = stringResource(R.string.editor_opacity),
            value = pen.opacity,
            range = 0.15f..0.8f,
            valueText = "${(pen.opacity * 100).toInt()}%",
            onChange = { v -> update { it.copy(opacity = v) } },
        )
    }
    LabeledSlider(
        label = stringResource(R.string.editor_stabilization),
        value = pen.stabilization,
        range = 0f..1f,
        valueText = "${(pen.stabilization * 100).toInt()}%",
        onChange = { v -> update { it.copy(stabilization = v) } },
    )

    SectionLabel(stringResource(R.string.editor_presets))
    PresetsRow(presets, tools, onApply = { onChange(tools.applyPreset(it)) }, onEdit = actions.onEditPreset)
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.minTouch)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(role = Role.Button, onClick = { actions.onSavePreset(tool, pen) })
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, null, Modifier.size(Dimens.icon), tint = Inkwell.colors.accent)
        Text(stringResource(R.string.editor_save_as_preset), style = MaterialTheme.typography.labelLarge, color = Inkwell.colors.accent, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Tap applies a preset instantly; long-press renames or deletes it. */
@Composable
fun PresetsRow(presets: List<PenPreset>, tools: ToolSelection, onApply: (PenPreset) -> Unit, onEdit: (PenPreset) -> Unit) {
    val c = Inkwell.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { p ->
            val active = tools.matches(p)
            Row(
                Modifier
                    .height(Dimens.minTouch)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) c.accentContainer else c.surface)
                    .border(1.dp, if (active) c.accent.copy(alpha = 0.4f) else c.outline, RoundedCornerShape(8.dp))
                    .combinedClickable(role = Role.Button, onClick = { onApply(p) }, onLongClick = { onEdit(p) })
                    .semantics { selected = active }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(p.pen.color).copy(alpha = if (p.tool == ToolType.HIGHLIGHTER) 0.7f else 1f)),
                )
                Text(
                    p.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ThicknessDot(dotDp: Float, selected: Boolean, color: Color, description: String, onClick: () -> Unit) {
    val c = Inkwell.colors
    Box(
        Modifier
            .size(Dimens.minTouch)
            .clip(CircleShape)
            .combinedClickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (selected) c.accentContainer else Color.Transparent)
                .border(1.dp, if (selected) c.accent.copy(alpha = 0.5f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(dotDp.dp).clip(CircleShape).background(color))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EraserOptions(tools: ToolSelection, onChange: (ToolSelection) -> Unit) {
    val e = tools.state.eraser
    SectionLabel(stringResource(R.string.editor_eraser_mode))
    Segmented(
        options = EraserMode.entries.toList(),
        selected = e.mode,
        label = {
            stringResource(
                when (it) {
                    EraserMode.PRECISION -> R.string.editor_eraser_precision
                    EraserMode.STROKE -> R.string.editor_eraser_stroke
                    EraserMode.SEGMENT -> R.string.editor_eraser_segment
                    EraserMode.WORD -> R.string.editor_eraser_word
                },
            )
        },
        onSelect = { m -> onChange(tools.updateEraser { it.copy(mode = m) }) },
    )
    SectionLabel(stringResource(R.string.editor_eraser_erases))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EraserFilter.entries.forEach { f ->
            ToggleChip(
                text = stringResource(
                    when (f) {
                        EraserFilter.ALL -> R.string.editor_filter_all
                        EraserFilter.INK_ONLY -> R.string.editor_filter_ink
                        EraserFilter.HIGHLIGHTER_ONLY -> R.string.editor_filter_highlighter
                        EraserFilter.SHAPES_ONLY -> R.string.editor_filter_shapes
                    },
                ),
                selected = e.filter == f,
                onClick = { onChange(tools.updateEraser { it.copy(filter = f) }) },
            )
        }
    }
    LabeledSlider(
        label = stringResource(R.string.editor_size),
        value = e.sizeDp,
        range = ToolLimits.ERASER_MIN_DP..ToolLimits.ERASER_MAX_DP,
        valueText = stringResource(R.string.editor_dp_value, e.sizeDp.toInt()),
        onChange = { v -> onChange(tools.updateEraser { it.copy(sizeDp = v) }) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LassoOptions(tools: ToolSelection, onChange: (ToolSelection) -> Unit, actions: ToolOptionsActions) {
    val s = tools.state
    SectionLabel(stringResource(R.string.editor_lasso_shape))
    Segmented(
        options = LassoMode.entries.toList(),
        selected = s.lassoMode,
        label = { stringResource(if (it == LassoMode.FREEFORM) R.string.editor_lasso_freeform else R.string.editor_lasso_rectangle) },
        onSelect = { onChange(tools.updateLasso(mode = it)) },
    )
    SectionLabel(stringResource(R.string.editor_lasso_selects))
    val f = s.lassoFilter
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleChip(stringResource(R.string.editor_lasso_handwriting), f.handwriting, { onChange(tools.updateLasso(filter = f.copy(handwriting = !f.handwriting))) })
        ToggleChip(stringResource(R.string.editor_lasso_highlighter), f.highlighter, { onChange(tools.updateLasso(filter = f.copy(highlighter = !f.highlighter))) })
        ToggleChip(stringResource(R.string.editor_lasso_shapes), f.shapes, { onChange(tools.updateLasso(filter = f.copy(shapes = !f.shapes))) })
        ToggleChip(stringResource(R.string.editor_lasso_images), f.images, { onChange(tools.updateLasso(filter = f.copy(images = !f.images))) })
        ToggleChip(stringResource(R.string.editor_lasso_text), f.text, { onChange(tools.updateLasso(filter = f.copy(text = !f.text))) })
    }
    ActionRow(Icons.Outlined.SelectAll, stringResource(R.string.editor_select_all_page), actions.onSelectAll)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShapeOptions(tools: ToolSelection, onChange: (ToolSelection) -> Unit, actions: ToolOptionsActions) {
    val sh = tools.state.shape
    val c = Inkwell.colors
    SectionLabel(stringResource(R.string.editor_shape))
    FlowRow(horizontalArrangement = Arrangement.Start) {
        ShapeKind.entries.forEach { k ->
            IconAction(
                EditorIcons.shape(k),
                stringResource(shapeLabel(k)),
                onClick = { onChange(tools.updateShape { it.copy(kind = k) }) },
                selected = sh.kind == k,
            )
        }
    }
    SectionLabel(stringResource(R.string.editor_stroke_color))
    FlowRow {
        Palettes.pen.forEach { ink ->
            Swatch(ink.argb, sameColor(sh.strokeColor, ink.argb), { onChange(tools.updateShape { it.copy(strokeColor = ink.argb) }) }, stringResource(ink.name))
        }
        IconAction(Icons.Outlined.Colorize, stringResource(R.string.editor_custom_color), onClick = { actions.onCustomColor(ColorTarget.SHAPE_STROKE, sh.strokeColor) })
    }
    SectionLabel(stringResource(R.string.editor_fill_color))
    val noneLabel = stringResource(R.string.editor_color_none)
    FlowRow {
        Palettes.fills.forEach { ink ->
            if (ink.argb == 0) {
                Box(
                    Modifier
                        .size(Dimens.minTouch)
                        .clip(CircleShape)
                        .combinedClickable(role = Role.RadioButton, onClick = { onChange(tools.updateShape { it.copy(fillColor = 0) }) })
                        .semantics { contentDescription = noneLabel; selected = sh.fillColor == 0 },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .border(if (sh.fillColor == 0) 2.dp else 1.dp, if (sh.fillColor == 0) c.accent else c.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.width(18.dp).height(1.5.dp).background(c.inkMuted)) }
                }
            } else {
                Swatch(ink.argb, sameColor(sh.fillColor, ink.argb), { onChange(tools.updateShape { it.copy(fillColor = ink.argb) }) }, stringResource(ink.name))
            }
        }
        IconAction(Icons.Outlined.Colorize, stringResource(R.string.editor_custom_color), onClick = { actions.onCustomColor(ColorTarget.SHAPE_FILL, if (sh.fillColor == 0) 0xFFF2D04B.toInt() else sh.fillColor) })
    }
    LabeledSlider(
        label = stringResource(R.string.editor_width),
        value = sh.strokeWidth,
        range = ToolLimits.SHAPE_MIN_WIDTH..ToolLimits.SHAPE_MAX_WIDTH,
        valueText = stringResource(R.string.editor_points_value, sh.strokeWidth),
        onChange = { v -> onChange(tools.updateShape { it.copy(strokeWidth = (v * 10f).toInt() / 10f) }) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextOptions(tools: ToolSelection, onChange: (ToolSelection) -> Unit, actions: ToolOptionsActions) {
    val t = tools.state.text
    LabeledSlider(
        label = stringResource(R.string.editor_text_size),
        value = t.fontSizePt,
        range = ToolLimits.TEXT_MIN_PT..ToolLimits.TEXT_MAX_PT,
        valueText = stringResource(R.string.editor_pt_value, t.fontSizePt.toInt()),
        onChange = { v -> onChange(tools.updateText { it.copy(fontSizePt = v.toInt().toFloat()) }) },
    )
    Row {
        IconAction(Icons.Outlined.FormatBold, stringResource(R.string.editor_bold), onClick = { onChange(tools.updateText { it.copy(bold = !it.bold) }) }, selected = t.bold)
        IconAction(Icons.Outlined.FormatItalic, stringResource(R.string.editor_italic), onClick = { onChange(tools.updateText { it.copy(italic = !it.italic) }) }, selected = t.italic)
    }
    SectionLabel(stringResource(R.string.editor_color))
    FlowRow {
        Palettes.pen.forEach { ink ->
            Swatch(ink.argb, sameColor(t.color, ink.argb), { onChange(tools.updateText { it.copy(color = ink.argb) }) }, stringResource(ink.name))
        }
        IconAction(Icons.Outlined.Colorize, stringResource(R.string.editor_custom_color), onClick = { actions.onCustomColor(ColorTarget.TEXT, t.color) })
    }
    Text(
        stringResource(R.string.editor_text_hint),
        style = MaterialTheme.typography.bodySmall,
        color = Inkwell.colors.inkMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ImageOptions(actions: ToolOptionsActions) {
    SectionLabel(stringResource(R.string.editor_insert_image))
    ActionRow(Icons.Outlined.PhotoLibrary, stringResource(R.string.editor_image_photos), actions.onPickPhoto)
    ActionRow(Icons.Outlined.PhotoCamera, stringResource(R.string.editor_image_camera), actions.onCamera)
    ActionRow(Icons.Outlined.ContentPaste, stringResource(R.string.editor_image_paste), actions.onPasteImage)
    Text(
        stringResource(R.string.editor_image_hint),
        style = MaterialTheme.typography.bodySmall,
        color = Inkwell.colors.inkMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.minTouch)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(Dimens.icon), tint = if (enabled) c.ink else c.inkMuted)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) c.ink else c.inkMuted, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Same visible colour (alpha ignored); 0 means "no colour" and only matches itself. */
fun sameColor(a: Int, b: Int): Boolean = if (a == 0 || b == 0) a == b else (a and 0xFFFFFF) == (b and 0xFFFFFF)

fun penTypeLabel(t: PenType): Int = when (t) {
    PenType.BALLPOINT -> R.string.editor_pen_ballpoint
    PenType.FOUNTAIN -> R.string.editor_pen_fountain
    PenType.BRUSH -> R.string.editor_pen_brush
    PenType.PENCIL -> R.string.editor_tool_pencil
    PenType.HIGHLIGHTER -> R.string.editor_tool_highlighter
}

fun shapeLabel(k: ShapeKind): Int = when (k) {
    ShapeKind.LINE -> R.string.editor_shape_line
    ShapeKind.ARROW -> R.string.editor_shape_arrow
    ShapeKind.DOUBLE_ARROW -> R.string.editor_shape_double_arrow
    ShapeKind.RECTANGLE -> R.string.editor_shape_rectangle
    ShapeKind.ROUNDED_RECTANGLE -> R.string.editor_shape_rounded
    ShapeKind.ELLIPSE -> R.string.editor_shape_ellipse
    ShapeKind.TRIANGLE -> R.string.editor_shape_triangle
    ShapeKind.POLYGON -> R.string.editor_shape_polygon
    ShapeKind.STAR -> R.string.editor_shape_star
}

fun toolLabel(t: ToolType): Int = when (t) {
    ToolType.PEN -> R.string.editor_tool_pen
    ToolType.PENCIL -> R.string.editor_tool_pencil
    ToolType.HIGHLIGHTER -> R.string.editor_tool_highlighter
    ToolType.ERASER -> R.string.editor_tool_eraser
    ToolType.LASSO -> R.string.editor_tool_lasso
    ToolType.SHAPE -> R.string.editor_tool_shapes
    ToolType.TEXT -> R.string.editor_tool_text
    ToolType.IMAGE -> R.string.editor_tool_image
    else -> R.string.editor_tool_pen
}
