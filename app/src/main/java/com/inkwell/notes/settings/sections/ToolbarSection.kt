package com.inkwell.notes.settings.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.core.model.ToolbarPosition
import com.inkwell.notes.settings.logic.PaperMath
import com.inkwell.notes.settings.logic.PresetOps
import com.inkwell.notes.settings.ui.ActionRow
import com.inkwell.notes.settings.ui.ChipsRow
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.ConfirmDialog
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.Radius16
import com.inkwell.notes.settings.ui.Radius8
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SettingsIconButton
import com.inkwell.notes.settings.ui.SwitchRow
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun ToolbarSection(s: AppSettings, actions: SettingsActions) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    SectionColumn {
        SettingsGroup("Toolbar") {
            ChipsRow(
                title = "Position",
                subtitle = "Where the tool pill sits in the editor. Floating lets you drag it anywhere.",
                choices = listOf(
                    Choice(ToolbarPosition.TOP, "Top"),
                    Choice(ToolbarPosition.BOTTOM, "Bottom"),
                    Choice(ToolbarPosition.LEFT, "Left"),
                    Choice(ToolbarPosition.RIGHT, "Right"),
                    Choice(ToolbarPosition.FLOATING, "Floating"),
                ),
                selected = s.toolbar.position,
                onSelect = { p -> actions.update { it.copy(toolbar = it.toolbar.copy(position = p)) } },
            )
            RowDivider()
            SwitchRow(
                "Compact toolbar",
                s.toolbar.compact,
                { v -> actions.update { it.copy(toolbar = it.toolbar.copy(compact = v)) } },
                subtitle = "Smaller tool buttons leave more room for the page.",
            )
            RowDivider()
            SwitchRow(
                "Focus mode hides the toolbar",
                s.toolbar.focusModeHidesToolbar,
                { v -> actions.update { it.copy(toolbar = it.toolbar.copy(focusModeHidesToolbar = v)) } },
                subtitle = "In focus mode only the page stays on screen. Swipe in from the edge to bring the tools back.",
            )
        }

        SettingsGroup(
            "Pen presets",
            description = "Presets appear in the pen options for one-tap switching. Save new ones from the pen options in the editor.",
        ) {
            val presets = s.penPresets
            if (presets.isEmpty()) {
                Text(
                    "No presets. Reset to restore the five defaults, or save your own from the pen options.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Inkwell.colors.inkMuted,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 520.dp
                    Column {
                        presets.forEachIndexed { index, preset ->
                            if (index > 0) RowDivider()
                            PresetRow(
                                preset = preset,
                                index = index,
                                count = presets.size,
                                compact = compact,
                                onMove = { delta -> actions.update { it.copy(penPresets = PresetOps.move(it.penPresets, preset.id, delta)) } },
                                onRename = { renaming = preset.id },
                                onDelete = {
                                    actions.update { it.copy(penPresets = PresetOps.delete(it.penPresets, preset.id)) }
                                    actions.messageWithAction("Deleted “${preset.name}”", "Undo") {
                                        actions.update { it.copy(penPresets = PresetOps.restore(it.penPresets, preset, index)) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            RowDivider()
            ActionRow(
                title = "Reset to defaults",
                subtitle = "Thin black, Medium blue, Thick red, Yellow highlighter and Graphite pencil.",
                actionLabel = "Reset",
                onAction = { confirmReset = true },
                enabled = !PresetOps.isDefault(presets),
            )
        }
    }

    val renamingPreset = renaming?.let { id -> s.penPresets.firstOrNull { it.id == id } }
    if (renamingPreset != null) {
        RenamePresetDialog(
            initial = renamingPreset.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                actions.update { it.copy(penPresets = PresetOps.rename(it.penPresets, renamingPreset.id, name)) }
                renaming = null
            },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset pen presets?",
            text = "Your presets are replaced by the five defaults. Pens currently in use are not changed.",
            confirmLabel = "Reset",
            onConfirm = {
                confirmReset = false
                actions.update { it.copy(penPresets = PresetOps.defaults()) }
                actions.message("Pen presets reset")
            },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun PresetRow(
    preset: PenPreset,
    index: Int,
    count: Int,
    compact: Boolean,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresetSwatch(preset, Modifier.size(width = 56.dp, height = 40.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                preset.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(PresetOps.describe(preset), style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 1)
        }
        if (compact) {
            var menu by remember { mutableStateOf(false) }
            Box {
                SettingsIconButton(Icons.Outlined.MoreVert, "Options for ${preset.name}", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Move up", enabled = index > 0) { menu = false; onMove(-1) }
                    MenuItem("Move down", enabled = index < count - 1) { menu = false; onMove(1) }
                    MenuItem("Rename") { menu = false; onRename() }
                    MenuItem("Delete", color = c.danger) { menu = false; onDelete() }
                }
            }
        } else {
            SettingsIconButton(Icons.Outlined.KeyboardArrowUp, "Move ${preset.name} up", { onMove(-1) }, enabled = index > 0)
            SettingsIconButton(Icons.Outlined.KeyboardArrowDown, "Move ${preset.name} down", { onMove(1) }, enabled = index < count - 1)
            SettingsIconButton(Icons.Outlined.Edit, "Rename ${preset.name}", onRename)
            SettingsIconButton(Icons.Outlined.DeleteOutline, "Delete ${preset.name}", onDelete)
        }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, color: Color = Inkwell.colors.ink, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) color else color.copy(alpha = 0.4f)) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 44.dp),
    )
}

/** A short sample stroke in the preset's colour and relative width. */
@Composable
private fun PresetSwatch(preset: PenPreset, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val highlighter = preset.tool == ToolType.HIGHLIGHTER
    Canvas(modifier.semantics { contentDescription = "${preset.name} sample" }) {
        drawRoundRect(PaperWhite, cornerRadius = CornerRadius(8.dp.toPx()))
        drawRoundRect(c.outline, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
        val color = Color(preset.pen.color).copy(alpha = if (highlighter) preset.pen.opacity.coerceIn(0.2f, 1f) else preset.pen.opacity.coerceIn(0.4f, 1f))
        val width = if (highlighter) 9.dp.toPx() else (preset.pen.width * 1.4f).coerceIn(1.2f, 5f) * density
        val path = Path()
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val mid = size.height / 2f
        val amp = if (highlighter) 0f else size.height * 0.18f
        val steps = 24
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val p = Offset(left + (right - left) * t, mid + amp * sin(t * 2.0 * PI).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = width, cap = if (highlighter) StrokeCap.Butt else StrokeCap.Round))
    }
}

/** Presets are previewed on white paper, as they appear on a default page. */
private val PaperWhite = Color(PaperMath.paperColors.first().paper)

@Composable
private fun RenamePresetDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = Inkwell.colors
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val cleaned = PresetOps.cleanName(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = Radius16,
        title = { Text("Rename preset", style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= PresetOps.MAX_NAME_CHARS) value = it },
                singleLine = true,
                label = { Text("Name") },
                isError = cleaned == null,
                supportingText = {
                    Text(
                        if (cleaned == null) "Enter a name" else "${value.text.length} / ${PresetOps.MAX_NAME_CHARS}",
                        color = if (cleaned == null) c.danger else c.inkMuted,
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (cleaned != null) onConfirm(cleaned) }),
                shape = Radius8,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.outline,
                    focusedLabelColor = c.accent,
                    cursorColor = c.accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { QuietButton("Rename", onClick = { cleaned?.let(onConfirm) }, enabled = cleaned != null) },
        dismissButton = { QuietButton("Cancel", onClick = onDismiss, color = c.inkMuted) },
    )
}
