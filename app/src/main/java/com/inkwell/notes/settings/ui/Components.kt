package com.inkwell.notes.settings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal val Radius8 = RoundedCornerShape(8.dp)
internal val Radius12 = RoundedCornerShape(12.dp)
internal val Radius16 = RoundedCornerShape(16.dp)

/** A choice in a segmented control or chip group. */
internal data class Choice<T>(val value: T, val label: String, val enabled: Boolean = true, val icon: ImageVector? = null)

/**
 * A titled group of settings: heading and optional description above an outlined card.
 * Rows inside the card bring their own 16dp padding; use [RowDivider] between them.
 */
@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Inkwell.colors
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = c.inkMuted,
                modifier = Modifier.padding(start = 4.dp, bottom = if (description == null) 8.dp else 2.dp).semantics { heading() },
            )
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = c.inkMuted,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
        }
        Surface(Modifier.fillMaxWidth(), shape = Radius12, color = c.surface, border = BorderStroke(1.dp, c.outline)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Inkwell.colors.outline))
}

/** A row whose whole surface toggles the switch. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(title, subtitle, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                uncheckedThumbColor = c.inkMuted,
                uncheckedTrackColor = c.surfaceAlt,
                uncheckedBorderColor = c.outline,
            ),
        )
    }
}

/** Title and subtitle with a full-width segmented control below. */
@Composable
internal fun <T> SegmentedRow(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowLabel(title, subtitle, enabled)
        Segmented(choices, selected, onSelect, enabled = enabled)
        footer?.invoke(this)
    }
}

/** Equal-width outlined segments (8dp radius, 44dp tall). */
@Composable
internal fun <T> Segmented(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Inkwell.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.minTouch)
            .clip(Radius8)
            .border(1.dp, c.outline, Radius8)
            .selectableGroup(),
    ) {
        choices.forEachIndexed { i, choice ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
            val isSelected = choice.value == selected
            val itemEnabled = enabled && choice.enabled
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) c.accentContainer else Color.Transparent)
                    .selectable(selected = isSelected, enabled = itemEnabled, role = Role.RadioButton, onClick = { onSelect(choice.value) })
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.alpha(if (itemEnabled) 1f else 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (choice.icon != null) Icon(choice.icon, null, Modifier.size(18.dp), tint = if (isSelected) c.ink else c.inkMuted)
                    Text(
                        choice.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) c.ink else c.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Wrapping single-choice chips for longer option lists. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { choice ->
            SelectChip(
                label = choice.label,
                selected = choice.value == selected,
                enabled = enabled && choice.enabled,
                onClick = { onSelect(choice.value) },
                icon = choice.icon,
            )
        }
    }
}

@Composable
internal fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null, role: Role = Role.RadioButton) {
    val c = Inkwell.colors
    Row(
        Modifier
            .height(Dimens.minTouch)
            .clip(Radius8)
            .background(if (selected) c.accentContainer else c.surface)
            .border(1.dp, if (selected) c.accent else c.outline, Radius8)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = 14.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (selected) c.ink else c.inkMuted)
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.ink else c.inkMuted, maxLines = 1)
    }
}

/** A setting with a button on the right. */
@Composable
internal fun ActionRow(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(title, subtitle, true, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        OutlinedButton(
            onClick = onAction,
            enabled = enabled,
            shape = Radius8,
            border = BorderStroke(1.dp, if (destructive && enabled) c.danger.copy(alpha = 0.6f) else c.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (destructive) c.danger else c.ink),
            modifier = Modifier.heightIn(min = Dimens.minTouch),
        ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
    }
}

/** A plain informational row inside a group. */
@Composable
internal fun InfoRow(title: String, value: String, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, textAlign = TextAlign.End)
    }
}

internal enum class NoteTone { NEUTRAL, WARNING, DANGER }

/** A small icon + text note (explanations, warnings). */
@Composable
internal fun Note(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoteTone = NoteTone.NEUTRAL,
    icon: ImageVector = Icons.Outlined.Info,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Inkwell.colors
    val color = when (tone) {
        NoteTone.NEUTRAL -> c.inkMuted
        NoteTone.WARNING -> c.warning
        NoteTone.DANGER -> c.danger
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (tone == NoteTone.NEUTRAL) c.inkMuted else color)
            if (action != null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, content = action)
        }
    }
}

/** A 44dp icon button with a 20dp icon. */
@Composable
internal fun SettingsIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Inkwell.colors.inkMuted,
) {
    Box(
        modifier
            .size(Dimens.minTouch)
            .clip(Radius8)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(Dimens.icon), tint = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Inkwell.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Radius8,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        modifier = modifier.heightIn(min = Dimens.minTouch),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Inkwell.colors.accent) {
    TextButton(onClick = onClick, enabled = enabled, shape = Radius8, modifier = modifier.heightIn(min = Dimens.minTouch)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else color.copy(alpha = 0.4f))
    }
}

/** Confirmation dialog for destructive or irreversible actions. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = Radius16,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) },
        confirmButton = {
            QuietButton(confirmLabel, onClick = onConfirm, color = if (destructive) c.danger else c.accent)
        },
        dismissButton = { QuietButton("Cancel", onClick = onDismiss, color = c.inkMuted) },
    )
}
