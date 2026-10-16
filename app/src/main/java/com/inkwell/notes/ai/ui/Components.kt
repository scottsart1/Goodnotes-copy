package com.inkwell.notes.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal val Radius8 = RoundedCornerShape(8.dp)
internal val Radius12 = RoundedCornerShape(12.dp)
internal val Radius16 = RoundedCornerShape(16.dp)

/** 44dp square icon button with a 20dp outlined icon. */
@Composable
internal fun PanelIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Inkwell.colors.inkMuted,
) {
    Box(
        modifier.size(Dimens.minTouch).clip(Radius8)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(Dimens.icon), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

/** Small outlined chip; 44dp touch target. */
@Composable
internal fun InkChip(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
    onRemove: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = Inkwell.colors
    val bg = if (selected) c.accentContainer else c.surface
    val border = if (selected) c.accent else c.outline
    val fg = if (!enabled) c.inkMuted.copy(alpha = 0.5f) else if (selected) c.ink else c.inkMuted
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .clip(Radius8)
            .background(bg)
            .border(1.dp, border, Radius8)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = if (leadingIcon != null) 8.dp else 12.dp, end = if (onRemove != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, Modifier.size(16.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) {
            Box(
                Modifier.padding(start = 2.dp).size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Remove $label", onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, "Remove $label", Modifier.size(14.dp), tint = fg) }
        }
    }
}

/** Outlined section card (12dp radius, 1dp outline). */
@Composable
internal fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Inkwell.colors
    Surface(modifier.fillMaxWidth(), shape = Radius12, color = c.surface, border = BorderStroke(1.dp, c.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                    }
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).clip(Radius8)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
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
internal fun inkFieldColors() = Inkwell.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, disabledBorderColor = c.outline,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, disabledContainerColor = c.surfaceAlt,
        focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.inkMuted, focusedPlaceholderColor = c.inkMuted, unfocusedPlaceholderColor = c.inkMuted,
        errorBorderColor = c.danger, errorSupportingTextColor = c.danger,
    )
}

/** A labelled dropdown anchored to an outlined field-like button. */
@Composable
internal fun <T> InkDropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: (T) -> Boolean = { false },
    optionDetail: ((T) -> String?)? = null,
    header: ((T) -> String?)? = null,
    enabled: Boolean = true,
) {
    val c = Inkwell.colors
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).clip(Radius8)
                    .border(1.dp, c.outline, Radius8)
                    .background(if (enabled) c.surface else c.surfaceAlt)
                    .clickable(enabled = enabled && options.isNotEmpty(), role = Role.DropdownList) { open = true }
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selectedText, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface, shape = Radius12, border = BorderStroke(1.dp, c.outline)) {
                var lastHeader: String? = null
                for (o in options) {
                    val h = header?.invoke(o)
                    if (h != null && h != lastHeader) {
                        Text(h, style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp, end = 16.dp))
                        lastHeader = h
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(optionText(o), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                                optionDetail?.invoke(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkMuted) }
                            }
                        },
                        trailingIcon = if (isSelected(o)) ({ Icon(Icons.Outlined.Check, "Selected", Modifier.size(Dimens.icon), tint = c.accent) }) else null,
                        onClick = { open = false; onSelect(o) },
                        modifier = Modifier.heightIn(min = Dimens.minTouch),
                    )
                }
            }
        }
    }
}

@Composable
internal fun InkTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Inkwell.colors.accent) {
    TextButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.minTouch), shape = Radius8,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Filled accent button (flat, no gradient). */
@Composable
internal fun InkPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = Inkwell.colors
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.minTouch), shape = Radius8,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.surfaceAlt, disabledContentColor = c.inkMuted),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun InkOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, color: Color = Inkwell.colors.ink) {
    val c = Inkwell.colors
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.minTouch), shape = Radius8,
        border = BorderStroke(1.dp, c.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Notice / warning strip with an outline (no fill gradients). */
@Composable
internal fun Banner(text: String, icon: ImageVector, tone: Color, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    val c = Inkwell.colors
    Row(
        modifier.fillMaxWidth().clip(Radius8).background(c.surfaceAlt).border(1.dp, c.outline, Radius8).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(Dimens.icon), tint = tone)
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f).padding(start = 10.dp))
        action?.invoke(this)
    }
}

@Composable
internal fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * Column that scrolls itself when given a bounded height, and lays out normally when the
 * host already scrolls (avoids nested-scroll crashes in either placement).
 */
@Composable
internal fun AdaptiveScrollColumn(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp), content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(Modifier.fillMaxWidth().then(scroll).padding(contentPadding), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
