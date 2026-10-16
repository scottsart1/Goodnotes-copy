package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal val R8 = RoundedCornerShape(8.dp)
internal val R12 = RoundedCornerShape(12.dp)
internal val R16 = RoundedCornerShape(16.dp)

/** 44dp icon button with a 20dp outlined icon and a hover/long-press tooltip. */
@Composable
internal fun LibIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = Inkwell.colors.inkMuted,
) {
    val c = Inkwell.colors
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier
                .size(Dimens.minTouch)
                .clip(R8)
                .background(if (selected) c.accentContainer else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(Dimens.icon),
                tint = when {
                    !enabled -> tint.copy(alpha = 0.38f)
                    selected -> c.accent
                    else -> tint
                },
            )
        }
    }
}

/** Accent "New"-style button. */
@Composable
internal fun LibPrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Inkwell.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        shape = R8,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = Inkwell.colors.inkMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = Inkwell.colors.outline)
}

/** Dialog shell: 16dp radius, surface colour, text buttons. */
@Composable
internal fun LibDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    wide: Boolean = false,
    content: @Composable () -> Unit,
) {
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (wide) modifier.widthIn(max = 560.dp).padding(horizontal = 16.dp) else modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !wide),
        shape = R16,
        containerColor = c.surface,
        titleContentColor = c.ink,
        textContentColor = c.ink,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = content,
        confirmButton = {
            if (confirmLabel != null) {
                TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                    Text(confirmLabel, color = if (!confirmEnabled) c.inkMuted else if (destructive) c.danger else c.accent)
                }
            }
        },
        dismissButton = dismissLabel?.let {
            { TextButton(onClick = onDismiss) { Text(it, color = c.inkMuted) } }
        },
    )
}

/** Menu row with a 20dp leading icon. */
@Composable
internal fun LibMenuItem(text: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false, trailing: String? = null) {
    val c = Inkwell.colors
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(Dimens.icon)) } },
        trailingIcon = trailing?.let { { Text(it, style = MaterialTheme.typography.labelSmall, color = c.inkMuted) } },
        colors = MenuDefaults.itemColors(
            textColor = if (destructive) c.danger else c.ink,
            leadingIconColor = if (destructive) c.danger else c.inkMuted,
        ),
        modifier = Modifier.heightIn(min = Dimens.minTouch),
    )
}

@Composable
internal fun MenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = Inkwell.colors.outline)
}

@Composable
internal fun libTextFieldColors(): TextFieldColors {
    val c = Inkwell.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.outline,
        focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surface,
        cursorColor = c.accent,
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        focusedPlaceholderColor = c.inkMuted,
        unfocusedPlaceholderColor = c.inkMuted,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.inkMuted,
    )
}

/** Tiny outlined label on thumbnails and rows. */
@Composable
internal fun SmallBadge(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Inkwell.colors
    Row(
        modifier
            .heightIn(min = 20.dp)
            .clip(R8)
            .background(c.surface)
            .border(1.dp, c.outline, R8)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** Centered empty state with one clear action. */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    Column(
        modifier.widthIn(max = 420.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(R16).background(c.surfaceAlt),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(24.dp), tint = c.inkMuted) }
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.size(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(Modifier.size(20.dp))
            LibPrimaryButton(actionLabel, null, onAction)
        }
    }
}
