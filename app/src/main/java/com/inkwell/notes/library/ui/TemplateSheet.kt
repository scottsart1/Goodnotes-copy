package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.library.TemplateChoices
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal fun paperStyleLabel(style: PaperStyle): Int = when (style) {
    PaperStyle.BLANK -> R.string.lib_paper_blank
    PaperStyle.RULED -> R.string.lib_paper_ruled
    PaperStyle.NARROW_RULED -> R.string.lib_paper_narrow
    PaperStyle.WIDE_RULED -> R.string.lib_paper_wide
    PaperStyle.GRID -> R.string.lib_paper_grid
    PaperStyle.DOT_GRID -> R.string.lib_paper_dots
    PaperStyle.GRAPH -> R.string.lib_paper_graph
}

/**
 * "Notebook from template": page size (A3/A4/A5/Letter/Legal/Custom), orientation, paper style
 * with live previews and paper colour, optionally saved as the default for new notebooks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotebookTemplateSheet(
    defaults: PageTemplate,
    onCreate: (PageTemplate, makeDefault: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var size by remember { mutableStateOf(defaults.size) }
    var orientation by remember { mutableStateOf(defaults.orientation) }
    var style by remember { mutableStateOf(defaults.paper.style) }
    var color by remember { mutableStateOf(TemplateChoices.colorFor(defaults.paper)) }
    var widthMm by remember {
        mutableStateOf(if (defaults.customWidthPt > 0f) TemplateChoices.displayMm(defaults.customWidthPt) else "210")
    }
    var heightMm by remember {
        mutableStateOf(if (defaults.customHeightPt > 0f) TemplateChoices.displayMm(defaults.customHeightPt) else "297")
    }
    var makeDefault by remember { mutableStateOf(false) }
    val template = TemplateChoices.build(size, orientation, style, color, widthMm, heightMm, defaults.paper)
    val previewAspect = template?.let { TemplateChoices.aspect(it) } ?: TemplateChoices.aspect(defaults)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = c.surface,
        contentColor = c.ink,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.lib_template_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
            Spacer(Modifier.height(20.dp))

            SheetLabel(stringResource(R.string.lib_template_size))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in TemplateChoices.sizes) ChoiceChip(s.label, size == s) { size = s }
            }
            if (size == PageSizePreset.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MmField(stringResource(R.string.lib_template_width_mm), widthMm, Modifier.weight(1f)) { widthMm = it }
                    MmField(stringResource(R.string.lib_template_height_mm), heightMm, Modifier.weight(1f)) { heightMm = it }
                }
                if (template == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.lib_template_custom_hint), style = MaterialTheme.typography.bodySmall, color = c.danger)
                }
            }
            Spacer(Modifier.height(20.dp))

            SheetLabel(stringResource(R.string.lib_template_orientation))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(stringResource(R.string.lib_template_portrait), orientation == Orientation.PORTRAIT) { orientation = Orientation.PORTRAIT }
                ChoiceChip(stringResource(R.string.lib_template_landscape), orientation == Orientation.LANDSCAPE) { orientation = Orientation.LANDSCAPE }
            }
            Spacer(Modifier.height(20.dp))

            SheetLabel(stringResource(R.string.lib_template_paper))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (s in TemplateChoices.styles) {
                    val selected = s == style
                    val label = stringResource(paperStyleLabel(s))
                    Column(
                        Modifier
                            .width(88.dp)
                            .clip(R8)
                            .selectable(selected = selected, role = Role.RadioButton) { style = s }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.height(96.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val shape = R8
                            PaperPreview(
                                paper = defaults.paper.copy(style = s, color = color.paper, lineColor = color.lines),
                                modifier = Modifier
                                    .aspectRatio(previewAspect, matchHeightConstraintsFirst = previewAspect <= 80f / 96f)
                                    .clip(shape)
                                    .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.outline, shape),
                                pageWidthPt = template?.widthPt() ?: defaults.widthPt(),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) c.accent else c.inkMuted, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            SheetLabel(stringResource(R.string.lib_template_colour))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (pc in TemplateChoices.paperColors) {
                    val selected = pc == color
                    Box(
                        Modifier
                            .size(Dimens.minTouch)
                            .clip(CircleShape)
                            .selectable(selected = selected, role = Role.RadioButton) { color = pc }
                            .semantics { contentDescription = pc.name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(pc.paper))
                                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.outline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = c.accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.minTouch)
                    .clip(R8)
                    .toggleable(value = makeDefault, role = Role.Checkbox) { makeDefault = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = makeDefault,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.inkMuted, checkmarkColor = c.onAccent),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.lib_template_make_default), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel), color = c.inkMuted) }
                Spacer(Modifier.width(8.dp))
                LibPrimaryButton(
                    stringResource(R.string.lib_template_create),
                    null,
                    { template?.let { onCreate(it, makeDefault) } },
                    enabled = template != null,
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Inkwell.colors.inkMuted)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MmField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        singleLine = true,
        label = { Text(label) },
        isError = TemplateChoices.parseMm(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = R8,
        colors = libTextFieldColors(),
        modifier = modifier,
    )
}

/** Outlined choice chip with a 44dp touch target. */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Inkwell.colors
    Box(
        Modifier
            .heightIn(min = Dimens.minTouch)
            .clip(R8)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .clip(R8)
                .background(if (selected) c.accentContainer else c.surface)
                .border(1.dp, if (selected) c.accent else c.outline, R8)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.accent else c.ink)
        }
    }
}
