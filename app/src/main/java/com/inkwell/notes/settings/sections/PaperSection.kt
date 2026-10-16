package com.inkwell.notes.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.settings.logic.PaperColorOption
import com.inkwell.notes.settings.logic.PaperMath
import com.inkwell.notes.settings.ui.ChipsRow
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.Note
import com.inkwell.notes.settings.ui.PagePreview
import com.inkwell.notes.settings.ui.PaperTile
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.Radius8
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.RowLabel
import com.inkwell.notes.settings.ui.SegmentedRow
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SliderRow
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.roundToInt

@Composable
internal fun PaperSection(s: AppSettings, actions: SettingsActions) {
    val t = s.notebookDefaults.template
    fun setTemplate(transform: (PageTemplate) -> PageTemplate) =
        actions.update { it.copy(notebookDefaults = it.notebookDefaults.copy(template = transform(it.notebookDefaults.template))) }
    fun setPaper(transform: (PaperSpec) -> PaperSpec) = setTemplate { it.copy(paper = transform(it.paper)) }

    SectionColumn {
        TemplateSummary(t)

        SettingsGroup("Page") {
            ChipsRow(
                title = "Page size",
                choices = PaperMath.sizes.map { Choice(it, it.label) },
                selected = t.size,
                onSelect = { size ->
                    setTemplate { cur ->
                        if (size == PageSizePreset.CUSTOM && (cur.customWidthPt <= 0f || cur.customHeightPt <= 0f)) {
                            cur.copy(size = size, customWidthPt = cur.widthPtPortraitBase(), customHeightPt = cur.heightPtPortraitBase())
                        } else {
                            cur.copy(size = size)
                        }
                    }
                },
                footer = if (t.size == PageSizePreset.CUSTOM) {
                    { CustomSizeFields(t) { w, h -> setTemplate { PaperMath.withCustomSize(it, w, h) } } }
                } else {
                    null
                },
            )
            RowDivider()
            SegmentedRow(
                title = "Orientation",
                choices = listOf(
                    Choice(Orientation.PORTRAIT, "Portrait", icon = Icons.Outlined.CropPortrait),
                    Choice(Orientation.LANDSCAPE, "Landscape", icon = Icons.Outlined.CropLandscape),
                ),
                selected = t.orientation,
                onSelect = { o -> setTemplate { it.copy(orientation = o) } },
            )
        }

        SettingsGroup("Paper") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RowLabel("Style", null, enabled = true)
                StylePicker(t.paper) { style -> setPaper { it.copy(style = style) } }
            }
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RowLabel("Paper colour", "Lines adjust to suit the paper.", enabled = true)
                ColorPicker(t.paper) { opt -> setPaper { it.copy(color = opt.paper, lineColor = opt.lines) } }
            }
            RowDivider()
            val blank = t.paper.style == PaperStyle.BLANK
            SliderRow(
                title = "Line spacing",
                subtitle = if (blank) "Blank paper has no lines." else null,
                value = t.paper.effectiveSpacing().takeIf { it > 0f } ?: 24f,
                onCommit = { v -> setPaper { it.copy(spacingPt = PaperMath.clampSpacing(v)) } },
                valueRange = PaperMath.SPACING_MIN_PT..PaperMath.SPACING_MAX_PT,
                steps = ((PaperMath.SPACING_MAX_PT - PaperMath.SPACING_MIN_PT) / PaperMath.SPACING_STEP_PT).roundToInt() - 1,
                snap = PaperMath::clampSpacing,
                valueLabel = { v -> if (blank) "—" else "${v.roundToInt()} pt" },
                enabled = !blank,
                startLabel = "Tighter",
                endLabel = "Roomier",
            )
            if (!blank && t.paper.spacingPt > 0f) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp)) {
                    Spacer(Modifier.weight(1f))
                    QuietButton("Use the style's default spacing", onClick = { setPaper { it.copy(spacingPt = 0f) } })
                }
            }
        }

        Note("Used for new notebooks and new pages. Existing pages keep their paper; change it from the page menu in a notebook.")
    }
}

private fun PageTemplate.widthPtPortraitBase(): Float = minOf(size.widthPt, size.heightPt).let { if (it > 0f) it else PageSizePreset.A4.widthPt }
private fun PageTemplate.heightPtPortraitBase(): Float = maxOf(size.widthPt, size.heightPt).let { if (it > 0f) it else PageSizePreset.A4.heightPt }

