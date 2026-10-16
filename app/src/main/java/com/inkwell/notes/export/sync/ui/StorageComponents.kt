package com.inkwell.notes.export.sync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal val R8 = RoundedCornerShape(8.dp)
internal val R12 = RoundedCornerShape(12.dp)

/** Outlined settings card: 12dp radius, 1dp outline, no shadow. */
@Composable
internal fun StorageCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Inkwell.colors
    Surface(modifier.fillMaxWidth(), shape = R12, color = c.surface, border = BorderStroke(1.dp, c.outline)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (icon != null) {
                    Icon(icon, null, Modifier.padding(top = 1.dp).size(Dimens.icon), tint = c.inkMuted)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                }
            }
            content()
        }
    }
}

/** Destination chip: 32dp tall pill inside a 44dp touch target, check mark when on. */
@Composable
internal fun TargetChip(
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = Inkwell.colors
    val bg = if (selected) c.accentContainer else c.surface
    val border = if (selected) c.accent else c.outline
    val fg = when {
        !enabled && selected -> c.ink.copy(alpha = 0.6f)
        !enabled -> c.inkMuted.copy(alpha = 0.5f)
        selected -> c.ink
        else -> c.inkMuted
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "On" else "Off"
            }
            .clip(R8)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(32.dp).clip(R8).background(bg).border(1.dp, border.copy(alpha = if (enabled) 1f else 0.6f), R8)
                .padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val leading = if (selected) Icons.Outlined.Check else icon
            if (leading != null) {
                Icon(leading, null, Modifier.size(16.dp), tint = if (selected) c.accent.copy(alpha = if (enabled) 1f else 0.6f) else fg)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact segmented control (1dp outline, 8dp radius). */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    Row(
        modifier.heightIn(min = Dimens.minTouch).padding(vertical = 4.dp).clip(R8).border(1.dp, c.outline, R8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, option ->
            val on = option == selected
            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(c.outline))
            Box(
                Modifier.heightIn(min = 36.dp).background(if (on) c.accentContainer else c.surface)
                    .semantics { this.selected = on }
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, color = if (on) c.ink else c.inkMuted, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
internal fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).clip(R8)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.ink else c.inkMuted)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                uncheckedThumbColor = c.inkMuted, uncheckedTrackColor = c.surfaceAlt, uncheckedBorderColor = c.outline,
            ),
        )
    }
}

@Composable
internal fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: ImageVector? = null) {
    val c = Inkwell.colors
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled && !busy, modifier = modifier.heightIn(min = Dimens.minTouch), shape = R8,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.surfaceAlt, disabledContentColor = c.inkMuted),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        ButtonContent(text, icon, busy)
    }
}

@Composable
internal fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: ImageVector? = null) {
    val c = Inkwell.colors
    OutlinedButton(
        onClick = onClick, enabled = enabled && !busy, modifier = modifier.heightIn(min = Dimens.minTouch), shape = R8,
        border = BorderStroke(1.dp, c.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink, disabledContentColor = c.inkMuted),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        ButtonContent(text, icon, busy)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Inkwell.colors.inkMuted)
        Spacer(Modifier.width(8.dp))
    } else if (icon != null) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun QuietAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Inkwell.colors.accent) {
    TextButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.minTouch), shape = R8,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Result line under a test button: green check or red error, readable text. */
@Composable
internal fun CheckResult(state: CheckState, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val (text, ok) = when (state) {
        is CheckState.Ok -> state.message to true
        is CheckState.Failed -> state.message to false
        else -> return
    }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }, verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
            Modifier.padding(top = 1.dp).size(16.dp), tint = if (ok) c.success else c.danger,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) c.ink else c.danger)
    }
}

@Composable
internal fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
internal fun HairlineDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Inkwell.colors.outline))
}

@Composable
internal fun inkFieldColors() = Inkwell.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, disabledBorderColor = c.outline,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, disabledContainerColor = c.surfaceAlt,
        focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.inkMuted,
        focusedPlaceholderColor = c.inkMuted, unfocusedPlaceholderColor = c.inkMuted,
        errorBorderColor = c.danger, errorLabelColor = c.danger, errorSupportingTextColor = c.danger, errorCursorColor = c.danger,
        focusedSupportingTextColor = c.inkMuted, unfocusedSupportingTextColor = c.inkMuted,
        focusedTrailingIconColor = c.inkMuted, unfocusedTrailingIconColor = c.inkMuted,
    )
}

/** 44dp icon button with a 20dp icon. */
@Composable
internal fun SmallIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(Dimens.minTouch).clip(R8).clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(Dimens.icon), tint = Inkwell.colors.inkMuted) }
}

/** Result of a connection/folder check shown inline. */
internal sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Ok(val message: String) : CheckState
    data class Failed(val message: String) : CheckState
}
