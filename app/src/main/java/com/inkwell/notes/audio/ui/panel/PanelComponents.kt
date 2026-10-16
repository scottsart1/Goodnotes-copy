package com.inkwell.notes.audio.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import androidx.compose.ui.res.stringResource

internal val R8 = RoundedCornerShape(8.dp)
internal val R12 = RoundedCornerShape(12.dp)
internal val R16 = RoundedCornerShape(16.dp)

/** 44dp icon button with a 20dp outlined icon. */
@Composable
internal fun AudioIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Inkwell.colors.inkMuted,
) {
    Box(
        modifier
            .size(Dimens.minTouch)
            .clip(R8)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(Dimens.icon), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

/** Small outlined chip with a 44dp touch target. */
@Composable
internal fun AudioChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    contentDescription: String? = null,
) {
    val c = Inkwell.colors
    val fg = when {
        !enabled -> c.inkMuted.copy(alpha = 0.5f)
        selected -> c.ink
        else -> c.inkMuted
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(R8)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription ?: label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(R8)
                .background(if (selected) c.accentContainer else c.surface)
                .border(1.dp, if (selected) c.accent else c.outline, R8)
                .padding(horizontal = if (leading != null) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leading != null) Icon(leading, null, Modifier.size(16.dp), tint = fg)
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Quiet status pill ("Recovered", "Transcript"). */
@Composable
internal fun AudioBadge(label: String, modifier: Modifier = Modifier, color: Color = Inkwell.colors.inkMuted) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, Inkwell.colors.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Primary filled action (accent). */
@Composable
internal fun AudioPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Inkwell.colors
    Box(
        modifier
            .heightIn(min = Dimens.minTouch)
            .clip(R8)
            .background(if (enabled) c.accent else c.surfaceAlt)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.onAccent else c.inkMuted)
    }
}

/** Outlined secondary action. */
@Composable
internal fun AudioSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Inkwell.colors.ink) {
    val c = Inkwell.colors
    Box(
        modifier
            .heightIn(min = Dimens.minTouch)
            .clip(R8)
            .border(1.dp, c.outline, R8)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else c.inkMuted)
    }
}

/** Confirmation dialog in the app's restrained style; [danger] colours the confirm action. */
@Composable
internal fun AudioConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    offerDontAskAgain: Boolean = false,
) {
    val c = Inkwell.colors
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = R16,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                if (offerDontAskAgain) {
                    Row(
                        Modifier.fillMaxWidth().clip(R8).clickable(role = Role.Checkbox) { dontAsk = !dontAsk },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = dontAsk, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.inkMuted, checkmarkColor = c.onAccent),
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        )
                        Text(stringResource(R.string.audio_dont_ask_again), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAsk) }) {
                Text(confirm, color = if (danger) c.danger else c.accent, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.audio_cancel), color = c.inkMuted, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
