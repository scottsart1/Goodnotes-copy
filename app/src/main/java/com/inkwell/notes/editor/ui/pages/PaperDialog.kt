package com.inkwell.notes.editor.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.editor.ui.components.LabeledSlider
import com.inkwell.notes.editor.ui.components.SectionLabel
import com.inkwell.notes.editor.ui.components.Segmented
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Paper colours offered for pages, with matching line colours. */
enum class PaperColor(val paper: Int, val line: Int, val label: Int) {
    WHITE(0xFFFFFFFF.toInt(), 0xFFD6E0F0.toInt(), R.string.editor_paper_white),
    IVORY(0xFFFBF6E9.toInt(), 0xFFE2D9C2.toInt(), R.string.editor_paper_ivory),
    GRAY(0xFFEFEFEC.toInt(), 0xFFD2D2CC.toInt(), R.string.editor_paper_gray),
    DARK(0xFF262522.toInt(), 0xFF45433E.toInt(), R.string.editor_paper_dark);

    companion object {
        fun of(argb: Int): PaperColor = entries.firstOrNull { (it.paper and 0xFFFFFF) == (argb and 0xFFFFFF) } ?: WHITE
    }
}

private const val MM_PER_PT = 25.4f / 72f

/** What the paper dialog decided. [sizePt] is null when the page size should stay. */
data class PaperChoice(val paper: PaperSpec, val sizePt: Pair<Float, Float>?, val applyToAll: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperDialog(page: PageMeta, selectedCount: Int, onApply: (PaperChoice) -> Unit, onDismiss: () -> Unit) {
    var style by remember { mutableStateOf(page.paper.style) }
    var color by remember { mutableStateOf(PaperColor.of(page.paper.color)) }
    var spacing by remember { mutableFloatStateOf(page.paper.effectiveSpacing().takeIf { it > 0f } ?: 24f) }
    var spacingTouched by remember { mutableStateOf(page.paper.spacingPt > 0f) }
    val detected = remember { detectSize(page) }
    var preset by remember { mutableStateOf(detected.first) }
    var orientation by remember { mutableStateOf(detected.second) }
    var customW by remember { mutableStateOf((max(page.widthPt, 1f) * MM_PER_PT).roundToInt().toString()) }
    var customH by remember { mutableStateOf((max(page.heightPt, 1f) * MM_PER_PT).roundToInt().toString()) }
    var sizeTouched by remember { mutableStateOf(false) }
    var allPages by remember { mutableStateOf(false) }
    val isPdf = page.pdfPageIndex != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_paper_title)) },
        text = {
            Column(Modifier.widthIn(min = 300.dp, max = 560.dp).verticalScroll(rememberScrollState())) {
                Segmented(
                    options = listOf(false, true),
                    selected = allPages,
                    label = {
                        if (it) stringResource(R.string.editor_paper_all_pages)
                        else if (selectedCount > 1) stringResource(R.string.editor_paper_selected_pages, selectedCount)
                        else stringResource(R.string.editor_paper_this_page)
                    },
                    onSelect = { allPages = it },
                )
                SectionLabel(stringResource(R.string.editor_paper_style))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PaperStyle.entries.forEach { s ->
                        PaperTile(
                            spec = PaperSpec(s, color.paper, color.line, if (spacingTouched) spacing else 0f),
                            label = stringResource(paperStyleLabel(s)),
                            selected = style == s,
                            onClick = { style = s },
                        )
                    }
                }
                SectionLabel(stringResource(R.string.editor_paper_color))
                Segmented(PaperColor.entries.toList(), color, { stringResource(it.label) }, { color = it })
                if (style != PaperStyle.BLANK) {
                    LabeledSlider(
                        label = stringResource(R.string.editor_line_spacing),
                        value = spacing,
                        range = 8f..48f,
                        valueText = stringResource(R.string.editor_pt_value, spacing.roundToInt()),
                        onChange = { spacing = it.roundToInt().toFloat(); spacingTouched = true },
                    )
                }
                SectionLabel(stringResource(R.string.editor_page_size))
                if (isPdf && !allPages) {
                    Text(stringResource(R.string.editor_pdf_size_fixed), style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PageSizePreset.entries.forEach { p ->
                            SizeChip(p.label.takeIf { p != PageSizePreset.CUSTOM } ?: stringResource(R.string.editor_size_custom), preset == p) {
                                preset = p; sizeTouched = true
                            }
                        }
                    }
                    if (preset == PageSizePreset.CUSTOM) {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                customW, { customW = it.filter(Char::isDigit).take(4); sizeTouched = true },
                                label = { Text(stringResource(R.string.editor_width_mm)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                            )
                            Box(Modifier.width(8.dp))
                            OutlinedTextField(
                                customH, { customH = it.filter(Char::isDigit).take(4); sizeTouched = true },
                                label = { Text(stringResource(R.string.editor_height_mm)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SectionLabel(stringResource(R.string.editor_orientation))
                    Segmented(
                        listOf(Orientation.PORTRAIT, Orientation.LANDSCAPE), orientation,
                        { stringResource(if (it == Orientation.PORTRAIT) R.string.editor_portrait else R.string.editor_landscape) },
                        { orientation = it; sizeTouched = true },
                    )
                    if (isPdf) Text(stringResource(R.string.editor_pdf_size_skipped), style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            val customValid = preset != PageSizePreset.CUSTOM || ((customW.toIntOrNull() ?: 0) in 50..2000 && (customH.toIntOrNull() ?: 0) in 50..2000)
            TextButton(enabled = customValid, onClick = {
                val paper = PaperSpec(style, color.paper, color.line, if (spacingTouched && style != PaperStyle.BLANK) spacing else 0f)
                val size = if (!sizeTouched) null else {
                    val t = PageTemplate(
                        size = preset,
                        orientation = orientation,
                        customWidthPt = (customW.toIntOrNull() ?: 210) / MM_PER_PT,
                        customHeightPt = (customH.toIntOrNull() ?: 297) / MM_PER_PT,
                    )
                    t.widthPt() to t.heightPt()
                }
                onApply(PaperChoice(paper, size, allPages))
            }) { Text(stringResource(R.string.editor_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

private fun detectSize(page: PageMeta): Pair<PageSizePreset, Orientation> {
    val w = page.widthPt
    val h = page.heightPt
    val orientation = if (w > h) Orientation.LANDSCAPE else Orientation.PORTRAIT
    val small = min(w, h)
    val large = max(w, h)
    val match = PageSizePreset.entries.firstOrNull { it != PageSizePreset.CUSTOM && abs(min(it.widthPt, it.heightPt) - small) < 2f && abs(max(it.widthPt, it.heightPt) - large) < 2f }
    return (match ?: PageSizePreset.CUSTOM) to orientation
}

@Composable
private fun SizeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    com.inkwell.notes.editor.ui.components.ToggleChip(text, selected, onClick)
}

/** A small paper preview drawn with the real spacing proportions. */
@Composable
fun PaperTile(spec: PaperSpec, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Inkwell.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 60.dp, height = 80.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(spec.color))
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.outline, RoundedCornerShape(6.dp)),
        ) {
            Canvas(Modifier.size(width = 60.dp, height = 80.dp)) {
                // Previews show a 150 x 200 pt corner of the page.
                val scale = size.width / 150f
                val s = spec.effectiveSpacing() * scale
                val line = Color(spec.lineColor)
                if (s <= 0f) return@Canvas
                when (spec.style) {
                    PaperStyle.RULED, PaperStyle.NARROW_RULED, PaperStyle.WIDE_RULED -> {
                        var y = s * 1.5f
                        while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += s }
                    }
                    PaperStyle.GRID, PaperStyle.GRAPH -> {
                        var i = 0
                        var x = 0f
                        while (x <= size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), if (spec.style == PaperStyle.GRAPH && i % 5 == 0) 1.4f else 0.8f); x += s; i++ }
                        i = 0
                        var y = 0f
                        while (y <= size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), if (spec.style == PaperStyle.GRAPH && i % 5 == 0) 1.4f else 0.8f); y += s; i++ }
                    }
                    PaperStyle.DOT_GRID -> {
                        var y = s
                        while (y < size.height) {
                            var x = s
                            while (x < size.width) { drawCircle(line, 1.4f, Offset(x, y)); x += s }
                            y += s
                        }
                    }
                    PaperStyle.BLANK -> Unit
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) c.accent else c.inkMuted, modifier = Modifier.padding(top = 4.dp).heightIn(min = 16.dp))
    }
}

fun paperStyleLabel(s: PaperStyle): Int = when (s) {
    PaperStyle.BLANK -> R.string.editor_paper_blank
    PaperStyle.RULED -> R.string.editor_paper_ruled
    PaperStyle.NARROW_RULED -> R.string.editor_paper_narrow
    PaperStyle.WIDE_RULED -> R.string.editor_paper_wide
    PaperStyle.GRID -> R.string.editor_paper_grid
    PaperStyle.DOT_GRID -> R.string.editor_paper_dots
    PaperStyle.GRAPH -> R.string.editor_paper_graph
}

/** Shows the effective page size of [page] in millimetres. */
fun pageSizeMm(page: PageMeta): String =
    "${(page.widthPt * MM_PER_PT).roundToInt()} × ${(page.heightPt * MM_PER_PT).roundToInt()} mm"