@Composable
private fun TemplateSummary(t: PageTemplate) {
    val c = Inkwell.colors
    val description = "${PaperMath.sizeLabel(t)}, ${PaperMath.orientationLabel(t.orientation)}, ${PaperMath.styleLabel(t.paper.style)}, ${PaperMath.matchColor(t.paper)?.name ?: "custom colour"}"
    val ratio = (t.widthPt() / t.heightPt()).takeIf { it.isFinite() && it > 0f } ?: (595f / 842f)
    val preview: @Composable (Modifier) -> Unit = { m ->
        Box(m.aspectRatio(ratio, matchHeightConstraintsFirst = true)) {
            PagePreview(
                style = t.paper.style,
                paperArgb = t.paper.color,
                lineArgb = t.paper.lineColor,
                spacingPt = t.paper.effectiveSpacing(),
                pageWidthPt = t.widthPt(),
                description = "Preview of a new page: $description",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("New pages", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            Text(PaperMath.sizeLabel(t), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(
                "${PaperMath.orientationLabel(t.orientation)} · ${PaperMath.styleLabel(t.paper.style)} · ${PaperMath.spacingLabel(t.paper)}",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(Radius8).background(c.desk).padding(20.dp)) {
        if (maxWidth >= 440.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                preview(Modifier.height(168.dp))
                text(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                preview(Modifier.height(132.dp))
                text(Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StylePicker(paper: PaperSpec, onSelect: (PaperStyle) -> Unit) {
    val c = Inkwell.colors
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PaperMath.styles.forEach { style ->
            val selected = style == paper.style
            Column(
                Modifier
                    .clip(Radius8)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(style) })
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 84.dp)
                        .border(if (selected) 2.dp else 0.dp, if (selected) c.accent else Color.Transparent, Radius8)
                        .padding(if (selected) 3.dp else 0.dp),
                ) {
                    PaperTile(style, paper.color, paper.lineColor, Modifier.fillMaxWidth().height(if (selected) 78.dp else 84.dp))
                }
                Text(
                    PaperMath.styleLabel(style),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) c.ink else c.inkMuted,
                )
            }
        }
    }
}

/** Check mark on dark paper swatches: the lightest paper colour. */
private val LightCheck = Color(PaperMath.paperColors.first().paper)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(paper: PaperSpec, onSelect: (PaperColorOption) -> Unit) {
    val c = Inkwell.colors
    val current = PaperMath.matchColor(paper)
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaperMath.paperColors.forEach { opt ->
            val selected = current?.paper == opt.paper
            Box(
                Modifier
                    .size(Dimens.minTouch)
                    .clip(CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(opt) })
                    .semantics { contentDescription = opt.name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(opt.paper))
                        .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            null,
                            Modifier.size(16.dp),
                            tint = if (PaperMath.isDark(opt.paper)) LightCheck else c.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomSizeFields(t: PageTemplate, onValid: (Float, Float) -> Unit) {
    val c = Inkwell.colors
    val (w0, h0) = PaperMath.customMm(t)
    var width by rememberSaveable { mutableStateOf(w0) }
    var height by rememberSaveable { mutableStateOf(h0) }
    val w = PaperMath.parseMm(width)
    val h = PaperMath.parseMm(height)
    fun commit(nw: String, nh: String) {
        val pw = PaperMath.parseMm(nw)
        val ph = PaperMath.parseMm(nh)
        if (pw != null && ph != null) onValid(pw, ph)
    }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.outline,
        focusedLabelColor = c.accent,
        cursorColor = c.accent,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = width,
                onValueChange = { v -> width = v.take(7); commit(width, height) },
                label = { Text("Width") },
                suffix = { Text("mm") },
                singleLine = true,
                isError = w == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = Radius8,
                colors = colors,
                modifier = Modifier.width(150.dp),
            )
            OutlinedTextField(
                value = height,
                onValueChange = { v -> height = v.take(7); commit(width, height) },
                label = { Text("Height") },
                suffix = { Text("mm") },
                singleLine = true,
                isError = h == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = Radius8,
                colors = colors,
                modifier = Modifier.width(150.dp),
            )
        }
        Text(
            if (w == null || h == null) "Enter sizes between ${PaperMath.MIN_CUSTOM_MM.roundToInt()} and ${PaperMath.MAX_CUSTOM_MM.roundToInt()} mm."
            else "Orientation decides which side is longer.",
            style = MaterialTheme.typography.bodySmall,
            color = if (w == null || h == null) c.danger else c.inkMuted,
        )
    }
}
